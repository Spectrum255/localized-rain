package com.spectrum255.localizedrain;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.spectrum255.localizedrain.weather.BiomePatches;
import com.spectrum255.localizedrain.weather.WeatherData.WeatherType;
import com.spectrum255.localizedrain.weather.WeatherManager;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class LocalizedWeatherCommand {
    private static final int DEFAULT_DURATION = -1;
    /** area 命令每隔多少采样格检查一次（8 区块 = 128 方块）。 */
    private static final int AREA_STEP = 8;
    /** 单次 area 命令最多检查多少个采样点，防止主线程一口气扫太多。 */
    private static final long MAX_AREA_SAMPLES = 512L;
    /** 单次 area 命令最多覆盖多少片群系范围。 */
    private static final long MAX_AREA_PATCHES = 64L;
    private static final Dynamic2CommandExceptionType AREA_TOO_LARGE = new Dynamic2CommandExceptionType(
            (samples, maximum) -> Component.translatable("commands.localizedrain.weather.area.too_large", samples, maximum));
    private static final Dynamic2CommandExceptionType AREA_TOO_MANY_PATCHES = new Dynamic2CommandExceptionType(
            (patches, maximum) -> Component.translatable("commands.localizedrain.weather.area.too_many", patches, maximum));

    private LocalizedWeatherCommand() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("weather")
                .requires(source -> source.hasPermission(2))
                .then(mode("clear", WeatherType.CLEAR))
                .then(mode("rain", WeatherType.RAIN))
                .then(mode("thunder", WeatherType.THUNDER)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mode(String name, WeatherType weather) {
        return Commands.literal(name)
                .executes(context -> applyCurrent(context, weather, DEFAULT_DURATION))
                .then(Commands.argument("duration", TimeArgument.time(1))
                        .executes(context -> applyCurrent(context, weather, duration(context))))
                .then(Commands.literal("at")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> applyAt(context, weather, DEFAULT_DURATION))
                                .then(Commands.argument("duration", TimeArgument.time(1))
                                        .executes(context -> applyAt(context, weather, duration(context))))))
                .then(Commands.literal("area")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(context -> applyArea(context, weather, DEFAULT_DURATION))
                                        .then(Commands.argument("duration", TimeArgument.time(1))
                                                .executes(context -> applyArea(context, weather, duration(context)))))))
                .then(Commands.literal("global")
                        .executes(context -> applyGlobal(context, weather, DEFAULT_DURATION))
                        .then(Commands.argument("duration", TimeArgument.time(1))
                                .executes(context -> applyGlobal(context, weather, duration(context)))));
    }

    private static int applyCurrent(CommandContext<CommandSourceStack> context, WeatherType weather, int duration) {
        ServerLevel level = context.getSource().getLevel();
        BlockPos pos = BlockPos.containing(context.getSource().getPosition());
        return apply(context.getSource(), level, Set.of(WeatherManager.patchAt(level, pos)), weather, duration);
    }

    private static int applyAt(CommandContext<CommandSourceStack> context, WeatherType weather, int duration) {
        ServerLevel level = context.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        return apply(context.getSource(), level, Set.of(WeatherManager.patchAt(level, pos)), weather, duration);
    }

    private static int applyArea(CommandContext<CommandSourceStack> context, WeatherType weather, int duration)
            throws CommandSyntaxException {
        ServerLevel level = context.getSource().getLevel();
        BlockPos from = BlockPosArgument.getBlockPos(context, "from");
        BlockPos to = BlockPosArgument.getBlockPos(context, "to");
        int minCellX = SectionPos.blockToSectionCoord(Math.min(from.getX(), to.getX()));
        int maxCellX = SectionPos.blockToSectionCoord(Math.max(from.getX(), to.getX()));
        int minCellZ = SectionPos.blockToSectionCoord(Math.min(from.getZ(), to.getZ()));
        int maxCellZ = SectionPos.blockToSectionCoord(Math.max(from.getZ(), to.getZ()));
        long samples = ((long) (maxCellX - minCellX) / AREA_STEP + 1L)
                * ((long) (maxCellZ - minCellZ) / AREA_STEP + 1L);
        if (samples > MAX_AREA_SAMPLES) {
            throw AREA_TOO_LARGE.create(samples, MAX_AREA_SAMPLES);
        }

        Set<String> patches = new LinkedHashSet<>();
        for (int cellX = minCellX; cellX <= maxCellX; cellX += AREA_STEP) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ += AREA_STEP) {
                patches.add(BiomePatches.patchAtCell(level, cellX, cellZ));
                if (patches.size() > MAX_AREA_PATCHES) {
                    throw AREA_TOO_MANY_PATCHES.create(patches.size(), MAX_AREA_PATCHES);
                }
            }
        }
        return apply(context.getSource(), level, patches, weather, duration);
    }

    private static int applyGlobal(CommandContext<CommandSourceStack> context, WeatherType weather, int duration) {
        ServerLevel level = context.getSource().getLevel();
        return apply(context.getSource(), level, WeatherManager.allPatches(level), weather, duration);
    }

    private static int apply(CommandSourceStack source, ServerLevel level, Set<String> patches,
                             WeatherType weather, int duration) {
        if (!level.dimensionType().hasSkyLight()) {
            source.sendFailure(Component.translatable("commands.localizedrain.weather.unsupported_dimension"));
            return 0;
        }
        WeatherManager.setWeather(level, patches, weather, duration);
        int count = patches.size();
        source.sendSuccess(() -> Component.translatable(translationKey(weather), count), true);
        return count;
    }

    private static String translationKey(WeatherType weather) {
        return switch (weather) {
            case CLEAR -> "commands.localizedrain.weather.set.clear";
            case RAIN -> "commands.localizedrain.weather.set.rain";
            case THUNDER -> "commands.localizedrain.weather.set.thunder";
        };
    }

    private static int duration(CommandContext<CommandSourceStack> context) {
        return IntegerArgumentType.getInteger(context, "duration");
    }
}
