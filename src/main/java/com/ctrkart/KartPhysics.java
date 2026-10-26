package com.ctrkart;

import com.ctrkart.mixin.LivingEntityAccessor;
import java.util.Locale;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.animal.equine.AbstractHorse; // si no existe: ...animal.horse.AbstractHorse
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Fisica de kart al estilo CTR Nitro-Fueled. Se ejecuta cada tick en el cliente que conduce.
 *
 * Controles:  W/S acelerar/frenar | A/D girar | ESPACIO = hop; mantenido + A/D = derrapar
 *
 * DERRAPE (automatico, sin pulsar nada mas): mientras mantienes ESPACIO girando, la barra se llena.
 *   1a barra llena = nivel 1 (rojo), 2a = nivel 2 (amarillo), 3a = SACRED FIRE (azul) al instante.
 *   Al soltar ESPACIO con nivel 1 o 2 se cobra un turbo de ese nivel.
 *
 * ENERGIA (0..100): se obtiene al lograr Sacred Fire, al caer de altura y al alimentar al caballo.
 * Alarga la duracion del Sacred Fire (la de la caida alta incluida).
 *
 * Todos los numeros son de balance propio, ajustables aqui.
 */
public final class KartPhysics {
    // Movimiento
    static final double BRAKE = 0.035;
    static final double REVERSE_MAX = 0.12;
    static final double COAST = 0.975;
    static final double HOP_VELOCITY = 0.24;
    static final double DRIFT_MIN_SPEED = 0.22;
    static final float DRIFT_ANGLE = 26f;
    // Derrape
    static final int TIER_TICKS = 12;          // ticks que tarda en llenarse cada barra (12 = 0,6 s)
    static final int MAX_TURBOS = 3;           // la 3a barra es el Sacred Fire
    static final int TURBO_TICKS_PER_LEVEL = 24;
    static final int BOOST_CAP = 120;
    static final double[] FIRE_MULT = {1.00, 1.16, 1.32, 1.50}; // sin fuego / rojo / amarillo / azul
    // Sacred Fire
    static final int SACRED_BASE_TICKS = 60;   // duracion base (3 s); despues sigue gastando energia
    static final double SACRED_MULT = 1.55;    // siempre mayor que la velocidad base y que cualquier otro turbo
    static final double SUPER_MULT = 1.30;     // Super Motor
    // Energia
    static final float ENERGY_MAX = 100f;
    static final float ENERGY_DRAIN = 0.5f;    // por tick mientras alarga el Sacred Fire (100 = 10 s)
    static final float SACRED_ENERGY = 25f;    // energia al asestar un Sacred Fire
    // Caida alta: de FALL_MIN a FALL_FULL bloques de caida, la energia va de FALL_ENERGY_MIN a FALL_ENERGY_MAX
    static final double FALL_MIN = 3.0;
    static final double FALL_FULL = 24.0;
    static final float FALL_ENERGY_MIN = 10f;
    static final float FALL_ENERGY_MAX = 100f;
    // Escape del modelo de kart (bloques, relativo al centro del kart)
    static final double EXHAUST_BACK = 0.9;
    static final double EXHAUST_SIDE = 0.20;
    static final double EXHAUST_Y = 0.25;

    private static final DustParticleOptions FIRE_RED = new DustParticleOptions(0xFF2A2A, 1.3f);
    private static final DustParticleOptions FIRE_YELLOW = new DustParticleOptions(0xFFD21E, 1.3f);
    private static final DustParticleOptions FIRE_BLUE = new DustParticleOptions(0x2F6BFF, 1.5f);

    /** Lo fija el cliente (KartModClient) para escribir en la barra de accion. */
    public static volatile java.util.function.Consumer<Component> HUD = c -> {};

    private KartPhysics() {}

