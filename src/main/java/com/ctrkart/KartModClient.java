package com.ctrkart;

import java.lang.reflect.Method;
import com.ctrkart.client.KartVisuals;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class KartModClient implements ClientModInitializer {
    private static Method overlayMethod;
    private static Object overlayTarget; // "gui" o el jugador, segun donde exista el metodo
    private static boolean searched;

    @Override
    public void onInitializeClient() {
        KartPhysics.HUD = KartModClient::showOverlay;
        ClientTickEvents.END_CLIENT_TICK.register(client -> KartVisuals.tick(client));
        ClientPlayNetworking.registerGlobalReceiver(KartSyncPayload.TYPE,
                (payload, context) -> KartTracker.apply(payload)); // Fabric ya lo ejecuta en el hilo principal
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> KartTracker.clear());
    }

    /** Escribe en la barra de accion. El nombre del metodo cambio en 26.x, asi que se busca por reflexion. */
    private static void showOverlay(Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (!searched) {
            searched = true;
            Object[] targets = {mc.gui, mc.player};
            for (Object t : targets) {
                if (t == null) {
                    continue;
                }
                Method m = findOverlay(t.getClass());
                if (m != null) {
                    overlayMethod = m;
                    overlayTarget = t == mc.gui ? mc.gui : null;
                    break;
                }
            }
            if (overlayMethod == null) {
                System.err.println("[ctrkart] No se encontro el metodo de la barra de accion; el HUD de texto no se mostrara");
            }
        }
        if (overlayMethod == null) {
            return;
        }
        try {
            Object target = overlayTarget != null ? mc.gui
                    : (overlayMethod.getDeclaringClass().isInstance(mc.player) ? mc.player : null);
            if (target == null) {
                return;
            }
            if (overlayMethod.getParameterCount() == 2) {
                overlayMethod.invoke(target, text, false);
            } else {
                overlayMethod.invoke(target, text);
            }
        } catch (ReflectiveOperationException ignored) {
            // sin HUD, el mod sigue funcionando
        }
    }

    private static Method findOverlay(Class<?> cls) {
        for (Method m : cls.getMethods()) {
            String n = m.getName().toLowerCase();
            Class<?>[] p = m.getParameterTypes();
            boolean nameOk = n.contains("overlay") && n.contains("message");
            boolean paramsOk = p.length >= 1 && p[0] == Component.class
                    && (p.length == 1 || (p.length == 2 && p[1] == boolean.class));
            if (nameOk && paramsOk) {
                return m;
            }
        }
        return null;
    }
}
