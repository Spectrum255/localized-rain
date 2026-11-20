package com.spectrum255.localizedrain.storm;

import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.api.StormProfile;
import com.spectrum255.localizedrain.api.StormSnapshot;
import com.spectrum255.localizedrain.api.WeatherType;
import com.spectrum255.localizedrain.api.WeatherTypes;
import com.spectrum255.localizedrain.api.event.StormMergeEvent;
import com.spectrum255.localizedrain.api.event.StormRemoveEvent;
import com.spectrum255.localizedrain.api.event.StormSplitEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.NeoForge;

/**
 * M2：同类型风暴的合并与超大风暴的分裂。
 *
 * <p>每 {@value #CHECK_INTERVAL_TICKS} tick 检查一次（与 {@code StormManager.tick} 同线程），
 * 合并/分裂都发可取消事件；一次检查最多执行一次合并、一次分裂，避免风暴数量突变。
 * 仅在 {@link WeatherMode#MOBILE} 下被调用（入口由 StormManager 把关）。</p>
 */
final class StormMerger {
    private static final int CHECK_INTERVAL_TICKS = 100;
    /** 每个维度上一次合并/分裂的 gameTime，用于冷却。 */
    private static final Map<ServerLevel, Long> LAST_MERGE = new WeakHashMap<>();
    private static final Map<ServerLevel, Long> LAST_SPLIT = new WeakHashMap<>();

    private StormMerger() {
    }

    /** @return 是否有风暴被合并或分裂（调用方据此立即广播） */
    static boolean tick(ServerLevel level) {
        if (level.getGameTime() % CHECK_INTERVAL_TICKS != 0L) {
            return false;
        }
        boolean changed = false;
        if (Config.STORM_MERGE_ENABLED.get()) {
            changed |= mergePass(level);
        }
        if (Config.STORM_SPLIT_ENABLED.get()) {
            changed |= splitPass(level);
        }
        return changed;
    }

    // ---- 合并 ----

    /** 一次检查最多合并一对；保留更老（同龄取更大）的风暴。 */
    private static boolean mergePass(ServerLevel level) {
        long now = level.getGameTime();
        long cooldown = WindField.intConfig(Config.STORM_MERGE_COOLDOWN_TICKS, 200);
        if (now - LAST_MERGE.getOrDefault(level, Long.MIN_VALUE / 2) < cooldown) {
            return false;
        }
        List<StormObject> storms = StormManager.data(level).storms();
        if (storms.size() < 2) {
            return false;
        }
        double factor = WindField.doubleConfig(Config.STORM_MERGE_RADIUS_FACTOR, 0.6D);
        boolean allowManual = Config.STORM_MERGE_MANUAL.get();
        for (int i = 0; i < storms.size(); i++) {
            StormObject first = storms.get(i);
            if (first.dying() || (!allowManual && first.manual()) || now < first.mergeImmuneUntil) {
                continue;
            }
            StormProfile firstProfile = first.resolveProfile(level);
            if (firstProfile == null) {
                continue;
            }
            for (int j = i + 1; j < storms.size(); j++) {
                StormObject second = storms.get(j);
                if (second.dying() || (!allowManual && second.manual()) || now < second.mergeImmuneUntil) {
                    continue;
                }
                // 自然风暴只与自然风暴合并；手动风暴只与手动风暴合并（后者需显式开启）
                if (first.manual() != second.manual()) {
                    continue;
                }
                StormProfile secondProfile = second.resolveProfile(level);
                if (secondProfile == null) {
                    continue;
                }
                ResourceLocation firstType = first.weatherType(firstProfile);
                ResourceLocation secondType = second.weatherType(secondProfile);
                if (!firstType.equals(secondType)
                        && !StormManager.typesCompatible(level, firstType, secondType)) {
                    continue;
                }
                double dx = first.x - second.x;
                double dz = first.z - second.z;
                double reach = (first.radiusAt() + second.radiusAt()) * factor;
                if (dx * dx + dz * dz > reach * reach) {
                    continue;
                }
                StormObject survivor = first.spawnGameTime <= second.spawnGameTime ? first : second;
                StormObject absorbed = survivor == first ? second : first;
                StormProfile survivorProfile = survivor == first ? firstProfile : secondProfile;
                StormProfile absorbedProfile = survivor == first ? secondProfile : firstProfile;
                StormSnapshot survivorSnapshot = survivor.snapshot(survivorProfile);
                StormSnapshot absorbedSnapshot = absorbed.snapshot(absorbedProfile);
                if (survivorSnapshot == null || absorbedSnapshot == null) {
                    continue;
                }
                if (NeoForge.EVENT_BUS.post(new StormMergeEvent(level, survivorSnapshot, List.of(absorbedSnapshot)))
                        .isCanceled()) {
                    continue;
                }
                merge(level, storms, survivor, survivorProfile, first, firstProfile, second, secondProfile, absorbed);
                LAST_MERGE.put(level, now);
                NeoForge.EVENT_BUS.post(new StormRemoveEvent(level, absorbed.id(), StormRemoveEvent.Reason.MERGED));
                return true;
            }
        }
        return false;
    }

