package com.spectrum255.localizedrain.client.compat;

import com.spectrum255.localizedrain.Localizedrain;
import com.spectrum255.localizedrain.network.ClientWeatherTargets;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;

/**
 * 用本模组的即时目标天气替换 Xaero's Minimap 内置的 weather 信息行，让 HUD 在跨群系片时立刻切换，
 * 而天空/雨声/雨粒子仍按服务端的 0.01/tick 平滑值渐变。
 *
 * <p>本类**不引用任何 Xaero 的类**：Xaero 只是可选联动，本模组不对它产生编译期或运行期强依赖。
 * 所有调用都通过反射完成，没装 Xaero 时这里连一个类都不会加载；Xaero 改版导致方法签名不匹配时，
 * 只会在日志里警告一次并禁用联动，不影响主模组。</p>
 */
public final class XaeroWeatherCompat {
    /** 沿用内置行的 id，这样用户原来的启用状态和显示顺序都不变。 */
    private static final String DISPLAY_ID = "weather";

    private static final String XAERO_MINIMAP = "xaero.minimap.XaeroMinimap";
    private static final String INFO_DISPLAY = "xaero.hud.minimap.info.InfoDisplay";
    private static final String INFO_DISPLAY_MANAGER = "xaero.hud.minimap.info.InfoDisplayManager";
    private static final String INFO_DISPLAY_BUILDER = "xaero.hud.minimap.info.InfoDisplay$Builder";
    private static final String INFO_DISPLAY_ON_COMPILE = "xaero.hud.minimap.info.render.compile.InfoDisplayOnCompile";
    private static final String BUILT_IN_IO_CODECS =
            "xaero.lib.common.config.option.value.io.serialization.BuiltInConfigValueIOCodecs";
    private static final String WIDGET_FACTORIES = "xaero.hud.minimap.info.widget.InfoDisplayCommonWidgetFactories";

    /** 已确认不可用（Xaero 版本不匹配等），不再重试。 */
    private static boolean disabled;
    /** 当前已安装的显示实例；Xaero 重建内置行时靠它识别并重新替换。 */
    private static Object installedDisplay;

    private XaeroWeatherCompat() {}

    /** @return true 表示本次（或之前）已经装好。 */
    public static boolean install() {
        if (disabled) {
            return false;
        }
        try {
            return install0();
        } catch (Throwable throwable) {
            disabled = true;
            Localizedrain.LOGGER.warn(
                    "[localizedrain] Xaero weather display integration disabled: {}", throwable.toString());
            return false;
        }
    }

