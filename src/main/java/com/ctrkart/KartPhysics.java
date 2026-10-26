package com.ctrkart;

import com.ctrkart.mixin.LivingEntityAccessor;
import java.util.Locale;
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
 *             Durante el derrape, S (tap) en la ventana verde = turbo (hasta 3, se apilan)
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
    // Turbos
    static final int CHARGE_PERIOD = 22;      // el medidor da la vuelta cada 22 ticks (antes 34)
    static final int WINDOW_OPEN = 9;          // ventana de turbo perfecto: ticks 9..17 (antes 18..30)
    static final int WINDOW_CLOSE = 17;
    static final int MAX_TURBOS = 3;
    static final int TURBO_TICKS_PER_LEVEL = 24;
    static final int HANG_MIN_AIR = 12;        // "hang time": aire minimo para turbo al aterrizar
    // Reservas y Sacred Fire
    static final float RESERVE_CAP = 160f;     // 8 s
    static final int SACRED_CHAIN = 3;         // 3 turbos encadenados = Sacred Fire
    static final double[] FIRE_MULT = {1.00, 1.18, 1.30, 1.42}; // sin fuego / rojo / amarillo / azul
    static final double SACRED_MULT = 1.58;
    // Escape del modelo de kart (bloques, relativo al centro del kart)
    static final double EXHAUST_BACK = 0.9;
    static final double EXHAUST_SIDE = 0.20;
    static final double EXHAUST_Y = 0.25;

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
        boolean backPressed = throttle < 0 && !s.backHeld;
        boolean jumpPressed = jump && !s.jumpHeld;
        s.jumpHeld = jump;
        s.backHeld = throttle < 0;

        if (s.superTicks > 0) {
            s.superTicks--;
        }

        // --- aire / aterrizaje (hang time) ---
        boolean onGround = horse.onGround();
        if (!onGround) {
            s.airTicks++;
        } else {
            if (s.airTicks >= HANG_MIN_AIR) {
                payout(horse, s, 1, Math.min(s.airTicks * 2, 60));
            }
            if (s.airTicks > 0) {
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

        // --- derrape ---
        if (s.drifting) {
            if (!jump || s.speed < DRIFT_MIN_SPEED * 0.6) {
                endDrift(horse, s);
            } else if (onGround) {
                s.charge++;
                if (s.charge > CHARGE_PERIOD) {
                    s.charge = 0;
                }
                if (backPressed) {
                    tapTurbo(horse, s);
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

        // --- velocidad ---
        boolean boosting = s.reserve > 0;
        double mult = 1.0;
        if (boosting) {
            mult = s.sacred ? SACRED_MULT : FIRE_MULT[Math.min(s.fireLevel, 3)];
        }
        if (s.superTicks > 0) { // Super Motor: la velocidad maxima sube a velocidad de turbo
            mult = Math.max(mult, FIRE_MULT[3]);
        }
        double cap = k.top * mult;
        boolean powered = boosting || s.superTicks > 0;
        double accel = k.accel * (powered ? 2.5 : 1.0);

        if (throttle > 0) {
            s.speed += accel;
        } else if (s.drifting) {
            s.speed *= 0.99;
        } else if (throttle < 0) {
            s.speed -= BRAKE;
        } else {
            s.speed *= COAST;
        }
        if (boosting) { // el turbo fuerza la velocidad aunque no pises W
            s.speed = Math.min(cap, Math.max(s.speed, 0) + 0.05);
        }
        if (s.speed > cap) {
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

        // --- drenaje de reservas (Sacred Fire gasta la mitad) ---
        if (s.reserve > 0) {
            s.reserve -= s.sacred ? 0.5f : 1f;
            if (s.reserve <= 0) {
                s.reserve = 0;
                s.fireLevel = 0;
                s.chain = 0;
                s.sacred = false;
            }
        }

        effects(horse, s, bodyYaw);
        if (horse.tickCount % 2 == 0) {
            hud(rider, s);
        }
    }

    private static void tapTurbo(AbstractHorse horse, KartState s) {
        if (s.charge >= WINDOW_OPEN && s.charge <= WINDOW_CLOSE) {
            s.pending++;
            s.charge = 0;
            sfx(horse, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f + 0.25f * s.pending);
            if (s.pending >= MAX_TURBOS) { // tres turbos: se apilan en uno solo
                payout(horse, s, s.pending, TURBO_TICKS_PER_LEVEL * s.pending);
                s.pending = 0;
            }
        } else { // mal temporizado: se pierde la cadena
            s.pending = 0;
            s.charge = 0;
        }
    }

    private static void endDrift(AbstractHorse horse, KartState s) {
        if (s.pending > 0) {
            payout(horse, s, s.pending, TURBO_TICKS_PER_LEVEL * s.pending);
        }
        s.drifting = false;
        s.driftDir = 0;
        s.pending = 0;
        s.charge = 0;
    }

    /** Un turbo cobra: suma a las RESERVAS, sube el fuego y encadena hacia el Sacred Fire. */
    private static void payout(AbstractHorse horse, KartState s, int level, int ticks) {
        boolean chaining = s.reserve > 0;
        s.chain = chaining ? s.chain + 1 : 1;
        s.fireLevel = chaining ? Math.max(s.fireLevel, level) : level;
        s.reserve = Math.min(RESERVE_CAP, s.reserve + ticks);
        if (s.chain >= SACRED_CHAIN && !s.sacred) {
            s.sacred = true;
            sacredBurst(horse, s);
        }
        sfx(horse, SoundEvents.FIREWORK_ROCKET_LAUNCH, 1.0f);
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

    private static void sacredBurst(AbstractHorse horse, KartState s) {
        Level level = horse.level();
        RandomSource r = horse.getRandom();
        sfx(horse, SoundEvents.FIREWORK_ROCKET_BLAST, 0.85f); // explosion pequena
        sfx(horse, SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.6f);
        float yaw = s.moveYaw + s.bodyOffset;
        for (int side = -1; side <= 1; side += 2) {
            Vec3 e = local(horse, s, yaw, EXHAUST_BACK, side * EXHAUST_SIDE, EXHAUST_Y);
            level.addParticle(ParticleTypes.EXPLOSION, e.x, e.y, e.z, 0, 0, 0);
            for (int i = 0; i < 8; i++) {
                level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, e.x, e.y, e.z,
                        (r.nextDouble() - 0.5) * 0.3, 0.05 + r.nextDouble() * 0.1, (r.nextDouble() - 0.5) * 0.3);
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

        if (s.reserve > 0) { // llama por los dos escapes: roja -> amarilla -> azul (Sacred Fire)
            ParticleOptions p = s.sacred ? ParticleTypes.SOUL_FIRE_FLAME
                    : (s.fireLevel >= 2 ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME);
            int n = s.sacred ? 3 : Math.max(1, s.fireLevel);
            for (int side = -1; side <= 1; side += 2) {
                Vec3 e = local(horse, s, bodyYaw, EXHAUST_BACK, side * EXHAUST_SIDE, EXHAUST_Y);
                for (int i = 0; i < n; i++) {
                    level.addParticle(p, e.x + (r.nextDouble() - 0.5) * 0.06, e.y + (r.nextDouble() - 0.5) * 0.06,
                            e.z + (r.nextDouble() - 0.5) * 0.06, ox * 0.18, 0.01, oz * 0.18);
                }
            }
        }
        if (s.drifting && horse.onGround()) {
            for (int side = -1; side <= 1; side += 2) { // humo en las ruedas traseras
                Vec3 w = local(horse, s, bodyYaw, 0.45, side * 0.5, 0.1);
                level.addParticle(ParticleTypes.CLOUD, w.x, w.y, w.z, 0, 0.02, 0);
                if (s.charge >= WINDOW_OPEN && s.charge <= WINDOW_CLOSE) { // "Nitro Wheels" brillando
                    level.addParticle(ParticleTypes.ELECTRIC_SPARK, w.x, w.y + 0.1, w.z, 0, 0.05, 0);
                }
            }
        }
        if (s.superTicks > 0 && horse.tickCount % 2 == 0) {
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, horse.getX() + (r.nextDouble() - 0.5),
                    horse.getY() + 0.3 + r.nextDouble() * 0.5, horse.getZ() + (r.nextDouble() - 0.5), 0, 0, 0);
        }
    }

    private static void hud(Player rider, KartState s) {
        StringBuilder sb = new StringBuilder();
        if (s.sacred) {
            sb.append("§9§lSACRED FIRE§r ");
        } else if (s.reserve > 0) {
            sb.append(s.fireLevel >= 3 ? "§b" : s.fireLevel == 2 ? "§e" : "§c").append("Turbo§r ");
        }
        if (s.reserve > 0) {
            sb.append(String.format(Locale.ROOT, "Reservas %.1fs ", s.reserve / 20f));
        }
        if (s.superTicks > 0) {
            sb.append(String.format(Locale.ROOT, "§dSuper Motor %.1fs§r ", s.superTicks / 20f));
        }
        if (s.drifting) {
            sb.append("§7[");
            for (int i = 0; i < CHARGE_PERIOD / 2; i++) {
                boolean cur = s.charge >= i * 2 && s.charge < i * 2 + 2;
                boolean win = i * 2 >= WINDOW_OPEN && i * 2 + 1 <= WINDOW_CLOSE;
                sb.append(cur ? "§f█" : win ? "§a▮" : "§8▯");
            }
            sb.append("§7] §6").append("▮".repeat(s.pending));
        }
        if (sb.length() > 0) {
            HUD.accept(Component.literal(sb.toString()));
        }
    }

    private static void sfx(AbstractHorse h, SoundEvent e, float pitch) {
        h.level().playLocalSound(h.getX(), h.getY(), h.getZ(), e, SoundSource.NEUTRAL, 0.8f, pitch, false);
    }
}