    public static void tick(AbstractHorse horse, Player rider, KartState s) {
        Level level = horse.level();
        long now = level.getGameTime();
        if (now - s.lastTick > 2) { // se acaba de montar
            s.resetDynamic();
            s.moveYaw = horse.getYRot();
        }
        s.lastTick = now;

        // Choque real contra pared: solo si el kart intento avanzar y casi no se movio ni subio.
        // Asi subir bloques/escalones no quita velocidad.
        boolean blocked = false;
        if (s.hasLast) {
            double moved = Math.hypot(horse.getX() - s.lastX, horse.getZ() - s.lastZ);
            double expected = Math.abs(s.appliedSpeed);
            double risen = horse.getY() - s.lastY;
            blocked = horse.horizontalCollision && expected > 0.15 && moved < expected * 0.5 && risen < 0.05;
        }
        s.lastX = horse.getX();
        s.lastY = horse.getY();
        s.lastZ = horse.getZ();
        s.hasLast = true;

        KartClass k = s.kartClass;
        float steer = -rider.xxa;   // +1 = derecha (yaw aumenta al girar a la derecha)
        float throttle = rider.zza; // +1 = W, -1 = S
        boolean jump = ((LivingEntityAccessor) rider).ctrkart$isJumping();
        boolean jumpPressed = jump && !s.jumpHeld;
        s.jumpHeld = jump;

        if (s.superTicks > 0) {
            s.superTicks--;
        }

        // --- aire / aterrizaje (caida alta) ---
        boolean onGround = horse.onGround();
        if (!onGround) {
            s.airMaxY = s.airTicks == 0 ? horse.getY() : Math.max(s.airMaxY, horse.getY());
            s.airTicks++;
        } else {
            if (s.airTicks > 0) {
                double drop = s.airMaxY - horse.getY();
                if (drop >= FALL_MIN) {
                    highFall(horse, s, drop);
                }
                s.hopAir = false;
            }
            s.airTicks = 0;
        }

        // --- hop ---
        if (jumpPressed && onGround) {
            Vec3 dm = horse.getDeltaMovement();
            horse.setDeltaMovement(dm.x, HOP_VELOCITY, dm.z);
            s.hopAir = true;
        }

        // --- inicio de derrape: hop + girar manteniendo el salto ---
        if (!s.drifting && jump && s.hopAir && steer != 0 && s.speed >= DRIFT_MIN_SPEED) {
            s.drifting = true;
            s.driftDir = steer > 0 ? 1 : -1;
            s.charge = 0;
            s.pending = 0;
        }

        // --- derrape: la barra se llena sola; cada barra llena sube un nivel ---
        if (s.drifting) {
            if (!jump || s.speed < DRIFT_MIN_SPEED * 0.6) {
                endDrift(horse, s);
            } else if (onGround && s.pending < MAX_TURBOS) {
                s.charge++;
                if (s.charge >= TIER_TICKS) {
                    s.charge = 0;
                    s.pending++;
                    sfx(horse, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5f, 0.8f + 0.25f * s.pending);
                    if (s.pending >= MAX_TURBOS) { // 3a barra: SACRED FIRE al instante
                        startSacred(horse, s, true);
                    }
                }
            }
        }

        // --- direccion ---
        float speedFactor = (float) Mth.clamp(Math.abs(s.speed) / 0.12, 0.0, 1.0);
        float rate;
        if (s.drifting) {
            rate = k.turn * (s.driftDir * 0.95f + steer * 0.5f) * speedFactor;
        } else {
            rate = k.turn * steer * speedFactor * (s.speed < 0 ? -1f : 1f);
        }
        s.moveYaw += rate;
        float target = s.drifting ? s.driftDir * DRIFT_ANGLE : 0f;
        s.bodyOffset += (target - s.bodyOffset) * 0.35f;

        // --- velocidad: manda siempre la velocidad MAS ALTA entre todas las fuentes activas ---
        boolean turbo = s.boostTicks > 0;
        double mult = 1.0;
        if (turbo) {
            mult = FIRE_MULT[Math.min(s.fireLevel, 3)];
        }
        if (s.sacred) {
            mult = Math.max(mult, SACRED_MULT);
        }
        if (s.superTicks > 0) {
            mult = Math.max(mult, SUPER_MULT);
        }
        double cap = k.top * mult;
        boolean forced = s.sacred || turbo;          // el turbo / Sacred Fire fuerza la velocidad aunque no pises W
        boolean powered = forced || s.superTicks > 0;
        double accel = k.accel * (powered ? 2.5 : 1.0);

        if (throttle > 0) {
            if (s.speed < cap) {
                s.speed = Math.min(cap, s.speed + accel);
            }
        } else if (s.drifting) {
            s.speed *= 0.99;
        } else if (throttle < 0) {
            s.speed -= BRAKE;
        } else if (!forced) {
            s.speed *= COAST;
        }
        if (forced && s.speed < cap) {
            s.speed = Math.min(cap, Math.max(s.speed, 0) + 0.05);
        }
        // Si ya vas mas rapido de lo que da la fuente actual, esa velocidad mas alta se respeta
        // mientras haya potenciadores; solo baja al acabarse todos.
        if (!powered && s.speed > cap) {
            s.speed = Math.max(cap, s.speed - 0.012);
        }
        if (s.speed < -REVERSE_MAX) {
            s.speed = -REVERSE_MAX;
        }
        if (blocked) { // choque frontal contra pared
            s.speed *= 0.6;
            if (s.drifting) {
                endDrift(horse, s);
            }
        }

        // --- aplicar movimiento y rotacion ---
        double yawRad = Math.toRadians(s.moveYaw);
        Vec3 dm = horse.getDeltaMovement();
        horse.setDeltaMovement(-Math.sin(yawRad) * s.speed, dm.y, Math.cos(yawRad) * s.speed);
        s.appliedSpeed = s.speed;
        float bodyYaw = s.moveYaw + s.bodyOffset;
        horse.setYRot(bodyYaw);
        horse.setXRot(0f);
        horse.yRotO = bodyYaw;
        horse.yBodyRot = bodyYaw;
        horse.yHeadRot = bodyYaw;

        // --- temporizadores: turbo, Sacred Fire y ENERGIA ---
        if (s.boostTicks > 0) {
            s.boostTicks--;
        }
        if (s.sacred) {
            if (s.sacredTicks > 0) {
                s.sacredTicks--;
            } else if (s.energy > 0) { // la energia alarga el Sacred Fire
                s.energy = Math.max(0f, s.energy - ENERGY_DRAIN);
            } else {
                s.sacred = false;
            }
        }
        if (s.boostTicks <= 0 && !s.sacred) {
            s.fireLevel = 0;
        }

        effects(horse, s, bodyYaw);
        if (horse.tickCount % 2 == 0) {
            hud(s);
        }
    }

