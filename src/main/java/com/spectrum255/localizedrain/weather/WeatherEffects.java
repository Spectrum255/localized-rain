package com.spectrum255.localizedrain.weather;

import com.spectrum255.localizedrain.Localizedrain;
import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.api.WeatherType;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 天气类型 {@code effects}（移速倍率、周期伤害）的统一执行器。
 *
 * <p>片模式由 {@link WeatherManager} 按所在片的天气状态调用，移动模式由
 * {@code StormManager} 按覆盖玩家的主导风暴调用；两边共用同一份「已应用倍率 / 伤害计时」状态，
 * 所以模式切换时不会残留或重复叠加。</p>
 */
public final class WeatherEffects {
    /** 每玩家当前生效的移动速度倍率，避免每 tick 反复增删属性修饰符。 */
    private static final Map<UUID, Float> APPLIED_SPEED = new HashMap<>();
    /** 每玩家的天气伤害计时（tick）。 */
    private static final Map<UUID, Integer> DAMAGE_TICKS = new HashMap<>();
    private static final ResourceLocation SPEED_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "weather_speed");
    /** 单次点燃最多 5 分钟：数据包写极大 seconds 时 Math.round 会饱和成 Integer.MAX_VALUE。 */
    private static final int MAX_IGNITE_TICKS = 20 * 60 * 5;
    /** 原版冰冻伤害阈值 140 tick；超过它继续累加只会让天气结束后残留更久。 */
    private static final int MAX_FROZEN_TICKS = 140;
    /** 每玩家最多保留的「不同属性 × 运算」修饰符数量，防止恶意数据包写满 AttributeMap。 */
    private static final int MAX_ATTRIBUTE_MODIFIERS = 16;
    /** 已告警过的未知属性 id，避免每个玩家每 tick 刷屏。 */
    private static final Set<ResourceLocation> WARNED_ATTRIBUTES = new HashSet<>();
    /** 每玩家当前写入的属性修饰符（属性 + 运算 → 合成后的数值），用于差量更新与清理。 */
    private static final Map<UUID, Map<AttributeKey, Double>> APPLIED_ATTRIBUTES = new HashMap<>();
    private static final ResourceLocation MOVEMENT_SPEED_ID = Attributes.MOVEMENT_SPEED.unwrapKey()
            .map(ResourceKey::location)
            .orElseGet(() -> ResourceLocation.withDefaultNamespace("generic.movement_speed"));

    private record AttributeKey(ResourceLocation attribute, WeatherType.Effects.AttributeMod.Operation operation) {}

    private WeatherEffects() {}

    /**
     * 应用一次效果：{@code active} 为 false 时恢复移速并清空伤害计时。
     *
     * @param type 当前的天气类型；{@code null} 等价于「没有天气」
     */
    public static void apply(ServerPlayer player, ServerLevel level, WeatherType type, boolean active) {
        WeatherType.Effects effects = type == null ? WeatherType.Effects.EMPTY : type.effects();
        boolean exposed = level.canSeeSky(player.blockPosition());
        if (active) {
            applyIgnite(player, level, effects.ignite());
            applyFreeze(player, level, effects.freeze());
            applyPotions(player, level, effects.potions());
        }
        float speed = active ? effects.movementSpeed().orElse(1.0F) : 1.0F;
        if (active) {
            speed += attributeSpeedBonus(effects.attributes(), exposed);
        }
        setSpeed(player, clampSpeed(speed));
        applyDamageTimer(player, level, active ? effects.damage().orElse(null) : null);
        applyAttributes(player, active ? effects.attributes() : List.of(), exposed);
    }

    /**
     * 玩家同时处在多个移动风暴里时按层合成效果：
     * 移速取最慢、点燃/冰冻取最长、药水按效果 id 取最强、伤害取单层最大值（共用同一个计时器）。
     *
     * <p>伤害不能逐层独立计时：两层 20 点伤害若各自计时，重合区会变成每秒两次伤害（40 点/秒），
     * 与「叠加」直觉不符，也会让高伤害档案在重合区秒杀玩家。</p>
     */
    public static void applyLayers(ServerPlayer player, ServerLevel level, List<WeatherLayer> layers) {
        if (layers.isEmpty()) {
            clear(player);
            return;
        }
        boolean exposed = level.canSeeSky(player.blockPosition());
        float speed = 1.0F;
        double speedBonus = 0.0D;
        int igniteTicks = 0;
        int freezeTicks = 0;
        WeatherType.Effects.Damage damage = null;
        Map<ResourceLocation, WeatherType.Effects.Potion> potions = new HashMap<>();
        List<WeatherType.Effects.AttributeMod> attributeMods = new java.util.ArrayList<>();
        for (WeatherLayer layer : layers) {
            WeatherType.Effects effects = layer.type().effects();
            speed = Math.min(speed, effects.movementSpeed().orElse(1.0F));
            speedBonus += attributeSpeedBonus(effects.attributes(), exposed);
            attributeMods.addAll(effects.attributes());
            if (effects.ignite().isPresent()) {
                WeatherType.Effects.Ignite ignite = effects.ignite().get();
                if (!ignite.onlyWhenExposed() || exposed) {
                    int ticks = (int) Math.max(0L,
                            Math.min(MAX_IGNITE_TICKS, Math.round(ignite.seconds() * 20.0F)));
                    igniteTicks = Math.max(igniteTicks, ticks);
                }
            }
            if (effects.freeze().isPresent()) {
                WeatherType.Effects.Freeze freeze = effects.freeze().get();
                if (!freeze.onlyWhenExposed() || exposed) {
                    freezeTicks = Math.max(freezeTicks, Math.max(0, freeze.ticks()));
                }
            }
            if (effects.damage().isPresent()) {
                WeatherType.Effects.Damage candidate = effects.damage().get();
                if (damage == null
                        || candidate.amount() > damage.amount()
                        || (candidate.amount() == damage.amount()
                                && candidate.intervalTicks() < damage.intervalTicks())) {
                    damage = candidate;
                }
            }
            for (WeatherType.Effects.Potion potion : effects.potions()) {
                WeatherType.Effects.Potion previous = potions.get(potion.effect());
                if (previous == null
                        || potion.amplifier() > previous.amplifier()
                        || (potion.amplifier() == previous.amplifier()
                                && potion.durationTicks() > previous.durationTicks())) {
                    potions.put(potion.effect(), potion);
                }
            }
        }
        if (igniteTicks > 0) {
            player.setRemainingFireTicks(Math.max(player.getRemainingFireTicks(), igniteTicks));
        }
        if (freezeTicks > 0) {
            player.setTicksFrozen(Math.min(MAX_FROZEN_TICKS, player.getTicksFrozen() + freezeTicks));
        }
        for (WeatherType.Effects.Potion potion : potions.values()) {
            if (potion.onlyWhenExposed() && !exposed) {
                continue;
            }
            Holder<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.getHolder(potion.effect()).orElse(null);
            if (effect == null) {
                continue;
            }
            player.addEffect(new MobEffectInstance(effect, potion.durationTicks(), potion.amplifier(),
                    potion.ambient(), potion.visible(), potion.visible()));
        }
        setSpeed(player, clampSpeed(speed + (float) speedBonus));
        applyDamageTimer(player, level, damage);
        applyAttributes(player, attributeMods, exposed);
    }

    private static void setSpeed(ServerPlayer player, float targetSpeed) {
        AttributeInstance attribute = player.getAttribute(Attributes.MOVEMENT_SPEED);
        // 缓存只记录「上次写入的倍率」；玩家死亡重生后新实体没有该修饰符，所以还要检查修饰符是否真的还在
        boolean missingModifier = attribute != null
                && Math.abs(targetSpeed - 1.0F) > 1.0E-4F
                && attribute.getModifier(SPEED_MODIFIER_ID) == null;
        float applied = APPLIED_SPEED.getOrDefault(player.getUUID(), 1.0F);
        if (Math.abs(targetSpeed - applied) > 1.0E-4F || missingModifier) {
            if (attribute != null) {
                attribute.removeModifier(SPEED_MODIFIER_ID);
                if (Math.abs(targetSpeed - 1.0F) > 1.0E-4F) {
                    attribute.addOrUpdateTransientModifier(new AttributeModifier(SPEED_MODIFIER_ID,
                            targetSpeed - 1.0F, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                }
            }
            APPLIED_SPEED.put(player.getUUID(), targetSpeed);
        }
    }

    private static void applyDamageTimer(ServerPlayer player, ServerLevel level,
                                         WeatherType.Effects.Damage spec) {
        if (spec == null) {
            DAMAGE_TICKS.remove(player.getUUID());
            return;
        }
        // 原版受伤后有 10 tick 无敌帧，间隔小于 10 的配置不会更快生效，这里直接钳到 10
        int interval = Math.max(10, spec.intervalTicks());
        int ticks = DAMAGE_TICKS.merge(player.getUUID(), 1, Integer::sum);
        if (ticks < interval) {
            return;
        }
        DAMAGE_TICKS.put(player.getUUID(), 0);
        if (spec.onlyWhenExposed() && !level.canSeeSky(player.blockPosition())) {
            return;
        }
        player.hurt(resolveDamageSource(level, spec), spec.amount());
    }

    /** 玩家已不在任何天气范围内：恢复移速并清空伤害计时（没有效果时不做任何事）。 */
    public static void clear(ServerPlayer player) {
        UUID id = player.getUUID();
        if (!APPLIED_SPEED.containsKey(id) && !DAMAGE_TICKS.containsKey(id) && !APPLIED_ATTRIBUTES.containsKey(id)) {
            return;
        }
        APPLIED_SPEED.remove(id);
        DAMAGE_TICKS.remove(id);
        removeAppliedAttributes(player);
        AttributeInstance attribute = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute != null) {
            attribute.removeModifier(SPEED_MODIFIER_ID);
        }
    }

    /** 玩家退出/重连：丢弃记录（新玩家实体本来也没有修饰符）。 */
    public static void forget(UUID playerId) {
        APPLIED_SPEED.remove(playerId);
        DAMAGE_TICKS.remove(playerId);
        APPLIED_ATTRIBUTES.remove(playerId);
    }

    /** 合并「任意属性」里针对移动速度的乘算，避免与服务端固定的移速修饰符互相覆盖。 */
    private static double attributeSpeedBonus(List<WeatherType.Effects.AttributeMod> mods, boolean exposed) {
        if (mods.isEmpty()) {
            return 0.0D;
        }
        double bonus = 0.0D;
        for (WeatherType.Effects.AttributeMod mod : mods) {
            if (mod == null || mod.attribute() == null || mod.operation() == null) {
                continue;
            }
            if (mod.onlyWhenExposed() && !exposed) {
                continue;
            }
            if (!mod.attribute().equals(MOVEMENT_SPEED_ID)
                    || mod.operation() != WeatherType.Effects.AttributeMod.Operation.ADD_MULTIPLIED_TOTAL) {
                continue;
            }
            if (Double.isFinite(mod.amount())) {
                bonus += Mth.clamp(mod.amount(), -1000.0D, 1000.0D);
            }
        }
        return bonus;
    }

    /**
     * 写入 {@code effects.attributes}：同属性同运算先求和（每玩家最多 16 组），再差量增删
     * 固定 id 的瞬时修饰符。天气结束或玩家离开天气时由 {@link #clear} 移除。
     */
    private static void applyAttributes(ServerPlayer player,
                                        List<WeatherType.Effects.AttributeMod> mods, boolean exposed) {
        UUID id = player.getUUID();
        Map<AttributeKey, Double> target = new LinkedHashMap<>();
        boolean enabled = Config.effectsApplyAttributes();
        if (enabled) {
            for (WeatherType.Effects.AttributeMod mod : mods) {
                if (mod == null || mod.attribute() == null || mod.operation() == null) {
                    continue;
                }
                if (mod.onlyWhenExposed() && !exposed) {
                    continue;
                }
                if (!Double.isFinite(mod.amount())) {
                    continue;
                }
                // 移速乘算统一交给 setSpeed 合并，避免同一属性出现两个来源
                if (mod.attribute().equals(MOVEMENT_SPEED_ID)
                        && mod.operation() == WeatherType.Effects.AttributeMod.Operation.ADD_MULTIPLIED_TOTAL) {
                    continue;
                }
                double amount = Mth.clamp(mod.amount(), -1000.0D, 1000.0D);
                if (amount == 0.0D) {
                    continue;
                }
                target.merge(new AttributeKey(mod.attribute(), mod.operation()), amount, Double::sum);
                if (target.size() >= MAX_ATTRIBUTE_MODIFIERS) {
                    break;
                }
            }
        }

        Map<AttributeKey, Double> previous = APPLIED_ATTRIBUTES.getOrDefault(id, Map.of());
        for (AttributeKey key : previous.keySet()) {
            if (!target.containsKey(key)) {
                removeAttributeModifier(player, key);
            }
        }
        for (Map.Entry<AttributeKey, Double> entry : target.entrySet()) {
            Double old = previous.get(entry.getKey());
            if (old != null && Math.abs(old - entry.getValue()) < 1.0E-6D) {
                continue;
            }
            Holder<Attribute> attribute = BuiltInRegistries.ATTRIBUTE.getHolder(entry.getKey().attribute())
                    .orElse(null);
            if (attribute == null) {
                warnUnknownAttribute(entry.getKey().attribute());
                removeAttributeModifier(player, entry.getKey());
                continue;
            }
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            ResourceLocation modifierId = attributeModifierId(entry.getKey());
            instance.removeModifier(modifierId);
            double amount = Mth.clamp(entry.getValue(), -10_000.0D, 10_000.0D);
            instance.addOrUpdateTransientModifier(new AttributeModifier(modifierId, amount,
                    entry.getKey().operation().vanilla()));
        }
        if (target.isEmpty()) {
            APPLIED_ATTRIBUTES.remove(id);
        } else {
            APPLIED_ATTRIBUTES.put(id, target);
        }
    }

    /** 清理当前玩家已写入的全部天气属性修饰符。 */
    private static void removeAppliedAttributes(ServerPlayer player) {
        Map<AttributeKey, Double> applied = APPLIED_ATTRIBUTES.remove(player.getUUID());
        if (applied == null || applied.isEmpty()) {
            return;
        }
        for (AttributeKey key : applied.keySet()) {
            removeAttributeModifier(player, key);
        }
    }

    private static void removeAttributeModifier(ServerPlayer player, AttributeKey key) {
        Holder<Attribute> attribute = BuiltInRegistries.ATTRIBUTE.getHolder(key.attribute()).orElse(null);
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(attributeModifierId(key));
        }
    }

    /** 固定修饰符 id：同一属性 + 运算永远复用同一个，叠加时只更新数值。 */
    private static ResourceLocation attributeModifierId(AttributeKey key) {
        return ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID,
                "weather_attr/" + key.attribute().getNamespace() + "/" + key.attribute().getPath()
                        + "/" + key.operation().key());
    }

    private static void warnUnknownAttribute(ResourceLocation attribute) {
        if (WARNED_ATTRIBUTES.add(attribute)) {
            Localizedrain.LOGGER.warn("Weather effects reference unknown attribute {}; ignoring it", attribute);
        }
    }

    private static float clampSpeed(float speed) {
        return Float.isFinite(speed) ? Mth.clamp(speed, 0.05F, 4.0F) : 1.0F;
    }

    private static void applyIgnite(ServerPlayer player, ServerLevel level,
                                    Optional<WeatherType.Effects.Ignite> spec) {
        if (spec.isEmpty()) {
            return;
        }
        WeatherType.Effects.Ignite ignite = spec.get();
        if (ignite.onlyWhenExposed() && !level.canSeeSky(player.blockPosition())) {
            return;
        }
        int ticks = (int) Math.max(0L, Math.min(MAX_IGNITE_TICKS, Math.round(ignite.seconds() * 20.0F)));
        if (ticks > 0) {
            // 只延长、不缩短：玩家本身若已被更长的火点燃，保持原值
            player.setRemainingFireTicks(Math.max(player.getRemainingFireTicks(), ticks));
        }
    }

    private static void applyFreeze(ServerPlayer player, ServerLevel level,
                                    Optional<WeatherType.Effects.Freeze> spec) {
        if (spec.isEmpty()) {
            return;
        }
        WeatherType.Effects.Freeze freeze = spec.get();
        if (freeze.onlyWhenExposed() && !level.canSeeSky(player.blockPosition())) {
            return;
        }
        if (freeze.ticks() > 0) {
            player.setTicksFrozen(Math.min(MAX_FROZEN_TICKS, player.getTicksFrozen() + freeze.ticks()));
        }
    }

    private static void applyPotions(ServerPlayer player, ServerLevel level,
                                     List<WeatherType.Effects.Potion> potions) {
        for (WeatherType.Effects.Potion spec : potions) {
            if (spec.onlyWhenExposed() && !level.canSeeSky(player.blockPosition())) {
                continue;
            }
            Holder<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.getHolder(spec.effect()).orElse(null);
            if (effect == null) {
                continue;
            }
            player.addEffect(new MobEffectInstance(effect, spec.durationTicks(), spec.amplifier(),
                    spec.ambient(), spec.visible(), spec.visible()));
        }
    }

    private static DamageSource resolveDamageSource(ServerLevel level, WeatherType.Effects.Damage spec) {
        if (spec.type().isPresent()) {
            Optional<Holder.Reference<DamageType>> holder = level.registryAccess()
                    .registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolder(spec.type().get());
            if (holder.isPresent()) {
                return new DamageSource(holder.get());
            }
        }
        return level.damageSources().generic();
    }
}
