package com.spectrum255.localizedrain.weather;

import com.spectrum255.localizedrain.Localizedrain;
import com.spectrum255.localizedrain.api.WeatherType;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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

    private WeatherEffects() {}

    /**
     * 应用一次效果：{@code active} 为 false 时恢复移速并清空伤害计时。
     *
     * @param type 当前的天气类型；{@code null} 等价于「没有天气」
     */
    public static void apply(ServerPlayer player, ServerLevel level, WeatherType type, boolean active) {
        WeatherType.Effects effects = type == null ? WeatherType.Effects.EMPTY : type.effects();
        float targetSpeed = active ? effects.movementSpeed().orElse(1.0F) : 1.0F;
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
        Optional<WeatherType.Effects.Damage> damage = effects.damage();
        if (!active || damage.isEmpty()) {
            DAMAGE_TICKS.remove(player.getUUID());
            return;
        }
        WeatherType.Effects.Damage spec = damage.get();
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
        if (!APPLIED_SPEED.containsKey(id) && !DAMAGE_TICKS.containsKey(id)) {
            return;
        }
        APPLIED_SPEED.remove(id);
        DAMAGE_TICKS.remove(id);
        AttributeInstance attribute = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute != null) {
            attribute.removeModifier(SPEED_MODIFIER_ID);
        }
    }

    /** 玩家退出/重连：丢弃记录（新玩家实体本来也没有修饰符）。 */
    public static void forget(UUID playerId) {
        APPLIED_SPEED.remove(playerId);
        DAMAGE_TICKS.remove(playerId);
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