    /** 面积加权位置/雨量/雷量/温度/水分，半径按平方和开方，阶段取较靠后者。 */
    private static void merge(ServerLevel level, List<StormObject> storms, StormObject survivor,
                              StormProfile survivorProfile, StormObject a, StormProfile profileA,
                              StormObject b, StormProfile profileB, StormObject absorbed) {
        double weightA = a.radiusAt() * a.radiusAt();
        double weightB = b.radiusAt() * b.radiusAt();
        double total = Math.max(1.0E-4D, weightA + weightB);
        survivor.x = (a.x * weightA + b.x * weightB) / total;
        survivor.z = (a.z * weightA + b.z * weightB) / total;
        survivor.radius = Mth.clamp(Math.sqrt(a.radiusAt() * a.radiusAt() + b.radiusAt() * b.radiusAt()),
                survivorProfile.radius().min(), survivorProfile.radius().max() * 1.5D);
        survivor.radiusExplicit = true;
        survivor.rainOverride = Mth.clamp(
                (float) ((a.baseRain() * weightA + b.baseRain() * weightB) / total), 0.0F, 1.0F);
        survivor.thunderOverride = Mth.clamp(
                (float) ((a.baseThunder() * weightA + b.baseThunder() * weightB) / total), 0.0F, 1.0F);
        survivor.temperature = (float) ((a.temperature * weightA + b.temperature * weightB) / total);
        survivor.moisture = (float) ((a.moisture * weightA + b.moisture * weightB) / total);
        // 合并平均值本身有界，但仍走共用钳制：任一侧若来自旧存档/外部写入，这里能兜住
        survivor.velocityX = StormObject.clampVelocity((a.velocityX * weightA + b.velocityX * weightB) / total);
        survivor.velocityZ = StormObject.clampVelocity((a.velocityZ * weightA + b.velocityZ * weightB) / total);
        survivor.pinnedVelocity = a.pinnedVelocity && b.pinnedVelocity;
        survivor.stageIndex = Math.max(a.stageIndex, b.stageIndex);
        survivor.stageScale = survivorProfile.stage(survivor.stageIndex).radiusScale();
        storms.remove(absorbed);
        StormManager.data(level).setDirty();
    }

    // ---- 分裂 ----

