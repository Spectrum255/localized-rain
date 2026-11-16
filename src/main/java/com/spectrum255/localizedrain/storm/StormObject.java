package com.spectrum255.localizedrain.storm;

import com.spectrum255.localizedrain.api.StormProfile;
import com.spectrum255.localizedrain.api.StormProfiles;
import com.spectrum255.localizedrain.api.StormSnapshot;
import com.spectrum255.localizedrain.api.WeatherType;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** 单个风暴：水平圆 + 竖直区间，无三维体积。 */
final class StormObject {
    /** 消亡淡出固定 tick 数（设计 §7.3）；与快照共用 `StormTuning.FADE_TICKS`，领域层不依赖网络包。 */
    static final int DYING_TICKS = StormTuning.FADE_TICKS;

    UUID id;
    ResourceLocation profileId;
    /** 解析后的档案引用：每 tick 的查询会调用几十次，避免反复走注册表 Optional。 */
    private StormProfile cachedProfile;
    /** 是否已经解析过（用来区分「尚未解析」与「解析失败」）。 */
    private boolean profileResolved;
    /** 手动风暴指定的天气类型；非 null 时压过档案阶段类型。 */
    ResourceLocation typeOverride;
    StormProfile.VerticalMode verticalMode = StormProfile.VerticalMode.SURFACE;
    double minY;
    double maxY;
    double x;
    double z;
    double radius;
    double velocityX;
    double velocityZ;
    /** 手动指定过速度后不再被风场覆盖。 */
    boolean pinnedVelocity;
    /** 半径是否由生成参数显式指定；是则阶段切换不再重置半径。 */
    boolean radiusExplicit;
    int stageIndex;
    double stageTicksLeft;
    double stageTicksTotal;
    float rainIntensity;
    float thunderIntensity;
    float temperature = 0.5F;
    float moisture = 0.5F;
    /** API/命令显式覆盖的强度；非 null 时压过阶段插值结果。 */
    Float rainOverride;
    Float thunderOverride;
    /** 当前生效天气类型对应 id；由 StormManager 在类型变化时刷新能力位。 */
    ResourceLocation capabilityType;
    boolean precipitationCapability = true;
    boolean thunderCapability = true;
    boolean manual;
    boolean dying;
    int dyingTicksLeft;
    /** 手动风暴的剩余寿命；-1 = 不限制（按档案阶段推进）。 */
    int lifeTicksLeft = -1;
    /** 连续远离所有玩家多少 tick；用于「远离后淡出」。 */
    int farTicks;
    /** 本次淡出是否由「远离玩家」触发；瞬态标记，不持久化。 */
    boolean farDespawn;
    long spawnGameTime;
    long seed;
    /** 当前阶段的半径系数；由 StormManager 在每 tick 推进前写入 profile 对应阶段。 */
    StormProfile.Range stageScale = new StormProfile.Range(1.0D, 1.0D);

    /** 上一次阶段切换的来源阶段，-1 表示本 tick 没有切换；由 StormManager 消费后复位。 */
    int lastTransitionFrom = -1;

    private StormObject() {}

    static StormObject create(UUID id, ResourceLocation profileId, double x, double z, double radius,
                              double minY, double maxY, StormProfile.VerticalMode verticalMode,
                              boolean manual, long seed, long gameTime) {
        StormObject storm = new StormObject();
        storm.id = id;
        storm.profileId = profileId;
        storm.x = x;
        storm.z = z;
        storm.radius = radius;
        storm.minY = minY;
        storm.maxY = maxY;
        storm.verticalMode = verticalMode;
        storm.manual = manual;
        storm.seed = seed;
        storm.spawnGameTime = gameTime;
        return storm;
    }

    public UUID id() { return this.id; }

    /** 解析并缓存档案；数据包重载或服务器停止时由 StormManager 清空。未知档案只解析一次，避免每 tick 回源。 */
    StormProfile resolveProfile(ServerLevel level) {
        if (!this.profileResolved) {
            this.cachedProfile = StormProfiles.byId(level, this.profileId)
                    .map(Holder.Reference::value)
                    .orElse(null);
            this.profileResolved = true;
        }
        return this.cachedProfile;
    }

    void clearProfileCache() {
        this.cachedProfile = null;
        this.profileResolved = false;
    }
    public ResourceLocation profileId() { return this.profileId; }
    public StormProfile.VerticalMode verticalMode() { return this.verticalMode; }
    /** 生效的天气类型：手动覆盖优先，否则取当前阶段类型。 */
    public ResourceLocation weatherType(StormProfile profile) {
        return this.typeOverride != null ? this.typeOverride : profile.stage(this.stageIndex).type();
    }
    public double x() { return this.x; }
    public double z() { return this.z; }
    public double minY() { return this.minY; }
    public double maxY() { return this.maxY; }
    public boolean manual() { return this.manual; }
    public boolean dying() { return this.dying; }
    public int stageIndex() { return this.stageIndex; }
    public float rainIntensity() { return this.rainIntensity; }
    public float thunderIntensity() { return this.thunderIntensity; }
    public double radius() { return this.radius; }

