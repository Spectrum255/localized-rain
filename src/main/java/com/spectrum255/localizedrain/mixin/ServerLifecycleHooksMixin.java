package com.spectrum255.localizedrain.mixin;

import com.spectrum255.localizedrain.ConfigFiles;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 NeoForge 加载 SERVER 配置之前，把全局 {@code config/localizedrain/weather.toml} 与
 * {@code filters.toml} 复制到当前存档的 {@code serverconfig/localizedrain/}。这样 FML 的 override path
 * 从本次启动起就是存档自己的文件，每个存档的天气与过滤器设置自第一次启动即独立。
 */
@Mixin(ServerLifecycleHooks.class)
public abstract class ServerLifecycleHooksMixin {
    @Inject(method = "handleServerAboutToStart", at = @At("HEAD"))
    private static void localizedrain$seedPerWorldConfigs(MinecraftServer server, CallbackInfo callback) {
        ConfigFiles.seedWorldConfigs(server);
    }
}
