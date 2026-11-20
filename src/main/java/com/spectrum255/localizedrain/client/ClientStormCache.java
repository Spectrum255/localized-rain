package com.spectrum255.localizedrain.client;

import com.spectrum255.localizedrain.network.StormSyncPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** 客户端风暴缓存：周期包做权威校正，tick 之间按速度外推。 */
public final class ClientStormCache {
    private static final Map<UUID, Storm> STORMS = new HashMap<>();
    /** 是否收到过至少一次风暴快照；用于判断服务端是否处于移动模式，而不是依赖本地配置。 */
    private static boolean everReceived;
    /** 服务端当前是否由风暴系统接管天气（由服务端随快照下发，切换模式后立即失效）。 */
    private static boolean active;
    /** 服务端配置（随快照下发）：隔离群系天气与强度衰减带宽度。 */
    private static String isolatedMode = "clear";
    private static double falloffRatio = 0.3D;

    public static final class Storm {
        private final UUID id;
        private final ResourceLocation typeId;
        private final boolean hasPrecipitation;
        private final boolean allowsThunder;
        private final double minY;
        private final double maxY;
        private float rain;
        private float thunder;
        private final boolean manual;
        private final boolean dying;
        private int dyingTicksLeft;
        private double x;
        private double z;
        private double radius;
        private double velocityX;
        private double velocityZ;
        /** 最近一次权威快照的位置；外推偏离过远时吸附回来。 */
        private double anchorX;
        private double anchorZ;

        private Storm(StormSyncPayload.StormEntry entry) {
            this.id = entry.id();
            this.typeId = entry.typeId();
            this.hasPrecipitation = entry.hasPrecipitation();
            this.allowsThunder = entry.allowsThunder();
            this.minY = entry.minY();
            this.maxY = entry.maxY();
            this.rain = entry.rain();
            this.thunder = entry.thunder();
            this.manual = entry.manual();
            this.dying = entry.dying();
            this.dyingTicksLeft = entry.dyingTicksLeft();
            this.x = entry.x();
            this.z = entry.z();
            this.radius = entry.radius();
            this.velocityX = entry.velocityX();
            this.velocityZ = entry.velocityZ();
            this.anchorX = entry.x();
            this.anchorZ = entry.z();
        }

        public double x() { return this.x; }
        public double z() { return this.z; }
        public double radius() { return this.radius; }
        public double minY() { return this.minY; }
        public double maxY() { return this.maxY; }
        public float rain() { return this.rain; }
        public float thunder() { return this.thunder; }
        public boolean dying() { return this.dying; }
        public int dyingTicksLeft() { return this.dyingTicksLeft; }
        public ResourceLocation typeId() { return this.typeId; }
        public boolean hasPrecipitation() { return this.hasPrecipitation; }
        public boolean allowsThunder() { return this.allowsThunder; }
        public boolean manual() { return this.manual; }
    }

    private ClientStormCache() {}

    /** 全量替换，同时把位置向权威值靠拢（不硬跳，避免 40 tick 一次的抖动）。 */
    public static void accept(StormSyncPayload payload) {
        everReceived = true;
        active = payload.active();
        isolatedMode = payload.isolatedMode();
        falloffRatio = payload.falloffRatio();
        if (!active) {
            // 服务端不在移动模式：不留旧风暴，避免切回 patches 后仍按缓存写雨量
            STORMS.clear();
            return;
        }
        Map<UUID, Storm> next = new HashMap<>();
        for (StormSyncPayload.StormEntry entry : payload.storms()) {
            Storm existing = STORMS.get(entry.id());
            Storm storm = new Storm(entry);
            if (existing != null) {
                storm.x = existing.x + (entry.x() - existing.x) * 0.5D;
                storm.z = existing.z + (entry.z() - existing.z) * 0.5D;
                // 半径与强度同样做半程插值：硬替换会让 40 tick 一次的快照产生可见跳变
                storm.radius = existing.radius + (entry.radius() - existing.radius) * 0.5D;
                storm.rain = existing.rain + (entry.rain() - existing.rain) * 0.5F;
                storm.thunder = existing.thunder + (entry.thunder() - existing.thunder) * 0.5F;
                // anchor 跟随最新权威位置（构造函数里已写 entry.x/z，这里再显式写一次，
                // 避免以后把构造逻辑改成分支时又退化成「永远吸附到出生点」）
                storm.anchorX = entry.x();
                storm.anchorZ = entry.z();
            }
            next.put(storm.id, storm);
        }
        STORMS.clear();
        STORMS.putAll(next);
    }

    /** 每 tick 按速度外推一次，等价于服务端的真实推进。 */
    public static void tick() {
        for (Storm storm : STORMS.values()) {
            storm.x += storm.velocityX;
            storm.z += storm.velocityZ;
            // 快照停发时不要无限外推：偏离最近权威位置太远就吸附回去
            double limit = Math.max(512.0D, storm.radius * 4.0D);
            if (Math.hypot(storm.x - storm.anchorX, storm.z - storm.anchorZ) > limit) {
                storm.x = storm.anchorX;
                storm.z = storm.anchorZ;
            }
            if (storm.dying && storm.dyingTicksLeft > 0) {
                storm.dyingTicksLeft--;
            }
        }
    }

    public static List<Storm> storms() {
        return new ArrayList<>(STORMS.values());
    }

    public static void clear() {
        STORMS.clear();
        everReceived = false;
        active = false;
        isolatedMode = "clear";
        falloffRatio = 0.3D;
    }

    public static boolean hasEverReceived() {
        return everReceived;
    }

    /** 服务端是否处于移动模式（快照里的 active 标志）。 */
    public static boolean active() {
        return active;
    }

    public static String isolatedMode() {
        return isolatedMode;
    }

    public static double falloffRatio() {
        return falloffRatio;
    }
}
