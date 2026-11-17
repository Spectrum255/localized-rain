package com.spectrum255.localizedrain;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.api.StormProfile;
import com.spectrum255.localizedrain.api.StormProfiles;
import com.spectrum255.localizedrain.api.StormSnapshot;
import com.spectrum255.localizedrain.api.StormSpawnSpec;
import com.spectrum255.localizedrain.api.WeatherMode;
import com.spectrum255.localizedrain.api.WeatherType;
import com.spectrum255.localizedrain.api.WeatherTypes;
import com.spectrum255.localizedrain.api.WindState;
import com.spectrum255.localizedrain.storm.StormManager;
import com.spectrum255.localizedrain.storm.WindField;
import com.spectrum255.localizedrain.weather.BiomePatches;
import com.spectrum255.localizedrain.weather.WeatherBiomeFilter;
import com.spectrum255.localizedrain.weather.WeatherManager;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class LocalizedWeatherCommand {
    private static final int DEFAULT_DURATION = -1;
    /** area 命令每隔多少采样格（quart）检查一次（32 quart = 128 方块）。 */
    private static final int AREA_STEP = 32;
    /** 单次 area 命令最多检查多少个采样点，防止主线程一口气扫太多。 */
    private static final long MAX_AREA_SAMPLES = 512L;
    /** 单次 area 命令最多覆盖多少片群系范围。 */
    private static final long MAX_AREA_PATCHES = 64L;
    /** 单次 area 命令最多触发多少次群系轮廓填充，避免命令卡住主线程。 */
    private static final int MAX_AREA_FILLS = 8;
    private static final Dynamic2CommandExceptionType AREA_TOO_LARGE = new Dynamic2CommandExceptionType(
            (samples, maximum) -> Component.translatable("commands.localizedrain.weather.area.too_large", samples, maximum));
    private static final Dynamic2CommandExceptionType AREA_TOO_MANY_PATCHES = new Dynamic2CommandExceptionType(
            (patches, maximum) -> Component.translatable("commands.localizedrain.weather.area.too_many", patches, maximum));
    private static final DynamicCommandExceptionType AREA_BUDGET = new DynamicCommandExceptionType(
            (limit) -> Component.translatable("commands.localizedrain.weather.area.budget", limit));
    /** `/weather mode` 的非法参数：必须报错，不能像配置读取那样静默回退 PATCHES。 */
    private static final DynamicCommandExceptionType INVALID_MODE = new DynamicCommandExceptionType(
            (name) -> Component.translatable("commands.localizedrain.mode.invalid", name));

    private static final SuggestionProvider<CommandSourceStack> TYPE_SUGGESTIONS =
            (context, builder) -> suggestTypes(context, builder);
    private static final SuggestionProvider<CommandSourceStack> MODE_SUGGESTIONS =
            (context, builder) -> {
                for (WeatherMode mode : WeatherMode.values()) {
                    builder.suggest(mode.getSerializedName());
                }
                return builder.buildFuture();
            };
    private static final SuggestionProvider<CommandSourceStack> PROFILE_SUGGESTIONS =
            (context, builder) -> {
                for (ResourceLocation id : StormProfiles.ids(context.getSource().getLevel())) {
                    builder.suggest(id.toString());
                }
                return builder.buildFuture();
            };

    private LocalizedWeatherCommand() {}

    public static void register(RegisterCommandsEvent event) {
        // 注册表里的天气类型都能用 /weather type <id> 设置；内置三种仍保留短命令
        ArgumentBuilder<CommandSourceStack, ?> typeNode = Commands
                .argument("type", ResourceLocationArgument.id())
                .suggests(TYPE_SUGGESTIONS);
        attachActions(typeNode, context -> ResourceLocationArgument.getId(context, "type"));

        event.getDispatcher().register(Commands.literal("weather")
                .requires(source -> source.hasPermission(2))
                .then(mode("clear", context -> WeatherTypes.CLEAR_ID))
                .then(mode("rain", context -> WeatherTypes.RAIN_ID))
                .then(mode("thunder", context -> WeatherTypes.THUNDER_ID))
                .then(Commands.literal("type").then(typeNode))
                .then(Commands.literal("mode")
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests(MODE_SUGGESTIONS)
                                .executes(LocalizedWeatherCommand::setMode)))
                .then(stormCommand()));
    }

    /** `/weather mode <patches|mobile|vanilla>`：运行期热切换并写回当前存档的 weather.toml。 */
    private static int setMode(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(context, "mode");
        WeatherMode mode = WeatherMode.strictParse(raw)
                .orElseThrow(() -> INVALID_MODE.create(raw));
        ServerLevel level = context.getSource().getLevel();
        ConfigFiles.writeWeatherMode(level, mode);
        WeatherMode.apply(mode);
        // 立刻重推风暴快照，客户端在下一 tick 之前就知道模式已变
        StormManager.refreshAll(level.getServer());
        Localizedrain.LOGGER.info("[localizedrain] weather mode switched to {} via command",
                mode.getSerializedName());
        context.getSource().sendSuccess(() -> Component.translatable(
                "commands.localizedrain.mode.set",
                Component.translatable("localizedrain.configuration.weatherMode." + mode.name())), true);
        return 1;
    }

    /** `/weather storm` 子树：移动模式下手动生成、改写与移除风暴的所有入口。 */
    private static LiteralArgumentBuilder<CommandSourceStack> stormCommand() {
        return Commands.literal("storm")
                .then(Commands.literal("list").executes(LocalizedWeatherCommand::listStorms))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("profile", ResourceLocationArgument.id())
                                .suggests(PROFILE_SUGGESTIONS)
                                .executes(context -> spawnStorm(context, Double.NaN))
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(16.0D, 4096.0D))
                                        .executes(context -> spawnStorm(context, radius(context)))
                                        .then(Commands.literal("band")
                                                .then(Commands.argument("minY", DoubleArgumentType.doubleArg(-256.0D, 512.0D))
                                                        .then(Commands.argument("maxY", DoubleArgumentType.doubleArg(-256.0D, 512.0D))
                                                                .executes(LocalizedWeatherCommand::spawnStormBand)))))))
                .then(Commands.literal("remove")
                        .then(Commands.literal("all").executes(LocalizedWeatherCommand::removeAllStorms))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(LocalizedWeatherCommand::removeStorm)))
                .then(Commands.literal("stage")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("index", IntegerArgumentType.integer())
                                        .executes(LocalizedWeatherCommand::setStormStage))))
                .then(Commands.literal("advance")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(LocalizedWeatherCommand::advanceStorm)))
                .then(Commands.literal("intensity")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("rain", FloatArgumentType.floatArg(0.0F, 1.0F))
                                        .then(Commands.argument("thunder", FloatArgumentType.floatArg(0.0F, 1.0F))
                                                .executes(LocalizedWeatherCommand::setStormIntensity)))))
                .then(Commands.literal("wind")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                        .executes(LocalizedWeatherCommand::setWind))))
                .then(Commands.literal("at")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("profile", ResourceLocationArgument.id())
                                        .suggests(PROFILE_SUGGESTIONS)
                                        .executes(context -> spawnStormAt(context, Double.NaN, DEFAULT_DURATION))
                                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(16.0D, 4096.0D))
                                                .executes(context -> spawnStormAt(context, radius(context), DEFAULT_DURATION))
                                                .then(Commands.argument("duration", TimeArgument.time(1))
                                                        .executes(context -> spawnStormAt(context, radius(context),
                                                                duration(context))))))))
                .then(Commands.literal("area")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .then(Commands.argument("profile", ResourceLocationArgument.id())
                                                .suggests(PROFILE_SUGGESTIONS)
                                                .executes(context -> spawnStormArea(context, DEFAULT_DURATION))
                                                .then(Commands.argument("duration", TimeArgument.time(1))
                                                        .executes(context -> spawnStormArea(context, duration(context))))))))
                .then(Commands.literal("global")
                        .then(Commands.argument("profile", ResourceLocationArgument.id())
                                .suggests(PROFILE_SUGGESTIONS)
                                .executes(context -> spawnStormGlobal(context, Double.NaN, DEFAULT_DURATION))
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(16.0D, 4096.0D))
                                        .executes(context -> spawnStormGlobal(context, radius(context), DEFAULT_DURATION))
                                        .then(Commands.argument("duration", TimeArgument.time(1))
                                                .executes(context -> spawnStormGlobal(context, radius(context),
                                                        duration(context)))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mode(
            String name, Function<CommandContext<CommandSourceStack>, ResourceLocation> type) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(name);
        attachActions(node, type);
        return node;
    }

    /** 把 [duration] / at / area / global 挂到给定节点上；天气类型在命令执行时再解析。 */
    private static void attachActions(ArgumentBuilder<CommandSourceStack, ?> node,
                                      Function<CommandContext<CommandSourceStack>, ResourceLocation> type) {
        node.executes(context -> applyCurrent(context, type.apply(context), DEFAULT_DURATION))
                .then(Commands.argument("duration", TimeArgument.time(1))
                        .executes(context -> applyCurrent(context, type.apply(context), duration(context))))
                .then(Commands.literal("at")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> applyAt(context, type.apply(context), DEFAULT_DURATION))
                                .then(Commands.argument("duration", TimeArgument.time(1))
                                        .executes(context -> applyAt(context, type.apply(context), duration(context))))))
                .then(Commands.literal("area")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(context -> applyArea(context, type.apply(context), DEFAULT_DURATION))
                                        .then(Commands.argument("duration", TimeArgument.time(1))
                                                .executes(context -> applyArea(context, type.apply(context), duration(context)))))))
                .then(Commands.literal("global")
                        .executes(context -> applyGlobal(context, type.apply(context), DEFAULT_DURATION))
                        .then(Commands.argument("duration", TimeArgument.time(1))
                                .executes(context -> applyGlobal(context, type.apply(context), duration(context)))));
    }

    private static int applyCurrent(CommandContext<CommandSourceStack> context, ResourceLocation typeId, int duration) {
        ServerLevel level = context.getSource().getLevel();
        BlockPos pos = BlockPos.containing(context.getSource().getPosition());
        if (WeatherManager.mobileMode()) {
            return applyMobileAt(context, typeId, duration, pos, 0.0D);
        }
        String patch = WeatherManager.patchAt(level, pos);
        if (patch == null) {
            context.getSource().sendFailure(Component.translatable("commands.localizedrain.weather.outside"));
            return 0;
        }
        return apply(context.getSource(), level, Set.of(patch), typeId, duration);
    }

    private static int applyAt(CommandContext<CommandSourceStack> context, ResourceLocation typeId, int duration) {
        ServerLevel level = context.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        if (WeatherManager.mobileMode()) {
            return applyMobileAt(context, typeId, duration, pos, 0.0D);
        }
        String patch = WeatherManager.patchAt(level, pos);
        if (patch == null) {
            context.getSource().sendFailure(Component.translatable("commands.localizedrain.weather.outside"));
            return 0;
        }
        return apply(context.getSource(), level, Set.of(patch), typeId, duration);
    }

    private static int applyArea(CommandContext<CommandSourceStack> context, ResourceLocation typeId, int duration)
            throws CommandSyntaxException {
        ServerLevel level = context.getSource().getLevel();
        BlockPos from = BlockPosArgument.getBlockPos(context, "from");
        BlockPos to = BlockPosArgument.getBlockPos(context, "to");
        if (WeatherManager.mobileMode()) {
            BlockPos center = BlockPos.containing(
                    (from.getX() + to.getX()) / 2.0D,
                    (from.getY() + to.getY()) / 2.0D,
                    (from.getZ() + to.getZ()) / 2.0D);
            double dx = from.getX() - to.getX();
            double dz = from.getZ() - to.getZ();
            double radius = Math.max(16.0D, Math.sqrt(dx * dx + dz * dz) / 2.0D);
            return applyMobileAt(context, typeId, duration, center, radius);
        }
        // 片采样格是 quart（4 方块）；AREA_STEP=32 个 quart = 128 方块，与旧版物理步长一致
        int minCellX = QuartPos.fromBlock(Math.min(from.getX(), to.getX()));
        int maxCellX = QuartPos.fromBlock(Math.max(from.getX(), to.getX()));
        int minCellZ = QuartPos.fromBlock(Math.min(from.getZ(), to.getZ()));
        int maxCellZ = QuartPos.fromBlock(Math.max(from.getZ(), to.getZ()));
        long samples = ((long) (maxCellX - minCellX) / AREA_STEP + 1L)
                * ((long) (maxCellZ - minCellZ) / AREA_STEP + 1L);
        if (samples > MAX_AREA_SAMPLES) {
            throw AREA_TOO_LARGE.create(samples, MAX_AREA_SAMPLES);
        }

        Set<String> patches = new LinkedHashSet<>();
        BiomePatches.FillBudget budget = new BiomePatches.FillBudget(MAX_AREA_FILLS);
        for (int cellX = minCellX; cellX <= maxCellX; cellX += AREA_STEP) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ += AREA_STEP) {
                String patch = BiomePatches.patchAtQuart(level, cellX, cellZ, budget);
                if (patch == null) {
                    throw AREA_BUDGET.create(MAX_AREA_FILLS);
                }
                patches.add(patch);
                if (patches.size() > MAX_AREA_PATCHES) {
                    throw AREA_TOO_MANY_PATCHES.create(patches.size(), MAX_AREA_PATCHES);
                }
            }
        }
        return apply(context.getSource(), level, patches, typeId, duration);
    }

    private static int applyGlobal(CommandContext<CommandSourceStack> context, ResourceLocation typeId, int duration) {
        ServerLevel level = context.getSource().getLevel();
        if (WeatherManager.mobileMode()) {
            return applyMobileGlobal(context, typeId, duration);
        }
        return apply(context.getSource(), level, WeatherManager.allPatches(level), typeId, duration);
    }

    /** 移动模式的单点设置：生成一个覆盖该位置的手动风暴，或清除覆盖该位置的风暴。 */
    private static int applyMobileAt(CommandContext<CommandSourceStack> context, ResourceLocation typeId,
                                     int duration, BlockPos pos, double radius) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int count;
        if (WeatherTypes.CLEAR_ID.equals(typeId)) {
            // clear 按「列」判断覆盖关系：玩家在高空清除地表风暴也应生效；强制晴天也允许清除飘过的风暴
            count = StormManager.clearAtColumn(level, pos, radius);
        } else {
            // 移动模式只有强制晴天会拦下手动设置；强制雨/雷不产生降水，允许手动覆盖
            if (WeatherBiomeFilter.manualBlockedInMobile(level, pos)) {
                source.sendFailure(Component.translatable("commands.localizedrain.weather.biome_excluded", 1));
                return 0;
            }
            int stormRadius = radius > 0.0D ? (int) Math.round(Math.min(radius, 4096.0D)) : 0;
            StormSnapshot snapshot = StormManager.spawnManual(level, pos, typeId, stormRadius, duration);
            count = snapshot == null ? 0 : 1;
        }
        if (count == 0 && !WeatherTypes.CLEAR_ID.equals(typeId)) {
            source.sendFailure(Component.translatable("commands.localizedrain.storm.spawn_failed", typeId.toString()));
            return 0;
        }
        int finalCount = count;
        Component message = mobileSuccessMessage(typeId, finalCount);
        if (radius > 0.0D && !WeatherTypes.CLEAR_ID.equals(typeId)) {
            // 区域生成用圆形风暴近似，矩形四角可能落在圆外
            message = message.copy().append(Component.literal(" ")).append(
                    Component.translatable("commands.localizedrain.storm.area_hint"));
        }
        Component finalMessage = message;
        source.sendSuccess(() -> finalMessage, true);
        return count;
    }

    /** 移动模式的 global：每位玩家生成一个（或清空维度全部风暴），与 /weather rain|clear 的原版语义一致。 */
    private static int applyMobileGlobal(CommandContext<CommandSourceStack> context, ResourceLocation typeId, int duration) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (WeatherTypes.CLEAR_ID.equals(typeId)) {
            int removed = StormManager.clearAllStorms(level);
            source.sendSuccess(() -> mobileSuccessMessage(WeatherTypes.CLEAR_ID, removed), true);
            return removed;
        }
        int players = level.players().size();
        if (players == 0) {
            source.sendSuccess(() -> Component.translatable("commands.localizedrain.storm.no_players"), false);
            return 0;
        }
        int spawned = 0;
        int skipped = 0;
        for (ServerPlayer player : level.players()) {
            if (WeatherBiomeFilter.manualBlockedInMobile(level, player.blockPosition())) {
                skipped++;
                continue;
            }
            if (StormManager.spawnManual(level, player.blockPosition(), typeId, 0, duration) != null) {
                spawned++;
            }
        }
        if (spawned == 0) {
            if (skipped > 0) {
                // 全部候选位置都在强制天气范围内：这不是生成失败
                int locked = skipped;
                source.sendFailure(Component.translatable(
                        "commands.localizedrain.weather.biome_excluded", locked));
            } else {
                source.sendFailure(Component.translatable(
                        "commands.localizedrain.storm.spawn_failed", typeId.toString()));
            }
            return 0;
        }
        int candidates = players - skipped;
        Component message = mobileSuccessMessage(typeId, spawned);
        if (spawned < candidates) {
            message = message.copy().append(Component.literal(" ")).append(
                    Component.translatable("commands.localizedrain.storm.partial_spawned", spawned, candidates));
        }
        if (skipped > 0) {
            message = message.copy().append(Component.literal(" ")).append(
                    Component.translatable("commands.localizedrain.storm.players_skipped", skipped));
        }
        Component finalMessage = message;
        source.sendSuccess(() -> finalMessage, true);
        return spawned;
    }

    private static int apply(CommandSourceStack source, ServerLevel level, Set<String> patches,
                             ResourceLocation typeId, int duration) {
        // 白/黑名单只约束自然循环：指令可以手动覆盖名单；强制晴天是硬锁定，仍会跳过
        List<String> allowed = patches.stream()
                .filter(patch -> WeatherBiomeFilter.forcedType(level, patch) == null)
                .toList();
        int skipped = patches.size() - allowed.size();
        if (allowed.isEmpty()) {
            source.sendFailure(Component.translatable("commands.localizedrain.weather.biome_excluded", patches.size()));
            return 0;
        }
        WeatherManager.setWeather(level, allowed, typeId, duration);
        int count = allowed.size();
        Component message = successMessage(typeId, count);
        if (skipped > 0) {
            message = message.copy().append(Component.literal(" ")).append(
                    Component.translatable("commands.localizedrain.weather.biome_skipped", skipped));
        }
        Component finalMessage = message;
        source.sendSuccess(() -> finalMessage, true);
        return count;
    }

    private static Component successMessage(ResourceLocation typeId, int count) {
        if (WeatherTypes.CLEAR_ID.equals(typeId)) {
            return Component.translatable("commands.localizedrain.weather.set.clear", count);
        }
        if (WeatherTypes.RAIN_ID.equals(typeId)) {
            return Component.translatable("commands.localizedrain.weather.set.rain", count);
        }
        if (WeatherTypes.THUNDER_ID.equals(typeId)) {
            return Component.translatable("commands.localizedrain.weather.set.thunder", count);
        }
        return Component.translatable("commands.localizedrain.weather.set.type", typeId.toString(), count);
    }

    /** 移动模式的成功文案：计数单位是风暴，不是片。 */
    private static Component mobileSuccessMessage(ResourceLocation typeId, int count) {
        if (WeatherTypes.CLEAR_ID.equals(typeId)) {
            return Component.translatable("commands.localizedrain.storm.set.clear", count);
        }
        if (WeatherTypes.RAIN_ID.equals(typeId)) {
            return Component.translatable("commands.localizedrain.storm.set.rain", count);
        }
        if (WeatherTypes.THUNDER_ID.equals(typeId)) {
            return Component.translatable("commands.localizedrain.storm.set.thunder", count);
        }
        return Component.translatable("commands.localizedrain.storm.set.type", count, typeId.toString());
    }

    private static int duration(CommandContext<CommandSourceStack> context) {
        return IntegerArgumentType.getInteger(context, "duration");
    }

    private static int listStorms(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        List<StormSnapshot> storms = StormManager.snapshots(level);
        if (storms.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("commands.localizedrain.storm.none"), false);
            return 0;
        }
        WindState wind = StormManager.wind(level);
        for (StormSnapshot snapshot : storms) {
            context.getSource().sendSuccess(() -> Component.literal(String.format(
                    "%s profile=%s pos=(%.1f, %.1f) radius=%.1f y=[%.1f,%.1f] stage=%d/%d rain=%.2f thunder=%.2f manual=%s dying=%s windSpeed=%.3f",
                    snapshot.id(), snapshot.profileId(), snapshot.x(), snapshot.z(),
                    snapshot.radius(), snapshot.minY(), snapshot.maxY(),
                    snapshot.stageIndex() + 1, snapshot.stageCount(),
                    snapshot.rainIntensity(), snapshot.thunderIntensity(),
                    snapshot.manual(), snapshot.dying(), wind.speed())), false);
        }
        return storms.size();
    }

    private static double radius(CommandContext<CommandSourceStack> context) {
        return DoubleArgumentType.getDouble(context, "radius");
    }

    private static ResourceLocation profileId(CommandContext<CommandSourceStack> context) {
        return ResourceLocationArgument.getId(context, "profile");
    }

    /** 解析 id 参数；格式非法时发送 bad_id 并返回 null。 */
    private static UUID parseId(CommandContext<CommandSourceStack> context, String argument) {
        try {
            return UUID.fromString(argument);
        } catch (IllegalArgumentException invalid) {
            context.getSource().sendFailure(Component.translatable("commands.localizedrain.storm.bad_id", argument));
            return null;
        }
    }

    /** 档案不存在 / 生成被事件取消时发送失败提示，成功返回快照。 */
    private static StormSnapshot spawnChecked(CommandContext<CommandSourceStack> context,
                                              ResourceLocation profileId, StormSpawnSpec.Builder builder) {
        ServerLevel level = context.getSource().getLevel();
        StormProfile profile = StormManager.profile(level, profileId);
        if (profile == null) {
            context.getSource().sendFailure(Component.translatable(
                    "commands.localizedrain.storm.unknown_profile", profileId.toString()));
            return null;
        }
        boolean surfaceOnly = !builder.hasVerticalBand()
                && profile.vertical() == StormProfile.VerticalMode.SURFACE;
        if (surfaceOnly && !StormManager.allowsManualStorms(level)) {
            // BAND/COLUMN 洞穴风暴在无天光维度仍然允许，只有 SURFACE 雨雪不可见
            context.getSource().sendFailure(Component.translatable(
                    "commands.localizedrain.storm.no_skylight_blocked"));
            return null;
        }
        if (!StormManager.allowsManualStorms(level)
                && profile.hasThunderStage(level)) {
            // 非 SURFACE 的雷暴在无天光/天花板维度永不落雷，直接拒绝
            context.getSource().sendFailure(Component.translatable(
                    "commands.localizedrain.storm.thunder_unavailable"));
            return null;
        }
        StormSnapshot snapshot = StormManager.spawn(level, builder.build());
        if (snapshot == null) {
            context.getSource().sendFailure(Component.translatable(
                    "commands.localizedrain.storm.spawn_failed", profileId.toString()));
        }
        return snapshot;
    }

    /** 生成成功后统一回复 id、档案与半径；失败返回 0。 */
    private static int reportSpawn(CommandContext<CommandSourceStack> context, ResourceLocation profileId,
                                   StormSpawnSpec.Builder builder, String messageKey) {
        StormSnapshot snapshot = spawnChecked(context, profileId, builder);
        if (snapshot == null) {
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable(messageKey,
                snapshot.id().toString(), profileId.toString(), (int) snapshot.radius()), true);
        return 1;
    }

    private static int spawnStorm(CommandContext<CommandSourceStack> context, double radius) {
        ResourceLocation profileId = profileId(context);
        StormSpawnSpec.Builder builder = StormSpawnSpec.builder(profileId, context.getSource().getPosition())
                .manual(true);
        if (!Double.isNaN(radius)) {
            builder.radius(radius);
        }
        return reportSpawn(context, profileId, builder, "commands.localizedrain.storm.spawned");
    }

    /** `spawn <profile> <radius> band <minY> <maxY>`：带竖直区间的洞穴/高空气暴。 */
    private static int spawnStormBand(CommandContext<CommandSourceStack> context) {
        ResourceLocation profileId = profileId(context);
        double minY = DoubleArgumentType.getDouble(context, "minY");
        double maxY = DoubleArgumentType.getDouble(context, "maxY");
        StormSpawnSpec.Builder builder = StormSpawnSpec.builder(profileId, context.getSource().getPosition())
                .radius(radius(context))
                .verticalBand(minY, maxY)
                .manual(true);
        return reportSpawn(context, profileId, builder, "commands.localizedrain.storm.spawned");
    }

    private static int spawnStormAt(CommandContext<CommandSourceStack> context, double radius, int duration) {
        ResourceLocation profileId = profileId(context);
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        StormSpawnSpec.Builder builder = StormSpawnSpec.builder(profileId, Vec3.atCenterOf(pos)).manual(true);
        if (!Double.isNaN(radius)) {
            builder.radius(radius);
        }
        if (duration > 0) {
            builder.lifetime(duration);
        }
        return reportSpawn(context, profileId, builder, "commands.localizedrain.storm.at_spawned");
    }

    /** `area <from> <to> <profile> [duration]`：中心取两角中点，半径覆盖整个矩形。 */
    private static int spawnStormArea(CommandContext<CommandSourceStack> context, int duration) {
        ResourceLocation profileId = profileId(context);
        BlockPos from = BlockPosArgument.getBlockPos(context, "from");
        BlockPos to = BlockPosArgument.getBlockPos(context, "to");
        Vec3 center = new Vec3(
                (from.getX() + to.getX()) / 2.0D + 0.5D,
                Math.max(from.getY(), to.getY()) + 0.5D,
                (from.getZ() + to.getZ()) / 2.0D + 0.5D);
        double horizontal = Math.hypot((double) from.getX() - to.getX(), (double) from.getZ() - to.getZ());
        // 区域风暴半径 = 矩形半对角线 + 64 格缓冲
        double radius = horizontal / 2.0D + 64.0D;
        StormSpawnSpec.Builder builder = StormSpawnSpec.builder(profileId, center)
                .radius(radius)
                .manual(true);
        if (duration > 0) {
            builder.lifetime(duration);
        }
        return reportSpawn(context, profileId, builder, "commands.localizedrain.storm.area_spawned");
    }

    /** `global <profile> [radius] [duration]`：为维度内每位玩家各生成一个手动风暴。 */
    private static int spawnStormGlobal(CommandContext<CommandSourceStack> context, double radius, int duration) {
        ServerLevel level = context.getSource().getLevel();
        ResourceLocation profileId = profileId(context);
        StormProfile profile = StormManager.profile(level, profileId);
        if (profile == null) {
            context.getSource().sendFailure(Component.translatable(
                    "commands.localizedrain.storm.unknown_profile", profileId.toString()));
            return 0;
        }
        if (profile.vertical() == StormProfile.VerticalMode.SURFACE
                && !StormManager.allowsManualStorms(level)) {
            // BAND/COLUMN 洞穴风暴在无天光维度仍然允许，只有 SURFACE 雨雪不可见
            context.getSource().sendFailure(Component.translatable(
                    "commands.localizedrain.storm.no_skylight_blocked"));
            return 0;
        }
        if (!StormManager.allowsManualStorms(level)
                && profile.hasThunderStage(level)) {
            // 非 SURFACE 的雷暴在无天光/天花板维度永不落雷，直接拒绝
            context.getSource().sendFailure(Component.translatable(
                    "commands.localizedrain.storm.thunder_unavailable"));
            return 0;
        }
        // 不指定半径时交给档案自身的 radius 区间（例如 cave_rain 的 48–96）
        double actualRadius = Double.isNaN(radius) ? 0.0D : radius;
        int cap = WindField.intConfig(Config.MAX_MANUAL_STORMS_PER_DIMENSION, 16);
        int existing = StormManager.manualStormCount(level);
        int players = level.players().size();
        if (existing + players > cap) {
            context.getSource().sendFailure(Component.translatable(
                    "commands.localizedrain.storm.manual_limit", existing, cap));
            return 0;
        }
        int spawned = 0;
        double firstRadius = -1.0D;
        for (ServerPlayer player : level.players()) {
            StormSpawnSpec.Builder builder = StormSpawnSpec.builder(profileId, Vec3.atCenterOf(player.blockPosition()))
                    .manual(true);
            if (actualRadius > 0.0D) {
                builder.radius(actualRadius);
            }
            if (duration > 0) {
                builder.lifetime(duration);
            }
            StormSnapshot snapshot = StormManager.spawn(level, builder.build());
            if (snapshot != null) {
                spawned++;
                if (firstRadius < 0.0D) {
                    firstRadius = snapshot.radius();
                }
            }
        }
        if (spawned == 0) {
            context.getSource().sendFailure(Component.translatable(
                    "commands.localizedrain.storm.spawn_failed", profileId.toString()));
            return 0;
        }
        int count = spawned;
        int shownRadius = firstRadius < 0.0D ? 0 : (int) Math.round(firstRadius);
        context.getSource().sendSuccess(() -> Component.translatable("commands.localizedrain.storm.global_spawned",
                count, profileId.toString(), shownRadius), true);
        return count;
    }

    private static int setStormStage(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        String argument = StringArgumentType.getString(context, "id");
        UUID id = parseId(context, argument);
        if (id == null) {
            return 0;
        }
        int index = IntegerArgumentType.getInteger(context, "index");
        StormSnapshot snapshot = StormManager.snapshotById(level, id);
        if (snapshot == null) {
            context.getSource().sendFailure(Component.translatable("commands.localizedrain.storm.missing", argument));
            return 0;
        }
        if (index < 0 || index >= snapshot.stageCount()) {
            context.getSource().sendFailure(Component.translatable("commands.localizedrain.storm.bad_index",
                    index, snapshot.stageCount() - 1));
            return 0;
        }
        if (!StormManager.setStage(level, id, index)) {
            context.getSource().sendFailure(Component.translatable("commands.localizedrain.storm.stage_set_failed", argument));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable("commands.localizedrain.storm.stage_set",
                argument, index + 1, snapshot.stageCount()), true);
        return 1;
    }

    private static int advanceStorm(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        String argument = StringArgumentType.getString(context, "id");
        UUID id = parseId(context, argument);
        if (id == null) {
            return 0;
        }
        if (StormManager.snapshotById(level, id) == null) {
            context.getSource().sendFailure(Component.translatable("commands.localizedrain.storm.missing", argument));
            return 0;
        }
        if (!StormManager.advanceStage(level, id)) {
            context.getSource().sendFailure(Component.translatable("commands.localizedrain.storm.advance_failed", argument));
            return 0;
        }
        StormSnapshot after = StormManager.snapshotById(level, id);
        int stage = after == null ? 0 : after.stageIndex() + 1;
        int total = after == null ? 0 : after.stageCount();
        context.getSource().sendSuccess(() -> Component.translatable("commands.localizedrain.storm.advanced",
                argument, stage, total), true);
        return 1;
    }

    private static int setStormIntensity(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        String argument = StringArgumentType.getString(context, "id");
        UUID id = parseId(context, argument);
        if (id == null) {
            return 0;
        }
        float rain = FloatArgumentType.getFloat(context, "rain");
        float thunder = FloatArgumentType.getFloat(context, "thunder");
        if (!StormManager.setIntensity(level, id, rain, thunder)) {
            context.getSource().sendFailure(Component.translatable("commands.localizedrain.storm.intensity_failed", argument));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable("commands.localizedrain.storm.intensity_set",
                argument, rain, thunder), true);
        return 1;
    }

    private static int setWind(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        double x = DoubleArgumentType.getDouble(context, "x");
        double z = DoubleArgumentType.getDouble(context, "z");
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            context.getSource().sendFailure(Component.translatable("commands.localizedrain.storm.wind_failed"));
            return 0;
        }
        StormManager.setWind(level, x, z);
        context.getSource().sendSuccess(() -> Component.translatable("commands.localizedrain.storm.wind_set", x, z), true);
        return 1;
    }

    private static int removeStorm(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        String argument = StringArgumentType.getString(context, "id");
        UUID id = parseId(context, argument);
        if (id == null) {
            return 0;
        }
        boolean removed = StormManager.remove(level, id);
        context.getSource().sendSuccess(() -> Component.translatable(removed
                ? "commands.localizedrain.storm.removed"
                : "commands.localizedrain.storm.missing", argument), true);
        return removed ? 1 : 0;
    }

    private static int removeAllStorms(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        int total = StormManager.removeWhere(level, snapshot -> true);
        context.getSource().sendSuccess(() -> Component.translatable("commands.localizedrain.storm.removed_all", total), true);
        return total;
    }

    private static CompletableFuture<Suggestions> suggestTypes(CommandContext<CommandSourceStack> context,
                                                               SuggestionsBuilder builder) {
        ServerLevel level = context.getSource().getLevel();
        Registry<WeatherType> registry = level.registryAccess().registry(WeatherTypes.REGISTRY_KEY).orElse(null);
        if (registry != null) {
            for (ResourceLocation id : registry.keySet()) {
                builder.suggest(id.toString());
            }
        }
        return builder.buildFuture();
    }
}
