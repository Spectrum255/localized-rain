package com.spectrum255.localizedrain;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.spectrum255.localizedrain.api.WeatherType;
import com.spectrum255.localizedrain.api.WeatherTypes;
import com.spectrum255.localizedrain.weather.BiomePatches;
import com.spectrum255.localizedrain.weather.WeatherBiomeFilter;
import com.spectrum255.localizedrain.weather.WeatherManager;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    private static final SuggestionProvider<CommandSourceStack> TYPE_SUGGESTIONS =
            (context, builder) -> suggestTypes(context, builder);

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
                .then(Commands.literal("type").then(typeNode)));
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
        return apply(context.getSource(), level, WeatherManager.allPatches(level), typeId, duration);
    }

    private static int apply(CommandSourceStack source, ServerLevel level, Set<String> patches,
                             ResourceLocation typeId, int duration) {
        // 名单排除或被强制天气的群系不接受手动变更；全部被跳过时明确报错，部分跳过时提示数量
        List<String> allowed = patches.stream()
                .filter(patch -> WeatherBiomeFilter.forcedWeather(level, patch) == null)
                .filter(patch -> WeatherBiomeFilter.allows(level, patch))
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

    private static int duration(CommandContext<CommandSourceStack> context) {
        return IntegerArgumentType.getInteger(context, "duration");
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