    private static boolean install0() throws Exception {
        Class<?> xaeroMinimapClass = load(XAERO_MINIMAP);
        ClassLoader loader = xaeroMinimapClass.getClassLoader();
        Object xaero = xaeroMinimapClass.getField("instance").get(null);
        if (xaero == null) {
            return false;
        }
        Class<?> minimapClass = loadWith(loader, "xaero.hud.minimap.Minimap");
        Object minimap = findMethod(xaeroMinimapClass, "getMinimap").invoke(xaero);
        if (minimap == null) {
            return false;
        }
        Class<?> infoDisplaysClass = loadWith(loader, "xaero.hud.minimap.info.InfoDisplays");
        Object infoDisplays = findMethod(minimapClass, "getInfoDisplays").invoke(minimap);
        if (infoDisplays == null) {
            return false;
        }
        Class<?> managerClass = loadWith(loader, INFO_DISPLAY_MANAGER);
        Object manager = findMethod(infoDisplaysClass, "getManager").invoke(infoDisplays);
        if (manager == null) {
            return false;
        }
        Object existing = findMethod(managerClass, "get", String.class).invoke(manager, DISPLAY_ID);
        if (existing == null) {
            // 内置 weather 行还没创建，等下一次客户端 tick
            return false;
        }
        if (existing == installedDisplay) {
            return true;
        }

        Class<?> infoDisplayClass = loadWith(loader, INFO_DISPLAY);
        Class<?> builderClass = loadWith(loader, INFO_DISPLAY_BUILDER);
        Class<?> onCompileClass = loadWith(loader, INFO_DISPLAY_ON_COMPILE);

        Object builder = findMethod(builderClass, "begin").invoke(null);
        builder = findMethod(builderClass, "setId", String.class)
                .invoke(builder, DISPLAY_ID);
        builder = findMethod(builderClass, "setName", Component.class)
                .invoke(builder, Component.translatable("gui.xaero_infodisplay_weather"));
        builder = findMethod(builderClass, "setDefaultState", Boolean.class)
                .invoke(builder, Boolean.FALSE);
        builder = findMethod(builderClass, "setCodec", loadFieldType(BUILT_IN_IO_CODECS, "BOOLEAN"))
                .invoke(builder, loadField(BUILT_IN_IO_CODECS, "BOOLEAN"));
        builder = findMethod(builderClass, "setWidgetFactory", loadFieldType(WIDGET_FACTORIES, "OFF_ON"))
                .invoke(builder, loadField(WIDGET_FACTORIES, "OFF_ON"));
        builder = findMethod(builderClass, "setCompiler", onCompileClass)
                .invoke(builder, createCompilerProxy(onCompileClass, infoDisplayClass));
        builder = findMethod(builderClass, "setDestination", Consumer.class)
                .invoke(builder, createDestinationProxy(managerClass, manager));
        findMethod(builderClass, "build").invoke(builder);

        Object installed = findMethod(managerClass, "get", String.class).invoke(manager, DISPLAY_ID);
        if (installed == null) {
            return false;
        }
        // InfoDisplayManager.add 只有在 id 是全新的时才调用 setManager；这里是替换，必须自己补，
        // 否则 getEffectiveState() 会抛 IllegalStateException。
        findMethod(infoDisplayClass, "setManager", loadWith(loader, INFO_DISPLAY_MANAGER))
                .invoke(installed, manager);
        installedDisplay = installed;
        return true;
    }

    /** 编译器接口的实现：读本模组自己的目标状态，而不是客户端平滑后的雨量。 */
    private static Object createCompilerProxy(Class<?> onCompileClass, Class<?> infoDisplayClass) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "localizedrain$xaeroWeatherCompiler";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            if (args != null && args.length >= 2) {
                Object display = args[0];
                Object compiler = args[1];
                Object state = findMethod(infoDisplayClass, "getEffectiveState").invoke(display);
                if (Boolean.TRUE.equals(state)) {
                    Component line = null;
                    if (ClientWeatherTargets.isThundering()) {
                        line = Component.translatable("gui.xaero_weather_thundering");
                    } else if (ClientWeatherTargets.isRaining()) {
                        line = Component.translatable("gui.xaero_weather_raining");
                    }
                    if (line != null) {
                        findMethod(compiler.getClass(), "addLine", line.getClass()).invoke(compiler, line);
                    }
                }
            }
            return null;
        };
        return Proxy.newProxyInstance(onCompileClass.getClassLoader(), new Class<?>[] {onCompileClass}, handler);
    }

    /** Builder 的 destination：把构造好的显示交给 InfoDisplayManager.add。 */
    private static Consumer<Object> createDestinationProxy(Class<?> managerClass, Object manager) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "localizedrain$xaeroWeatherDestination";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            if ("accept".equals(method.getName()) && args != null && args.length == 1) {
                findMethod(managerClass, "add", args[0].getClass()).invoke(manager, args[0]);
            }
            return null;
        };
        return (Consumer<Object>) Proxy.newProxyInstance(
                XaeroWeatherCompat.class.getClassLoader(), new Class<?>[] {Consumer.class}, handler);
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    private static Class<?> loadWith(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static Class<?> loadFieldType(String owner, String fieldName) throws Exception {
        return Class.forName(owner).getField(fieldName).getType();
    }

    private static Object loadField(String owner, String name) throws Exception {
        return Class.forName(owner).getField(name).get(null);
    }

    /** 按名字和参数类型找 public 方法，类型用 isAssignableFrom 匹配，避免依赖具体实现类。 */
    private static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != parameterTypes.length) {
                continue;
            }
            Class<?>[] actual = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < actual.length; i++) {
                if (!actual[i].isAssignableFrom(parameterTypes[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "#" + name);
    }

    private static Method findMethod(Class<?> owner, String name) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "#" + name);
    }
}
