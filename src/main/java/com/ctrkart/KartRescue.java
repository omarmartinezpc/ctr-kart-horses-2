package com.ctrkart;

import com.ctrkart.mixin.LivingEntityAccessor;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.animal.equine.AbstractHorse; // si no existe: ...animal.horse.AbstractHorse
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * RESCATE (cliente): animacion que coloca al kart en el ultimo checkpoint. La inicia el servidor (KartRace).
 *  0) HOVER: el kart se queda quieto; particulas arcoiris bajan del cielo y giran alrededor.
 *  1) LIFT : las particulas se llevan al kart al cielo.
 *  2) WAIT : espera a que el servidor lo coloque sobre el destino (RESCUE_WARP).
 *  3) DESCEND: baja despacio hacia el centro del checkpoint. Mantener ESPACIO carga la barra del Sacred Fire (3 barras).
 *  4) LANDED : aterriza; el servidor avisa del fin (RESCUE_END) y se cobra la barra cargada.
 * Tambien guarda el estado de la carrera (vueltas / checkpoint) para el texto de la barra de accion.
 */
public final class KartRescue {
    public static boolean active;
    public static int raceLap;
    public static int raceIdx;
    public static int raceTotal;
    public static boolean raceStarted;

    static final double LIFT_SPEED = 1.0;     // bloques por tick al subir (30 ticks = 30 bloques)
    static final double DESCEND_SPEED = 0.3;  // bloques por tick al bajar
    static final int PHASE_HOVER = 0;
    static final int PHASE_LIFT = 1;
    static final int PHASE_WAIT = 2;
    static final int PHASE_DESCEND = 3;
    static final int PHASE_LANDED = 4;

    private static int phase;
    private static int t;
    private static int charge;    // carga de la barra (0..3 barras): sube con ESPACIO mantenido y baja al soltarlo
    private static int lastBars;
    private static int endWait;
    public static int horseId;
    private static double ty;
    private static float yaw;

    private KartRescue() {}

    public static void start(AbstractHorse horse, double tx, double ty, double tz, float yawDeg) {
        active = true;
        phase = PHASE_HOVER;
        t = 0;
        charge = 0;
        lastBars = 0;
        endWait = 0;
        horseId = horse.getId();
        KartRescue.ty = ty;
        yaw = yawDeg;
        horse.noPhysics = true;
        KartState st = KartTracker.get(horse.getId());
        if (st != null) {
            KartPhysics.loseBoosts(st); // al ser rescatado se pierde todo
        }
        KartPhysics.sfx(horse, SoundEvents.BEACON_ACTIVATE, 1.0f, 1.4f);
    }

    /** El servidor ya coloco al kart en el cielo sobre el destino: empieza la bajada. */
    public static void warped(AbstractHorse horse) {
        if (active) {
            phase = PHASE_DESCEND;
            KartPhysics.sfx(horse, SoundEvents.ENDERMAN_TELEPORT, 0.8f, 1.2f);
        }
    }

    /** Fin del rescate (aviso del servidor, o por seguridad). */
    public static void end(AbstractHorse horse, KartState s) {
        if (!active) {
            return;
        }
        active = false;
        horse.noPhysics = false;
        if (s != null) {
            KartPhysics.rescueLanding(horse, s, bars(), yaw);
        }
        horse.setYRot(yaw);
        KartPhysics.sfx(horse, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.9f, 1.2f);
    }

    /** Se cancela (te bajaste del kart en pleno rescate). */
    public static void abort(AbstractHorse horse) {
        active = false;
        if (horse != null) {
            horse.noPhysics = false;
        }
    }

    /** Un tick de la animacion. Devuelve true si este tick lo gasto la animacion (la fisica normal no corre). */
    static boolean tick(AbstractHorse horse, Player rider, KartState s) {
        if (!active || horse.getId() != horseId) {
            return false;
        }
        t++;
        s.lastTick = KartTracker.clientTick; // que la fisica no se reinicie al terminar
        s.hasLast = false;
        s.speed = 0;
        s.drifting = false;
        s.moveYaw = yaw;
        // sin colision durante todo el rescate, hasta estar a 1 bloque de altura del bloque de Rescate
        horse.noPhysics = !(phase >= PHASE_DESCEND && horse.getY() - ty <= KartRace.RESCUE_COLLIDE_HEIGHT);
        horse.setYRot(yaw);
        boolean jump = ((LivingEntityAccessor) rider).ctrkart$isJumping();

        double vy = 0;
        switch (phase) {
            case PHASE_HOVER:
                if (t >= KartRace.RESCUE_HOVER_TICKS) {
                    phase = PHASE_LIFT;
                }
                break;
            case PHASE_LIFT:
                vy = LIFT_SPEED;
                if (t >= KartRace.RESCUE_HOVER_TICKS + KartRace.RESCUE_LIFT_TICKS) {
                    phase = PHASE_WAIT;
                }
                break;
            case PHASE_WAIT:
                if (t > 400) { // el servidor no contesto: se termina
                    end(horse, s);
                    return true;
                }
                break;
            case PHASE_DESCEND:
                // la barra se llena mientras mantienes ESPACIO y se vacia (mas rapido) al soltarlo
                if (jump) {
                    charge = Math.min(3 * KartPhysics.TIER_TICKS, charge + 1);
                } else {
                    charge = Math.max(0, charge - 2);
                }
                if (bars() > lastBars) {
                    KartPhysics.sfx(horse, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 0.8f + 0.3f * bars());
                }
                lastBars = bars();
                vy = -Math.min(DESCEND_SPEED, Math.max(0.0, horse.getY() - ty));
                if (horse.getY() - ty <= 1.0e-3) {
                    phase = PHASE_LANDED;
                    endWait = 0;
                }
                break;
            default: // LANDED
                if (++endWait > 60) {
                    end(horse, s);
                    return true;
                }
                break;
        }
        horse.setDeltaMovement(0, vy, 0);
        particles(horse);
        hud();
        return true;
    }