    private static void addEnergy(KartState s, float amount) {
        s.energy = Math.min(ENERGY_MAX, s.energy + amount);
    }

    /** Caida alta: da energia segun la altura y activa la velocidad de Sacred Fire. */
    private static void highFall(AbstractHorse horse, KartState s, double drop) {
        double t = Mth.clamp((drop - FALL_MIN) / (FALL_FULL - FALL_MIN), 0.0, 1.0);
        addEnergy(s, (float) (FALL_ENERGY_MIN + t * (FALL_ENERGY_MAX - FALL_ENERGY_MIN)));
        startSacred(horse, s, false); // la caida ya da su propia energia
    }

    /** Activa (o renueva) el Sacred Fire. Al asestarlo por primera vez da energia, suena y sale el estallido. */
    private static void startSacred(AbstractHorse horse, KartState s, boolean giveEnergy) {
        boolean fresh = !s.sacred;
        s.sacred = true;
        s.fireLevel = 3;
        s.sacredTicks = Math.max(s.sacredTicks, SACRED_BASE_TICKS);
        if (fresh) {
            if (giveEnergy) {
                addEnergy(s, SACRED_ENERGY);
            }
            sacredBurst(horse, s);
        }
    }

    /** Al soltar el derrape: nivel 1-2 cobra un turbo; con la 3a barra el Sacred Fire se renueva desde ahora. */
    private static void endDrift(AbstractHorse horse, KartState s) {
        if (s.pending >= MAX_TURBOS) {
            if (s.sacred) {
                s.sacredTicks = Math.max(s.sacredTicks, SACRED_BASE_TICKS);
            } else {
                startSacred(horse, s, true);
            }
        } else if (s.pending > 0) {
            int level = s.pending;
            s.fireLevel = s.boostTicks > 0 ? Math.max(s.fireLevel, level) : level;
            s.boostTicks = Math.min(BOOST_CAP, s.boostTicks + TURBO_TICKS_PER_LEVEL * level);
            sfx(horse, SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.8f, 1.0f);
        }
        s.drifting = false;
        s.driftDir = 0;
        s.pending = 0;
        s.charge = 0;
    }

