package com.spectrum255.localizedrain.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端天气渐变：服务端下发的层列表变化时，在 {@code weatherTransitionTicks} 内把旧层淡出、新层淡入。
 *
 * <p>按类型 id 取并集逐层插值：旧层强度 ×(1−t)、新层强度 ×t；只在新层里出现的类型从 0 升到目标，
 * 只在旧层里出现的类型从旧值降到 0。渐变期间所有消费者（雾、粒子、声音、天空覆盖）读同一份
 * {@code current}，因此不会出现「雾已切换、粒子还在用旧类型」的错位。</p>
 *
 * <p>中途再次切换时，以当前混合结果作为新的起点，避免从旧目标跳变。</p>
 */
public final class ClientWeatherTransitions {
    private static List<ClientWeatherVisuals.VisualLayer> from = List.of();
    private static List<ClientWeatherVisuals.VisualLayer> to = List.of();
    private static List<ClientWeatherVisuals.VisualLayer> current = List.of();
    private static int age;

    private ClientWeatherTransitions() {}

    /**
     * @param target 本次服务端快照解析出的目标层（可能为空）
     * @param ticks  渐变时长；0 或负数表示立即切换
     */
    static List<ClientWeatherVisuals.VisualLayer> blend(List<ClientWeatherVisuals.VisualLayer> target, int ticks) {
        List<ClientWeatherVisuals.VisualLayer> copy = target.isEmpty() ? List.of() : List.copyOf(target);
        if (ticks <= 0) {
            from = List.of();
            to = copy;
            current = copy;
            age = 0;
            return current;
        }
        if (sameLayers(copy, to)) {
            // 类型集合没变时也要刷新目标引用：服务端强度逐 tick 变化，若一直沿用旧 to，
            // 强度会被冻结在第一次快照。这里只更新 to，不改渐变进度，强度仍有 1e-4 容差内的小幅抖动。
            to = copy;
            if (age < ticks) {
                age++;
                current = mix(from, to, age / (float) ticks);
            } else {
                current = to;
            }
            return current;
        }
        // 类型集合或强度出现明显变化：以当前混合结果为起点重新渐变，避免从旧目标跳变。
        from = current;
        to = copy;
        age = 0;
        current = mix(from, to, 0.0F);
        return current;
    }

    /** 断线/换维度时清空，避免把上一个维度的天气带进新维度。 */
    public static void clear() {
        from = List.of();
        to = List.of();
        current = List.of();
        age = 0;
    }

    /**
     * 比较层集合是否「实质相同」：类型 id 与顺序一致，且强度变化不超过容差。
     * 强度明显变化（例如风暴从 0.2 升到 1.0）会重新开始渐变，而不是被丢弃。
     */
    private static boolean sameLayers(List<ClientWeatherVisuals.VisualLayer> left,
                                      List<ClientWeatherVisuals.VisualLayer> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            ClientWeatherVisuals.VisualLayer a = left.get(i);
            ClientWeatherVisuals.VisualLayer b = right.get(i);
            if (!a.typeId().equals(b.typeId())) {
                return false;
            }
            if (Math.abs(a.intensity() - b.intensity()) > 1.0E-4F) {
                return false;
            }
        }
        return true;
    }

    private static List<ClientWeatherVisuals.VisualLayer> mix(List<ClientWeatherVisuals.VisualLayer> oldLayers,
                                                               List<ClientWeatherVisuals.VisualLayer> newLayers,
                                                               float t) {
        float clamped = Math.max(0.0F, Math.min(1.0F, t));
        List<ClientWeatherVisuals.VisualLayer> mixed = new ArrayList<>(newLayers.size() + oldLayers.size());
        for (ClientWeatherVisuals.VisualLayer layer : newLayers) {
            ClientWeatherVisuals.VisualLayer previous = find(oldLayers, layer.typeId());
            float start = previous == null ? 0.0F : previous.intensity();
            float intensity = start + (layer.intensity() - start) * clamped;
            if (intensity > 0.005F) {
                mixed.add(new ClientWeatherVisuals.VisualLayer(layer.type(), layer.typeId(), layer.typeKey(),
                        intensity));
            }
        }
        for (ClientWeatherVisuals.VisualLayer layer : oldLayers) {
            if (find(newLayers, layer.typeId()) != null) {
                continue;
            }
            float intensity = layer.intensity() * (1.0F - clamped);
            if (intensity > 0.005F) {
                mixed.add(new ClientWeatherVisuals.VisualLayer(layer.type(), layer.typeId(), layer.typeKey(),
                        intensity));
            }
        }
        return mixed;
    }

    private static ClientWeatherVisuals.VisualLayer find(List<ClientWeatherVisuals.VisualLayer> layers,
                                                         ResourceLocation typeId) {
        for (ClientWeatherVisuals.VisualLayer layer : layers) {
            if (layer.typeId().equals(typeId)) {
                return layer;
            }
        }
        return null;
    }
}