    /** 阶段内按 radius_scale 线性插值。 */
    public double radiusAt() {
        if (this.stageTicksTotal <= 0.0D) {
            return this.radius;
        }
        double progress = 1.0D - Math.max(0.0D, this.stageTicksLeft) / this.stageTicksTotal;
        return this.radius * this.radiusScale().lerp((float) progress);
    }

    private StormProfile.Range radiusScale() {
        return this.stageScale;
    }

    /** 消亡淡出系数：1 → 0。 */
    public float fade() {
        return this.dying ? Math.max(0.0F, this.dyingTicksLeft / (float) DYING_TICKS) : 1.0F;
    }

    /** 未乘淡出系数的强度：同步给客户端后由客户端按剩余淡出 tick 自行平滑。 */
    public float baseRain() {
        float value = this.rainOverride != null ? this.rainOverride : this.rainIntensity;
        return this.precipitationCapability ? value : 0.0F;
    }

    public float baseThunder() {
        float value = this.thunderOverride != null ? this.thunderOverride : this.thunderIntensity;
        return this.thunderCapability ? value : 0.0F;
    }

    /** 按天气类型的能力位统一钳制强度；类型变化、阶段切换、读档后都走这里。 */
    void applyCapabilities(WeatherType type) {
        this.precipitationCapability = type == null || type.hasPrecipitation();
        this.thunderCapability = type == null || type.allowsThunder();
        clampIntensities();
    }

    private void clampIntensities() {
        if (!this.precipitationCapability) {
            this.rainIntensity = 0.0F;
        }
        if (!this.thunderCapability) {
            this.thunderIntensity = 0.0F;
        }
    }

    public float effectiveRain() {
        return this.baseRain() * this.fade();
    }

    public float effectiveThunder() {
        return this.baseThunder() * this.fade();
    }

    public boolean covers(double px, double py, double pz) {
        double dx = px - this.x;
        double dz = pz - this.z;
        return dx * dx + dz * dz < this.radiusAt() * this.radiusAt()
                && py >= this.minY && py <= this.maxY;
    }

    /** 推进一次：移动、阶段计时、强度趋近、消亡倒计时。 */
    void tick(StormProfile profile, WindField wind, double speedFactor) {
        if (this.dying) {
            this.dyingTicksLeft--;
            return;
        }
        if (this.lifeTicksLeft > 0 && --this.lifeTicksLeft == 0) {
            // 手动风暴的寿命由生成参数决定，到期直接进入淡出
            this.startDying();
            return;
        }
        if (!this.pinnedVelocity) {
            this.velocityX = wind.velocityX() * profile.speedMultiplier();
            this.velocityZ = wind.velocityZ() * profile.speedMultiplier();
        }
        this.x += this.velocityX;
        this.z += this.velocityZ;

        StormProfile.Stage stage = profile.stage(this.stageIndex);
        this.stageScale = stage.radiusScale();
        double progress = this.stageTicksTotal <= 0.0D
                ? 1.0D : 1.0D - Math.max(0.0D, this.stageTicksLeft) / this.stageTicksTotal;
        this.rainIntensity = stage.rain().lerp((float) progress);
        this.thunderIntensity = stage.thunder().lerp((float) progress);
        clampIntensities();

        this.stageTicksLeft -= speedFactor;
        if (this.stageTicksLeft > 0.0D) {
            return;
        }
        if (this.stageIndex + 1 < profile.stages().size()) {
            this.enterStage(profile, this.stageIndex + 1, stage);
        } else {
            this.startDying();
        }
    }

    void enterStage(StormProfile profile, int index, StormProfile.Stage from) {
        this.lastTransitionFrom = this.stageIndex;
        this.stageIndex = index;
        StormProfile.Stage next = profile.stage(index);
        this.stageTicksTotal = this.stageTicksLeft = pickTicks(this.seed, next);
        this.stageScale = next.radiusScale();
        this.rainIntensity = next.rain().lerp(0.0F);
        this.thunderIntensity = next.thunder().lerp(0.0F);
        // 阶段切换后半径回到该档案半径区间的中点，再按新阶段的 radius_scale 插值；
        // 生成时显式指定过半径（指令/API/洞穴风暴）的风暴保留自己的半径
        if (!this.radiusExplicit) {
            this.radius = (profile.radius().min() + profile.radius().max()) * 0.5D;
        }
        // 强度覆盖只对当前阶段有效，切阶段后回到档案定义
        this.rainOverride = null;
        this.thunderOverride = null;
        clampIntensities();
    }