    /** Punto del mundo a "back" bloques hacia atras y "side" a la derecha del kart (con kart_flip, hacia delante). */
    private static Vec3 local(AbstractHorse horse, KartState s, float bodyYaw, double back, double side, double up) {
        double rad = Math.toRadians(bodyYaw);
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        double dir = s.flip ? 1.0 : -1.0;
        return new Vec3(horse.getX() + fx * dir * back - fz * side,
                horse.getY() + up,
                horse.getZ() + fz * dir * back + fx * side);
    }

    private static DustParticleOptions tierColor(int tier) {
        return tier >= 3 ? FIRE_BLUE : tier == 2 ? FIRE_YELLOW : FIRE_RED;
    }

    private static void sacredBurst(AbstractHorse horse, KartState s) {
        Level level = horse.level();
        RandomSource r = horse.getRandom();
        sfx(horse, KartSounds.nextSacred(), 0.9f, 1.0f); // uno de los 3 sonidos, alternando
        float yaw = s.moveYaw + s.bodyOffset;
        for (int side = -1; side <= 1; side += 2) {
            Vec3 e = local(horse, s, yaw, EXHAUST_BACK, side * EXHAUST_SIDE, EXHAUST_Y);
            level.addParticle(ParticleTypes.EXPLOSION, e.x, e.y, e.z, 0, 0, 0);
            for (int i = 0; i < 10; i++) {
                double vx = (r.nextDouble() - 0.5) * 0.3;
                double vy = 0.05 + r.nextDouble() * 0.1;
                double vz = (r.nextDouble() - 0.5) * 0.3;
                level.addParticle(FIRE_BLUE, e.x, e.y, e.z, vx, vy, vz);
                level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, e.x, e.y, e.z, vx, vy, vz);
            }
        }
    }

    private static void effects(AbstractHorse horse, KartState s, float bodyYaw) {
        Level level = horse.level();
        RandomSource r = horse.getRandom();
        double rad = Math.toRadians(bodyYaw);
        double dir = s.flip ? 1.0 : -1.0;
        double ox = -Math.sin(rad) * dir; // hacia donde sale el fuego
        double oz = Math.cos(rad) * dir;

        if (s.sacred || s.boostTicks > 0) {
            // Colores bien distintos: rojo (nivel 1), amarillo (nivel 2), azul (Sacred Fire)
            ParticleOptions color = s.sacred ? FIRE_BLUE : (s.fireLevel >= 2 ? FIRE_YELLOW : FIRE_RED);
            ParticleOptions flame = s.sacred ? ParticleTypes.SOUL_FIRE_FLAME
                    : (s.fireLevel >= 2 ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME);
            int n = s.sacred ? 3 : Math.max(1, s.fireLevel);
            for (int side = -1; side <= 1; side += 2) {
                Vec3 e = local(horse, s, bodyYaw, EXHAUST_BACK, side * EXHAUST_SIDE, EXHAUST_Y);
                for (int i = 0; i < n; i++) {
                    double px = e.x + (r.nextDouble() - 0.5) * 0.06;
                    double py = e.y + (r.nextDouble() - 0.5) * 0.06;
                    double pz = e.z + (r.nextDouble() - 0.5) * 0.06;
                    level.addParticle(color, px, py, pz, ox * 0.18, 0.01, oz * 0.18);
                    level.addParticle(flame, px, py, pz, ox * 0.18, 0.01, oz * 0.18);
                }
            }
        }
        if (s.drifting && horse.onGround()) {
            for (int side = -1; side <= 1; side += 2) { // humo + chispas del color del nivel en las ruedas traseras
                Vec3 w = local(horse, s, bodyYaw, 0.45, side * 0.5, 0.1);
                level.addParticle(ParticleTypes.CLOUD, w.x, w.y, w.z, 0, 0.02, 0);
                if (s.pending >= 1) {
                    level.addParticle(tierColor(s.pending), w.x, w.y + 0.1, w.z,
                            (r.nextDouble() - 0.5) * 0.1, 0.05, (r.nextDouble() - 0.5) * 0.1);
                }
            }
        }
        if (s.superTicks > 0 && horse.tickCount % 2 == 0) {
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, horse.getX() + (r.nextDouble() - 0.5),
                    horse.getY() + 0.3 + r.nextDouble() * 0.5, horse.getZ() + (r.nextDouble() - 0.5), 0, 0, 0);
        }
    }

    /** Barra de energia (barra de accion) + estado del turbo + barra de carga del derrape. */
    private static void hud(KartState s) {
        StringBuilder sb = new StringBuilder();
        if (s.sacred) {
            sb.append("§9§lSACRED FIRE§r ");
        } else if (s.boostTicks > 0) {
            sb.append(s.fireLevel >= 3 ? "§b" : s.fireLevel == 2 ? "§e" : "§c").append("Turbo§r ");
        }

        int total = 16;
        int filled = Math.round(s.energy / ENERGY_MAX * total);
        sb.append("§7Energia ").append(s.sacred ? "§9" : "§b");
        for (int i = 0; i < total; i++) {
            if (i == filled) {
                sb.append("§8");
            }
            sb.append(i < filled ? "█" : "░");
        }
        sb.append(String.format(Locale.ROOT, " §f%d%%", Math.round(s.energy)));
        if (s.sacred) {
            float secs = (s.sacredTicks + s.energy / ENERGY_DRAIN) / 20f;
            sb.append(String.format(Locale.ROOT, " §9(%.1fs)", secs));
        }
        if (s.superTicks > 0) {
            sb.append(String.format(Locale.ROOT, " §dSuper Motor %.1fs", s.superTicks / 20f));
        }
        if (s.drifting) {
            int cells = TIER_TICKS;
            int fill = s.pending >= MAX_TURBOS ? cells : s.charge;
            String col = s.pending >= 2 ? "§9" : s.pending == 1 ? "§e" : "§c";
            sb.append(" §7[").append(col);
            for (int i = 0; i < cells; i++) {
                if (i == fill) {
                    sb.append("§8");
                }
                sb.append(i < fill ? "█" : "░");
            }
            sb.append("§7] ")
                    .append(s.pending >= 1 ? "§c▮" : "§8▯")
                    .append(s.pending >= 2 ? "§e▮" : "§8▯")
                    .append(s.pending >= 3 ? "§9▮" : "§8▯");
        }
        HUD.accept(Component.literal(sb.toString()));
    }

    private static void sfx(AbstractHorse h, SoundEvent e, float volume, float pitch) {
        h.level().playLocalSound(h.getX(), h.getY(), h.getZ(), e, SoundSource.NEUTRAL, volume, pitch, false);
    }
}
