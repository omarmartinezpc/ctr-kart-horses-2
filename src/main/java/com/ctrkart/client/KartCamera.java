package com.ctrkart.client;

import com.ctrkart.KartConfig;
import com.ctrkart.KartState;
import com.ctrkart.KartTracker;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.equine.AbstractHorse; // si no existe: ...animal.horse.AbstractHorse
import net.minecraft.world.entity.player.Player;

/**
 * Camara y cabeza del jugador mientras conduce un kart (solo cliente):
 *  - Siempre: la CAMARA es libre (no se limita). La CABEZA del modelo del jugador ya no va ligada a la camara:
 *    mira hacia donde va el kart (su trayectoria), con un poco de giro extra hacia el lado al que giras con A/D.
 *  - Con la opcion "camara_ctr": tercera persona fija detras del kart, que sigue su trayectoria con un poco
 *    de retraso (como en CTR Nitro-Fueled). Se pone sola al montar y se devuelve al bajar.
 * El cambio de tipo de camara se hace por reflexion (los nombres pueden cambiar entre versiones).
 */
public final class KartCamera {
    /** Cuanto gira la cabeza (en grados) hacia el lado al que giras con A/D, ademas de mirar a la trayectoria. 0 = mirar recto. */
    private static final float HEAD_STEER_DEG = 20f;
    /** Lo rapido que la cabeza alcanza su direccion (0..1 por tick; 1 = al instante). */
    private static final float HEAD_FOLLOW = 0.35f;
    /** Inclinacion de la camara CTR (grados hacia abajo) y lo rapido que sigue al kart (0..1 por tick). */
    private static final float CAM_PITCH = 14f;
    private static final float CAM_FOLLOW = 0.18f;

    private static boolean searched;
    private static Method getType;
    private static Method setType;
    private static Object previousType;
    private static boolean wasOnKart;
    private static KartState lastKart;

    private KartCamera() {}

    public static void tick(Minecraft mc) {
        Player p = mc.player;
        AbstractHorse horse = null;
        KartState st = null;
        if (p != null && p.getVehicle() instanceof AbstractHorse h) {
            st = KartTracker.get(h.getId());
            if (st != null) {
                horse = h;
            }
        }
        boolean onKart = horse != null;
        if (onKart && !wasOnKart) {
            st.headInit = false; // la cabeza se coloca de golpe al montar
            if (KartConfig.ctrCamera) {
                enterChase(mc);
            }
        } else if (!onKart && wasOnKart) {
            leaveChase(mc);
            if (lastKart != null) {
                lastKart.needsReset = true; // al volver a montar la fisica empieza de cero
            }
            if (p != null) { // la cabeza vuelve a seguir a la camara
                p.yHeadRot = p.getYRot();
                p.yHeadRotO = p.getYRot();
            }
        }
        if (onKart && wasOnKart && lastKart != null && lastKart != st) { // pasaste de un kart a otro
            lastKart.needsReset = true;
            st.headInit = false;
        }
        wasOnKart = onKart;
        lastKart = onKart ? st : null;
        if (!onKart) {
            return;
        }

        if (KartConfig.ctrCamera && isThirdPersonBack(mc)) {
            // camara fija: la vista se coloca detras de la trayectoria del kart
            float cur = p.getYRot();
            float yaw = cur + Mth.wrapDegrees(st.moveYaw - cur) * CAM_FOLLOW;
            p.setYRot(yaw);
            p.setXRot(Mth.lerp(0.2f, p.getXRot(), CAM_PITCH));
        }
        // Con camara libre no se toca la vista: puedes mirar a donde quieras.
        if (!st.needsReset) { // si la fisica aun no ha corrido en este kart, no hay trayectoria que seguir todavia
            updateHead(p, st);
        }
    }

    /**
     * La cabeza del MODELO del jugador mira hacia donde va el kart, sin depender de la camara. Se escribe al final
     * de cada tick de cliente (despues de que el juego ponga la cabeza donde mira la camara), asi lo que se dibuja
     * entre ticks es nuestra direccion. La rotacion de la CAMARA es yRot, que aqui no se toca.
     */
    private static void updateHead(Player p, KartState st) {
        float target = st.moveYaw + st.steerAnim * HEAD_STEER_DEG;
        float prev = st.headYaw;
        if (!st.headInit) {
            st.headYaw = target;
            prev = target;
            st.headInit = true;
        } else {
            st.headYaw += Mth.wrapDegrees(target - st.headYaw) * HEAD_FOLLOW;
        }
        p.yHeadRotO = prev;
        p.yHeadRot = st.headYaw;
    }

    private static void find(Minecraft mc) {
        searched = true;
        for (Method m : mc.options.getClass().getMethods()) {
            if (m.getName().equals("getCameraType") && m.getParameterCount() == 0) {
                getType = m;
            } else if (m.getName().equals("setCameraType") && m.getParameterCount() == 1) {
                setType = m;
            }
        }
        if (getType == null || setType == null) {
            System.err.println("[ctrkart] Camara: no encontre getCameraType/setCameraType; pasa a tercera persona con F5");
        }
    }

    private static boolean isThirdPersonBack(Minecraft mc) {
        if (!searched) {
            find(mc);
        }
        if (getType == null) {
            return true; // sin forma de saberlo, se asume que si
        }
        try {
            Object t = getType.invoke(mc.options);
            return t instanceof Enum<?> e && e.name().equals("THIRD_PERSON_BACK");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return true;
        }
    }

    private static void enterChase(Minecraft mc) {
        if (!searched) {
            find(mc);
        }
        if (getType == null || setType == null) {
            return;
        }
        try {
            previousType = getType.invoke(mc.options);
            setCameraType(mc, "THIRD_PERSON_BACK");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            System.err.println("[ctrkart] Camara: no pude cambiar a tercera persona: " + ex);
        }
    }

    private static void leaveChase(Minecraft mc) {
        if (previousType instanceof Enum<?> e && setType != null) {
            try {
                setCameraType(mc, e.name());
            } catch (ReflectiveOperationException | RuntimeException ex) {
                // no es grave
            }
        }
        previousType = null;
    }

    private static void setCameraType(Minecraft mc, String name) throws ReflectiveOperationException {
        for (Object c : setType.getParameterTypes()[0].getEnumConstants()) {
            if (c instanceof Enum<?> e && e.name().equals(name)) {
                setType.invoke(mc.options, c);
                return;
            }
        }
    }
}
