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
 *  - Siempre: la cabeza no puede girar mas de HEAD_LIMIT_DEG grados a cada lado respecto al kart.
 *  - Con la opcion "camara_ctr": tercera persona fija detras del kart, que sigue su trayectoria con un poco
 *    de retraso (como en CTR Nitro-Fueled). Se pone sola al montar y se devuelve al bajar.
 * El cambio de tipo de camara se hace por reflexion (los nombres pueden cambiar entre versiones).
 */
public final class KartCamera {
    /** Giro maximo de la cabeza a izquierda y derecha respecto al kart, en grados (antes: unos 105). */
    private static final float HEAD_LIMIT_DEG = 50f;
    /** Inclinacion de la camara CTR (grados hacia abajo) y lo rapido que sigue al kart (0..1 por tick). */
    private static final float CAM_PITCH = 14f;
    private static final float CAM_FOLLOW = 0.18f;

    private static boolean searched;
    private static Method getType;
    private static Method setType;
    private static Object previousType;
    private static boolean wasOnKart;

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
        if (onKart && !wasOnKart && KartConfig.ctrCamera) {
            enterChase(mc);
        } else if (!onKart && wasOnKart) {
            leaveChase(mc);
        }
        wasOnKart = onKart;
        if (!onKart) {
            return;
        }

        if (KartConfig.ctrCamera && isThirdPersonBack(mc)) {
            // camara fija: la vista se coloca detras de la trayectoria del kart
            float cur = p.getYRot();
            float yaw = cur + Mth.wrapDegrees(st.moveYaw - cur) * CAM_FOLLOW;
            p.setYRot(yaw);
            p.setYHeadRot(yaw);
            p.setXRot(Mth.lerp(0.2f, p.getXRot(), CAM_PITCH));
        } else {
            // cabeza: rango corto a izquierda y derecha
            float hy = horse.getYRot();
            float rel = Mth.wrapDegrees(p.getYRot() - hy);
            if (rel > HEAD_LIMIT_DEG || rel < -HEAD_LIMIT_DEG) {
                float yaw = hy + Mth.clamp(rel, -HEAD_LIMIT_DEG, HEAD_LIMIT_DEG);
                p.setYRot(yaw);
                p.setYHeadRot(yaw);
            }
        }
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