    void startDying() {
        this.dying = true;
        this.dyingTicksLeft = DYING_TICKS;
    }

    static double pickTicks(long seed, StormProfile.Stage stage) {
        long mixed = seed ^ ((long) stage.minTicks() << 32);
        double unit = ((mixed >>> 11) & 0x1FFFFF) / (double) 0x1FFFFF;
        return stage.minTicks() + unit * (stage.maxTicks() - stage.minTicks());
    }

    StormSnapshot snapshot(StormProfile profile) {
        return new StormSnapshot(this.id, this.x, this.z, this.radiusAt(), this.minY, this.maxY,
                this.profileId, this.weatherType(profile),
                this.stageIndex, profile.stages().size(),
                this.effectiveRain(), this.effectiveThunder(),
                this.velocityX, this.velocityZ,
                this.temperature, this.moisture,
                (long) Math.ceil(Math.max(0.0D, this.stageTicksLeft)),
                this.manual, this.dying);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", this.id);
        tag.putString("profile", this.profileId.toString());
        if (this.typeOverride != null) {
            tag.putString("type_override", this.typeOverride.toString());
        }
        tag.putString("vertical", this.verticalMode.getSerializedName());
        tag.putDouble("min_y", this.minY);
        tag.putDouble("max_y", this.maxY);
        tag.putDouble("x", this.x);
        tag.putDouble("z", this.z);
        tag.putDouble("radius", this.radius);
        tag.putDouble("vx", this.velocityX);
        tag.putDouble("vz", this.velocityZ);
        tag.putBoolean("pinned", this.pinnedVelocity);
        tag.putBoolean("radius_explicit", this.radiusExplicit);
        tag.putInt("stage", this.stageIndex);
        tag.putDouble("stage_left", this.stageTicksLeft);
        tag.putDouble("stage_total", this.stageTicksTotal);
        tag.putFloat("rain", this.rainIntensity);
        tag.putFloat("thunder", this.thunderIntensity);
        tag.putFloat("temperature", this.temperature);
        tag.putFloat("moisture", this.moisture);
        if (this.rainOverride != null) {
            tag.putFloat("rain_override", this.rainOverride);
        }
        if (this.thunderOverride != null) {
            tag.putFloat("thunder_override", this.thunderOverride);
        }
        tag.putBoolean("manual", this.manual);
        tag.putBoolean("dying", this.dying);
        tag.putInt("dying_left", this.dyingTicksLeft);
        tag.putInt("life_left", this.lifeTicksLeft);
        tag.putInt("far_ticks", this.farTicks);
        tag.putLong("spawn", this.spawnGameTime);
        tag.putLong("seed", this.seed);
        return tag;
    }

    static StormObject load(CompoundTag tag) {
        StormObject storm = new StormObject();
        storm.id = tag.getUUID("id");
        storm.profileId = ResourceLocation.tryParse(tag.getString("profile"));
        storm.typeOverride = tag.contains("type_override")
                ? ResourceLocation.tryParse(tag.getString("type_override")) : null;
        for (StormProfile.VerticalMode mode : StormProfile.VerticalMode.values()) {
            if (mode.getSerializedName().equals(tag.getString("vertical"))) {
                storm.verticalMode = mode;
            }
        }
        storm.minY = tag.getDouble("min_y");
        storm.maxY = tag.getDouble("max_y");
        storm.x = tag.getDouble("x");
        storm.z = tag.getDouble("z");
        storm.radius = tag.getDouble("radius");
        storm.velocityX = tag.getDouble("vx");
        storm.velocityZ = tag.getDouble("vz");
        storm.pinnedVelocity = tag.getBoolean("pinned");
        storm.radiusExplicit = tag.getBoolean("radius_explicit");
        storm.stageIndex = tag.getInt("stage");
        storm.stageTicksLeft = tag.getDouble("stage_left");
        storm.stageTicksTotal = tag.getDouble("stage_total");
        storm.rainIntensity = tag.getFloat("rain");
        storm.thunderIntensity = tag.getFloat("thunder");
        storm.temperature = tag.getFloat("temperature");
        storm.moisture = tag.getFloat("moisture");
        storm.rainOverride = tag.contains("rain_override") ? tag.getFloat("rain_override") : null;
        storm.thunderOverride = tag.contains("thunder_override") ? tag.getFloat("thunder_override") : null;
        storm.manual = tag.getBoolean("manual");
        storm.dying = tag.getBoolean("dying");
        storm.dyingTicksLeft = tag.getInt("dying_left");
        storm.lifeTicksLeft = tag.contains("life_left") ? tag.getInt("life_left") : -1;
        storm.farTicks = tag.getInt("far_ticks");
        storm.spawnGameTime = tag.getLong("spawn");
        storm.seed = tag.getLong("seed");
        return storm;
    }
}