    /** Barras completas (0..3): decide el turbo al aterrizar (1 rojo, 2 amarillo, 3 Sacred Fire; 0 = nada). */
    private static int bars() {
        return Math.min(3, charge / KartPhysics.TIER_TICKS);
    }

    /** Un tramo de la barra: vacio, lleno (con su color) o a medias (se va llenando poco a poco). */
    private static String segment(int i, String color) {
        int c = charge - i * KartPhysics.TIER_TICKS;
        if (c <= 0) {
            return "§8▯";
        }
        if (c >= KartPhysics.TIER_TICKS) {
            return color + "▮";
        }
        String[] partial = {"▏", "▎", "▍", "▌", "▋", "▊", "▉"};
        return color + partial[Math.min(partial.length - 1, c * partial.length / KartPhysics.TIER_TICKS)];
    }

    private static void hud() {
        StringBuilder sb = new StringBuilder("§d§lRESCATE§r");
        if (phase >= PHASE_DESCEND) {
            sb.append(" §7| §fMantén ESPACIO §7[ ")
                    .append(segment(0, "§c")).append(segment(1, "§e")).append(segment(2, "§9"))
                    .append(" §7]");
        } else {
            sb.append(" §7| §8prepara ESPACIO para la bajada");
        }
        KartPhysics.HUD.accept(Component.literal(sb.toString()));
    }

    private static DustParticleOptions rainbow(double h, float size) {
        double x = (h % 1.0 + 1.0) % 1.0 * 6.0;
        int i = (int) Math.floor(x);
        double f = x - i;
        double r;
        double g;
        double b;
        switch (i % 6) {
            case 0: r = 1; g = f; b = 0; break;
            case 1: r = 1 - f; g = 1; b = 0; break;
            case 2: r = 0; g = 1; b = f; break;
            case 3: r = 0; g = 1 - f; b = 1; break;
            case 4: r = f; g = 0; b = 1; break;
            default: r = 1; g = 0; b = 1 - f; break;
        }
        int rgb = ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
        return new DustParticleOptions(rgb, size);
    }

    private static void particles(AbstractHorse horse) {
        Level level = horse.level();
        Vec3 c = horse.position();
        switch (phase) {
            case PHASE_HOVER: {
                // columnas de colores que bajan del cielo girando alrededor del kart
                double f0 = (double) t / KartRace.RESCUE_HOVER_TICKS;
                for (int k = 0; k < 8; k++) {
                    double f = (t * 0.06 + k / 8.0) % 1.0;           // 1 = arriba del todo, 0 = a la altura del kart
                    double ang = t * 0.30 + k * 0.785 + f * 5.0;
                    double r = 1.7 - 0.5 * f0;
                    double y = c.y + 0.3 + 11.0 * f;
                    level.addParticle(rainbow(k / 8.0 + t * 0.02, 1.4f),
                            c.x + Math.cos(ang) * r, y, c.z + Math.sin(ang) * r, 0, 0, 0);
                }
                for (int k = 0; k < 4; k++) { // anillo a la altura del kart
                    double ang = -t * 0.45 + k * 1.571;
                    level.addParticle(rainbow(k / 4.0 + t * 0.03, 1.2f),
                            c.x + Math.cos(ang) * 1.2, c.y + 0.6 + 0.2 * Math.sin(t * 0.3 + k), c.z + Math.sin(ang) * 1.2, 0, 0, 0);
                }
                break;
            }
            case PHASE_LIFT:
            case PHASE_WAIT: {
                for (int k = 0; k < 10; k++) {
                    double ang = t * 0.5 + k * 0.628;
                    double r = 1.2 + 0.3 * Math.sin(t * 0.4 + k);
                    level.addParticle(rainbow(k / 10.0 + t * 0.03, 1.5f),
                            c.x + Math.cos(ang) * r, c.y + (k % 5) * 0.45 - 0.6, c.z + Math.sin(ang) * r, 0, 0, 0);
                }
                break;
            }
            default: {
                for (int k = 0; k < 6; k++) {
                    double ang = t * 0.25 + k * 1.047;
                    level.addParticle(rainbow(k / 6.0 + t * 0.02, 1.4f),
                            c.x + Math.cos(ang) * 1.5, c.y + 0.2 + k * 0.4, c.z + Math.sin(ang) * 1.5, 0, 0, 0);
                }
                if (phase == PHASE_DESCEND && charge > 0 && t % 2 == 0) { // al cargar: chispas
                    level.addParticle(ParticleTypes.ELECTRIC_SPARK, c.x, c.y + 0.4, c.z, 0, 0.05, 0);
                }
                break;
            }
        }
    }
}
