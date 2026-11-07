package com.ctrkart;

import java.lang.reflect.Method;
import com.ctrkart.client.KartCamera;
import com.ctrkart.client.KartClientCommands;
import com.ctrkart.client.KartCpClient;
import com.ctrkart.client.KartRenderLayers;
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
        KartConfig.load();
        KartPhysics.HUD = KartModClient::showOverlay;
        // click izquierdo (para el Sacred Fire manual del manejo clasico). Al abrir un menu el juego suelta las teclas solo.
        KartPhysics.ATTACK = KartModClient::attackDown;
        KartPhysics.ATTACK_CLICKS = KartModClient::consumeAttackClicks;
        KartClientCommands.register();
        KartRenderLayers.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            KartTracker.clientTick++;
            KartVisuals.tick(client);
            KartCamera.tick(client);
            KartCpClient.tick(client);
        });
        ClientPlayNetworking.registerGlobalReceiver(KartSyncPayload.TYPE,
                (payload, context) -> KartTracker.apply(payload)); // Fabric ya lo ejecuta en el hilo principal
        ClientPlayNetworking.registerGlobalReceiver(KartEnergyPayload.TYPE,
                (payload, context) -> KartTracker.addEnergy(payload.entityId(), payload.amount()));
        ClientPlayNetworking.registerGlobalReceiver(KartTpPayload.TYPE, (payload, context) -> handleTp(payload));
        ClientPlayNetworking.registerGlobalReceiver(KartCpPayload.TYPE, (payload, context) -> KartCpClient.handle(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            KartTracker.clear();
            KartCpClient.clear();
        });
    }

    /** Mensajes y teletransporte de los bloques TP (ver KartTeleport). */
    private static void handleTp(KartTpPayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (p.kind() == KartTpPayload.SET_ENTRY) {
            showOverlay(Component.literal("§bTP de Entrada §fnumero §l" + p.channel()));
            return;
        }
        if (p.kind() == KartTpPayload.SET_EXIT) {
            showOverlay(Component.literal("§1TP de Salida §fnumero §l" + p.channel()));
            return;
        }
        net.minecraft.world.entity.Entity e = mc.level.getEntity(p.entityId());
        if (!(e instanceof net.minecraft.world.entity.animal.equine.AbstractHorse horse)) {
            return;
        }
        KartPhysics.tpEffect(horse); // en el origen
        moveKart(horse, p.x(), p.y(), p.z());
        KartState st = KartTracker.get(horse.getId());
        if (st != null) {
            KartPhysics.onTeleported(st, p.y()); // reservas, turbo, velocidad y direccion NO se tocan
        }
        KartPhysics.tpEffect(horse); // en el destino
    }

    /** Coloca al kart (y al jugador que lo conduce) en otro sitio sin que la imagen "viaje" entre los dos puntos. */
    public static void moveKart(net.minecraft.world.entity.animal.equine.AbstractHorse horse, double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        double dx = x - horse.getX();
        double dy = y - horse.getY();
        double dz = z - horse.getZ();
        horse.setPos(x, y, z);
        horse.setOldPosAndRot();
        net.minecraft.world.entity.player.Player player = mc.player;
        if (player != null && player.getVehicle() == horse) {
            player.setPos(player.getX() + dx, player.getY() + dy, player.getZ() + dz);
            player.setOldPosAndRot();
        }
    }

    private static long windowHandle;

    /** Click izquierdo mantenido: por la tecla de "atacar" del juego O leyendo el boton del raton directamente (GLFW). */
    private static boolean attackDown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.keyAttack.isDown()) {
            return true;
        }
        return glfwLeftPressed(mc);
    }

    private static Method glfwGetMouseButton;
    private static boolean glfwSearched;

    /** Lee el boton izquierdo del raton con GLFW por reflexion (la libreria no esta disponible al compilar, pero si al jugar). */
    private static boolean glfwLeftPressed(Minecraft mc) {
        long h = glfwHandle(mc);
        if (h == 0L) {
            return false;
        }
        if (!glfwSearched) {
            glfwSearched = true;
            try {
                glfwGetMouseButton = Class.forName("org.lwjgl.glfw.GLFW")
                        .getMethod("glfwGetMouseButton", long.class, int.class);
            } catch (ReflectiveOperationException ignored) {
                // sin esta via, queda la tecla de atacar del juego
            }
        }
        if (glfwGetMouseButton == null) {
            return false;
        }
        try {
            Object r = glfwGetMouseButton.invoke(null, h, 0); // 0 = boton izquierdo; 1 = pulsado
            return r instanceof Integer i && i == 1;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    /** Pulsaciones del click izquierdo que el juego aun no habia gastado (cubre clicks muy rapidos entre ticks). */
    private static boolean consumeAttackClicks() {
        boolean any = false;
        while (Minecraft.getInstance().options.keyAttack.consumeClick()) {
            any = true;
        }
        return any;
    }

    /** El numero de la ventana de GLFW: el metodo que lo da se llama distinto segun la version, asi que se busca. */
    private static long glfwHandle(Minecraft mc) {
        if (windowHandle != 0L) {
            return windowHandle;
        }
        try {
            Object w = mc.getWindow();
            for (Method m : w.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == long.class) {
                    long v = (long) m.invoke(w);
                    if (v != 0L) {
                        windowHandle = v;
                        break;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // sin esta via, queda la tecla de atacar del juego
        }
        return windowHandle;
    }

    /** Escribe en la barra de accion. El nombre del metodo cambio en 26.x, asi que se busca por reflexion. */
    public static void showOverlay(Component text) {
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
