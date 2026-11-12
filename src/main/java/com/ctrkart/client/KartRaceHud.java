package com.ctrkart.client;

import com.ctrkart.KartRescue;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.Identifier;

/**
 * Puesto de la carrera (por ejemplo "3/6") arriba a la derecha de la pantalla.
 *
 * Como la API de interfaz cambia mucho entre versiones (y en 26.x no esta verificada), todo se hace por reflexion:
 *  - se registra un elemento del HUD de Fabric (HudElementRegistry.addLast) o, si no existe, HudRenderCallback;
 *  - el texto se dibuja con el metodo del objeto grafico que tenga la firma (Font, String, int, int, int[, boolean]).
 * Si algo falla se avisa UNA vez en el log ([ctrkart] Puesto) y el puesto sigue saliendo en la barra de accion (KartPhysics).
 */
public final class KartRaceHud {
    /** true cuando el HUD ya dibujo el puesto al menos una vez (entonces la barra de accion no lo repite). */
    public static volatile boolean drew;

    private static boolean warned;
    private static Method drawMethod;
    private static boolean drawWithShadowFlag;

    private KartRaceHud() {}

    public static void register() {
        try {
            Class<?> reg = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry");
            Class<?> element = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement");
            Object proxy = proxyFor(element);
            Method add = reg.getMethod("addLast", Identifier.class, element);
            add.invoke(null, Identifier.fromNamespaceAndPath("ctrkart", "race_position"), proxy);
            return;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            // probamos la forma antigua
        }
        try {
            Class<?> cb = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback");
            Object event = cb.getField("EVENT").get(null);
            Object proxy = proxyFor(cb);
            for (Method m : event.getClass().getMethods()) {
                if (m.getName().equals("register") && m.getParameterCount() == 1) {
                    m.invoke(event, proxy);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            // sin HUD
        }
        warn("no encontre la API del HUD de Fabric; el puesto saldra en la barra de accion");
    }

    private static Object proxyFor(Class<?> iface) {
        InvocationHandler h = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return "KartRaceHud";
                }
            }
            if (args != null && args.length >= 1 && args[0] != null && !method.isDefault()) {
                try {
                    draw(args[0]);
                } catch (RuntimeException ex) {
                    warn("error al dibujar: " + ex);
                }
            }
            return null;
        };
        return Proxy.newProxyInstance(KartRaceHud.class.getClassLoader(), new Class<?>[] {iface}, h);
    }

    private static void warn(String msg) {
        if (!warned) {
            warned = true;
            System.err.println("[ctrkart] Puesto: " + msg);
        }
    }

    private static void draw(Object g) {
        if (!KartRescue.raceStarted || KartRescue.racePlace <= 0 || KartRescue.raceRacers <= 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { // (con F1 el HUD completo ya no se dibuja solo)
            return;
        }
        Font font = mc.font;
        String color = KartRescue.racePlace == 1 ? "§6" : KartRescue.racePlace == 2 ? "§f" : KartRescue.racePlace == 3 ? "§c" : "§7";
        String text = "§7Puesto " + color + "§l" + KartRescue.racePlace + "§r§7/" + KartRescue.raceRacers;
        int x = mc.getWindow().getGuiScaledWidth() - font.width(text) - 6;
        int y = 6;
        if (drawMethod == null && !findDrawMethod(g, font)) {
            warn("no encontre como dibujar texto en esta version");
            return;
        }
        try {
            if (drawWithShadowFlag) {
                drawMethod.invoke(g, font, text, x, y, 0xFFFFFFFF, true);
            } else {
                drawMethod.invoke(g, font, text, x, y, 0xFFFFFFFF);
            }
            drew = true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            drawMethod = null;
            drew = false;
            warn("no pude dibujar el texto: " + ex);
        }
    }

    /** Busca en el objeto grafico un metodo (Font, String, int, int, int[, boolean]): drawString / text / ... */
    private static boolean findDrawMethod(Object g, Font font) {
        Method best = null;
        boolean bestShadow = false;
        for (Method m : g.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            boolean shadow = p.length == 6 && p[5] == boolean.class;
            if (!(p.length == 5 || shadow) || !p[0].isAssignableFrom(font.getClass()) || p[1] != String.class
                    || p[2] != int.class || p[3] != int.class || p[4] != int.class) {
                continue;
            }
            String n = m.getName();
            boolean known = n.equals("drawString") || n.equals("text") || n.equals("drawText");
            if (best == null || (known && shadow)) {
                best = m;
                bestShadow = shadow;
            }
            if (known && shadow) {
                break;
            }
        }
        if (best == null) {
            return false;
        }
        drawMethod = best;
        drawWithShadowFlag = bestShadow;
        return true;
    }
}
