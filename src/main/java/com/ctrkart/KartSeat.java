package com.ctrkart;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse; // si no existe: ...animal.horse.AbstractHorse
import net.minecraft.world.phys.Vec3;

/**
 * Donde se sienta el jugador en el kart y de que tamano va. Todo se mide en bloques desde el centro
 * del caballo (que es el centro del modelo del kart). Ajusta estos numeros si quieres moverlo.
 */
public final class KartSeat {
    /** Altura de la superficie del asiento sobre el suelo (el asiento del modelo esta a 5,2/16 de bloque). */
    public static final double SEAT_UP = 0.34;
    /** Cuanto hacia ATRAS del centro del kart se sienta el jugador (el asiento esta algo detras del centro). */
    public static final double SEAT_BACK = 0.12;
    /** Tamano del jugador mientras conduce (1.0 = normal). */
    public static final double RIDER_SCALE = 0.5;

    private KartSeat() {}

    /** Vale en cliente (por KartTracker) y en servidor (por las etiquetas), que son quienes colocan al jugador. */
    public static boolean isKart(Entity e) {
        if (!(e instanceof AbstractHorse)) {
            return false;
        }
        if (e.level().isClientSide()) {
            return KartTracker.get(e.getId()) != null;
        }
        return KartMod.serverClass(e) >= 0;
    }

    /** Desplazamiento del punto de asiento respecto al caballo, o null si no es un kart. */
    public static Vec3 seatOffset(Entity vehicle) {
        boolean flip;
        if (vehicle.level().isClientSide()) {
            KartState s = KartTracker.get(vehicle.getId());
            if (s == null) {
                return null;
            }
            flip = s.flip;
        } else {
            int c = KartMod.serverClass(vehicle);
            if (c < 0) {
                return null;
            }
            flip = c >= KartClass.FLIP_BASE;
        }
        double rad = Math.toRadians(vehicle.getYRot());
        double dir = flip ? -1.0 : 1.0;   // hacia atras del kart (con kart_flip, al reves)
        return new Vec3(Math.sin(rad) * dir * SEAT_BACK, SEAT_UP, -Math.cos(rad) * dir * SEAT_BACK);
    }
}