    /** 超大风暴按概率分出一个子风暴；父风暴不删除，只缩小并降低强度。 */
    private static boolean splitPass(ServerLevel level) {
        long now = level.getGameTime();
        long cooldown = WindField.intConfig(Config.STORM_SPLIT_COOLDOWN_TICKS, 400);
        if (now - LAST_SPLIT.getOrDefault(level, Long.MIN_VALUE / 2) < cooldown) {
            return false;
        }
        List<StormObject> storms = StormManager.data(level).storms();
        int cap = WindField.intConfig(Config.MAX_STORMS_PER_DIMENSION, 8);
        if (storms.size() >= cap) {
            return false;
        }
        double factor = WindField.doubleConfig(Config.STORM_SPLIT_RADIUS_FACTOR, 1.4D);
        float chance = (float) WindField.doubleConfig(Config.STORM_SPLIT_CHANCE, 0.25D);
        int immunityTicks = WindField.intConfig(Config.STORM_MERGE_IMMUNITY_TICKS, 1200);
        for (StormObject storm : storms) {
            if (storm.dying() || storm.manual()) {
                continue;
            }
            StormProfile profile = storm.resolveProfile(level);
            if (profile == null || storm.radiusAt() <= profile.radius().max() * factor) {
                continue;
            }
            if (level.getRandom().nextFloat() >= chance) {
                continue;
            }
            StormSnapshot parentSnapshot = storm.snapshot(profile);
            if (parentSnapshot == null) {
                continue;
            }
            StormObject child = createChild(level, storm, profile);
            StormSnapshot childSnapshot = child.snapshot(profile);
            if (childSnapshot == null) {
                continue;
            }
            if (NeoForge.EVENT_BUS.post(new StormSplitEvent(level, parentSnapshot, List.of(childSnapshot)))
                    .isCanceled()) {
                continue;
            }
            // 父风暴收缩并降强：子风暴是「分出去的一部分」，父风暴继续存在
            storm.radius *= 0.62D;
            storm.radiusExplicit = true;
            storm.rainOverride = Mth.clamp(storm.baseRain() * 0.9F, 0.0F, 1.0F);
            storm.thunderOverride = Mth.clamp(storm.baseThunder() * 0.9F, 0.0F, 1.0F);
            // 分裂瞬间父子只隔 0.55R，合并阈值却是 (r1+r2)*0.6，不设免疫下一次检查就会合回去
            if (immunityTicks > 0) {
                long immuneUntil = now + immunityTicks;
                storm.mergeImmuneUntil = immuneUntil;
                child.mergeImmuneUntil = immuneUntil;
            }
            storms.add(child);
            LAST_SPLIT.put(level, now);
            StormManager.data(level).setDirty();
            return true;
        }
        return false;
    }

    /** 子风暴落在垂直于父风暴速度的一侧，速度带一点横向分量，避免立刻与父风暴重合。 */
    private static StormObject createChild(ServerLevel level, StormObject parent, StormProfile profile) {
        double speed = Math.sqrt(parent.velocityX * parent.velocityX + parent.velocityZ * parent.velocityZ);
        double normalX;
        double normalZ;
        if (speed > 1.0E-4D) {
            normalX = -parent.velocityZ / speed;
            normalZ = parent.velocityX / speed;
        } else {
            normalX = 1.0D;
            normalZ = 0.0D;
        }
        double sign = level.getRandom().nextBoolean() ? 1.0D : -1.0D;
        double offset = parent.radiusAt() * 0.55D * sign;
        StormObject child = StormObject.create(UUID.randomUUID(), parent.profileId,
                parent.x + normalX * offset, parent.z + normalZ * offset,
                Math.max(16.0D, parent.radiusAt() * 0.55D),
                parent.minY, parent.maxY, parent.verticalMode, false,
                level.getRandom().nextLong(), level.getGameTime());
        StormProfile.Stage stage = profile.stage(parent.stageIndex);
        child.stageIndex = parent.stageIndex;
        child.stageScale = stage.radiusScale();
        child.stageTicksTotal = child.stageTicksLeft = StormObject.pickTicks(child.seed, stage);
        child.radiusExplicit = true;
        child.typeOverride = parent.typeOverride;
        child.capabilityType = parent.capabilityType;
        WeatherType childType = WeatherTypes.resolve(level, child.weatherType(profile));
        child.applyCapabilities(childType);
        double boost = speed * 0.4D * sign;
        // 父速度本身 ≤10，但叠加横风 boost 后可能到 14：子风暴同样要受共用上限约束
        child.velocityX = StormObject.clampVelocity(parent.velocityX + normalX * boost);
        child.velocityZ = StormObject.clampVelocity(parent.velocityZ + normalZ * boost);
        child.pinnedVelocity = parent.pinnedVelocity;
        // tick 每 tick 会用阶段插值覆写强度，所以这里用 override 承载初始强度
        child.rainOverride = Mth.clamp(parent.baseRain() * 0.85F, 0.0F, 1.0F);
        child.thunderOverride = Mth.clamp(parent.baseThunder() * 0.85F, 0.0F, 1.0F);
        return child;
    }
}
