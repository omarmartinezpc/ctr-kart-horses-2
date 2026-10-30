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

    /** Eje trasero del kart (centro de las ruedas de atras): el caballito gira alrededor de el. Bloques desde el centro. */
    public static final double REAR_AXLE_FWD = -0.3469;
    public static final double REAR_AXLE_UP = 0.21875;

    private KartSeat() {}

    /** Gira un punto (delante, arriba) del kart "phiDeg" grados con el morro hacia arriba, alrededor del eje trasero. */
    public static double[] pitch(double fwd, double up, double phiDeg) {
        if (phiDeg == 0.0) {
            return new double[] {fwd, up};
        }
        double a = Math.toRadians(phiDeg);
        double c = Math.cos(a);
        double s = Math.sin(a);
        double rf = fwd - REAR_AXLE_FWD;
        double ru = up - REAR_AXLE_UP;
        return new double[] {REAR_AXLE_FWD + rf * c - ru * s, REAR_AXLE_UP + rf * s + ru * c};
    }

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
        double wheelie = 0.0;
        if (vehicle.level().isClientSide()) {
            KartState s = KartTracker.get(vehicle.getId());
            if (s == null) {
                return null;
            }
            flip = s.flip;
            wheelie = s.wheelie;
        } else {
            int c = KartMod.serverClass(vehicle);
            if (c < 0) {
                return null;
            }
            flip = c >= KartClass.FLIP_BASE;
        }
        // "delante" del modelo (con kart_flip el modelo mira al reves que el caballo)
        double rad = Math.toRadians(vehicle.getYRot() + (flip ? 180.0 : 0.0));
        double[] p = pitch(-SEAT_BACK, SEAT_UP, wheelie); // el asiento va detras del centro; el caballito lo levanta
        return new Vec3(-Math.sin(rad) * p[0], p[1], Math.cos(rad) * p[0]);
    }
}
