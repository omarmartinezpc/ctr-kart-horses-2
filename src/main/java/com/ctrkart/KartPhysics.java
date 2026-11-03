package com.ctrkart;

import com.ctrkart.mixin.LivingEntityAccessor;
import java.util.Locale;
import net.minecraft.core.BlockPos;
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
 *   1a barra llena = nivel 1 (rojo), 2a = nivel 2 (amarillo), 3a = SACRED FIRE (amarillo potenciado; azul con /ctrkart sacredazul true).
 *   Al soltar ESPACIO con nivel 1 o 2 se cobra un turbo de ese nivel.
 *
 * MANEJO CLASICO (ajuste "manejo_clasico", ver KartConfig): el derrape es mas recto y reacciona a hacia donde giras
 * (hacia el lado en que empezaste = curva mas; al lado contrario = casi recto), y el Sacred Fire ya no es
 * automatico: al llenarse la 3a barra hay que pulsar el CLICK IZQUIERDO para soltarlo. Tras acertar uno, la barra
 * vuelve a llenarse y puedes hacer otro en el mismo derrape (maximo 3, contando el primero). Si con la barra llena
 * no haces click a tiempo, ese Sacred Fire se pierde, no puedes hacer mas en ese derrape y sale humo negro.
 *
 * ENERGIA (0..200; de 101% a 200% se gasta 25% mas rapido): se obtiene al lograr Sacred Fire (cada vez, se acumula), al caer de altura y al alimentar.
 * Se drena siempre, sin pausas; el Sacred Fire dura mientras queden reservas.
 * Se recuerdan los ultimos 3 Sacred Fire hechos con ESPACIO: si los 3 fueron seguidos (cada uno con el
 * anterior aun activo), hay un poco mas de velocidad hasta que se acaben las reservas.
 *
 * NIVELES DE SACRED FIRE (mientras haya reservas): 1 rojo, 2 amarillo, 3 amarillo potenciado, 4 azul.
 *   - Caida: rojo si es baja (menos de ~5 bloques), amarillo si es alta (~5 o mas). Solo si no tenias uno mayor.
 *   - Sacred Fire de derrape: amarillo potenciado (o azul con /ctrkart sacredazul true).
 *   - Bloque Sacred Pad: azul + reservas + animacion.
 * U-TURN: con S en el aire (a cualquier altura) giras mucho mas facil y no pierdes velocidad.
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
    /** Ayuda para subir bloques y slabs en el aire: durante los primeros ticks de un salto/caida el kart sube escalones como si estuviera en el suelo. */
    static final int STEP_ASSIST_TICKS = 12;
    /** Si la fisica lleva mas de esto sin correr (2 s), se considera que te bajaste y se reinicia al volver a montar. */
    static final long RESET_GAP_TICKS = 40;
    // Derrape
    static final int TIER_TICKS = 7;           // ticks que tarda en llenarse cada barra (7 = 0,35 s)
    static final int MAX_TURBOS = 3;           // la 3a barra es el Sacred Fire
    static final int TURBO_TICKS_PER_LEVEL = 24;
    static final int BOOST_CAP = 120;
    static final double[] FIRE_MULT = {1.00, 1.16, 1.32, 1.50}; // sin fuego / rojo / amarillo / azul
    // Sacred Fire
    // Velocidad segun el nivel del Sacred Fire: 0 ninguno, 1 rojo, 2 amarillo, 3 amarillo potenciado, 4 azul
    static final double[] SACRED_TIER_MULT = {1.00, 1.16, 1.32, 1.36, 1.55};
    // Con 3 Sacred Fire de derrape seguidos: un poco mas, mientras duren las reservas (solo niveles 3 y 4)
    static final double[] SACRED_TIER_CHAIN = {1.00, 1.16, 1.32, 1.41, 1.62};
    static final double SUPER_MULT = 1.30;     // Super Motor
    // Energia
    static final float ENERGY_MAX = 200f;      // tope de las reservas (200%)
    static final float ENERGY_NORMAL = 100f;   // hasta aqui se gastan a ritmo normal; de 101% a 200% se gastan 25% mas rapido
    static final float ENERGY_DRAIN = 0.5f;    // por tick mientras alarga el Sacred Fire (100 = 10 s)
    static final float ENERGY_DRAIN_OVER = 0.625f; // por tick por encima del 100% (25% mas rapido que 0.5)
    static final float SACRED_ENERGY = 25f;    // energia al asestar un Sacred Fire
    // Caida alta: de FALL_MIN a FALL_FULL bloques de caida, la energia va de FALL_ENERGY_MIN a FALL_ENERGY_MAX
    static final double FALL_MIN = 3.0;
    static final double FALL_YELLOW_MIN = 4.5; // desde esta caida (~5 bloques) el Sacred Fire es amarillo; por debajo, rojo
    static final double FALL_FULL = 24.0;
    static final float FALL_ENERGY_MIN = 10f;
    static final float FALL_ENERGY_MAX = 100f;
    // U-Turn (S en el aire)
    static final int U_TURN_MIN_AIR_TICKS = 2;       // ticks en el aire antes de poder hacer U-Turn
    static final float U_TURN_STEER_MULT = 2.4f;     // giro multiplicado con A/D durante el U-Turn
    static final float U_TURN_DRIFT_MULT = 1.6f;     // si ademas derrapas, la curva se cierra esto mas
    // Bloque Sacred Pad
    static final float PAD_ENERGY = 40f;             // reservas que da al pisarlo
    static final int PAD_REARM_TICKS = 10;           // ticks fuera del bloque para que se pueda activar otra vez (10 = 0,5 s)
    // Manejo clasico
    static final int CLASSIC_MAX_HITS = 3;        // Sacred Fire maximos por derrape (contando el primero)
    static final float CLASSIC_CURVE_BASE = 0.45f;  // curvatura del derrape sin tocar A/D (el normal es 0.95)
    static final float CLASSIC_CURVE_STEER = 0.50f; // lo que suma/resta girar hacia/contra el lado del derrape
    static final float CLASSIC_CURVE_MIN = 0.05f;   // girando al lado contrario: casi recto
    static final float CLASSIC_CURVE_MAX = 0.95f;   // girando hacia el lado del derrape: curva mas
    static final int CLASSIC_WINDOW_TICKS = 12;     // tiempo para hacer click tras llenarse la barra (12 = 0,6 s)
    static final int FAIL_SMOKE_TICKS = 50;         // humo negro tras fallar (50 = 2,5 s)
    // Escape del modelo de kart (bloques, relativo al centro del kart)
    static final double EXHAUST_BACK = 0.72;
    static final double EXHAUST_SIDE = 0.225;
    static final double EXHAUST_Y = 0.62;

    private static final DustParticleOptions FIRE_RED = new DustParticleOptions(0xFF2A2A, 1.3f);
    private static final DustParticleOptions FIRE_YELLOW = new DustParticleOptions(0xFFD21E, 1.3f);
    private static final DustParticleOptions FIRE_GOLD = new DustParticleOptions(0xFFF04A, 1.6f);
    private static final DustParticleOptions FIRE_BLUE = new DustParticleOptions(0x2F6BFF, 1.5f);
    private static final DustParticleOptions SMOKE_WHITE = new DustParticleOptions(0xF4F4F4, 1.0f);
    private static final DustParticleOptions SMOKE_BLACK = new DustParticleOptions(0x0B0B0B, 1.6f);

    /** Lo fija el cliente (KartModClient) para escribir en la barra de accion. */
    public static volatile java.util.function.Consumer<Component> HUD = c -> {};

    /** Lo fija el cliente (KartModClient): true mientras se mantiene el click izquierdo. */
    public static volatile java.util.function.BooleanSupplier ATTACK = () -> false;

    /** Lo fija el cliente: devuelve true si hubo pulsaciones del click izquierdo desde la ultima vez (las gasta). */
    public static volatile java.util.function.BooleanSupplier ATTACK_CLICKS = () -> false;

    private KartPhysics() {}

    public static void tick(AbstractHorse horse, Player rider, KartState s) {
        // Se reinicia SOLO al montar (KartCamera marca needsReset al bajarte) o si la fisica llevaba mucho sin correr.
        // Antes se usaba la hora del servidor, que da saltos con el lag y borraba el Sacred Fire y la velocidad en pleno juego.
        long now = KartTracker.clientTick;
        if (s.needsReset || now - s.lastTick > RESET_GAP_TICKS) {
            s.resetDynamic();
            s.moveYaw = horse.getYRot();
            s.needsReset = false;
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
        s.steerAnim += (steer - s.steerAnim) * 0.4f; // para la animacion de las ruedas delanteras
        float throttle = rider.zza; // +1 = W, -1 = S
        boolean jump = ((LivingEntityAccessor) rider).ctrkart$isJumping();
        boolean jumpPressed = jump && !s.jumpHeld;
        s.jumpHeld = jump;
        boolean classic = KartConfig.classicHandling;
        boolean attack = ATTACK.getAsBoolean();
        boolean attackPressed = attack && !s.attackHeld; // pulsacion nueva del click izquierdo
        s.attackHeld = attack;

        if (s.superTicks > 0) {
            s.superTicks--;
        }
        if (s.failSmokeTicks > 0) {
            s.failSmokeTicks--;
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

        // --- bloque Sacred Pad ---
        padCheck(horse, s);

        // --- hop ---
        if (jumpPressed && onGround) {
            Vec3 dm = horse.getDeltaMovement();
            horse.setDeltaMovement(dm.x, HOP_VELOCITY, dm.z);
            s.hopAir = true;
        }

        // --- inicio de derrape: hop + girar manteniendo el salto ---
        if (!s.drifting && jump && s.hopAir && steer != 0 && s.speed >= DRIFT_MIN_SPEED) {
            s.drifting = true;
            s.driftGrounded = false; // la barra empieza a cargar al aterrizar del hop
            s.driftDir = steer > 0 ? 1 : -1;
            s.charge = 0;
            s.pending = 0;
        }

        // --- derrape: la barra se llena sola; cada barra llena sube un nivel ---
        if (s.drifting) {
            if (onGround) {
                s.driftGrounded = true;
            }
            // Una vez que el derrape toco el suelo, la barra sigue cargando aunque caigas o rebotes en escalones
            // (antes solo cargaba con onGround y se pausaba en el aire).
            boolean canCharge = (onGround || s.driftGrounded) && s.pending < MAX_TURBOS
                    && !(classic && (s.driftHits >= CLASSIC_MAX_HITS || s.sacredFailed));
            if (!jump || s.speed < DRIFT_MIN_SPEED * 0.6) {
                endDrift(horse, s);
            } else if (canCharge) {
                s.charge++;
                if (s.charge >= TIER_TICKS) {
                    s.charge = 0;
                    s.pending++;
                    if (s.pending >= MAX_TURBOS) {
                        if (classic) { // 3a barra llena: el Sacred Fire queda LISTO, falta el click izquierdo
                            s.sacredReady = true;
                            s.sacredWindow = CLASSIC_WINDOW_TICKS;
                            sfx(horse, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.8f);
                        } else {       // 3a barra: SACRED FIRE al instante (solo suena su propio sonido)
                            driftStrike(horse, s);
                        }
                    } else {
                        sfx(horse, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5f, 0.8f + 0.25f * s.pending);
                    }
                }
            }
            // manejo clasico: con el Sacred Fire listo hay una ventana corta para hacer CLICK IZQUIERDO.
            // Acierto = Sacred Fire y la barra vuelve a empezar. Sin click a tiempo = se pierde (y no hay mas en este derrape).
            if (classic && s.drifting && s.sacredReady) {
                boolean clicked = attackPressed || ATTACK_CLICKS.getAsBoolean();
                if (clicked) {
                    driftStrike(horse, s);
                    s.sacredReady = false;
                    s.driftHits++;
                    s.pending = 0;
                    s.charge = 0;
                } else if (--s.sacredWindow <= 0) {
                    s.sacredReady = false;
                    s.sacredFailed = true;
                    s.failSmokeTicks = FAIL_SMOKE_TICKS;
                    sfx(horse, KartSounds.FAIL, 1.0f, 1.0f);
                }
            }
        }

        // --- U-Turn: S en el aire (a cualquier altura) = giras mucho mas facil y no pierdes velocidad ---
        boolean uTurn = !onGround && s.airTicks >= U_TURN_MIN_AIR_TICKS && throttle < 0 && s.speed > 0.05;
        s.uTurn = uTurn;

        // --- direccion ---
        float speedFactor = (float) Mth.clamp(Math.abs(s.speed) / 0.12, 0.0, 1.0);
        float rate;
        float curve = CLASSIC_CURVE_MAX; // solo manejo clasico: cuanto curva el derrape (1 = lo normal)
        if (s.drifting && classic) {
            // girar hacia el lado en que empezaste el derrape curva mas; hacia el otro lado lo deja casi recto
            float same = steer * s.driftDir;
            curve = Mth.clamp(CLASSIC_CURVE_BASE + CLASSIC_CURVE_STEER * same, CLASSIC_CURVE_MIN, CLASSIC_CURVE_MAX);
            rate = k.turn * s.driftDir * curve * speedFactor;
        } else if (s.drifting) {
            rate = k.turn * (s.driftDir * 0.95f + steer * 0.5f) * speedFactor;
        } else {
            rate = k.turn * steer * speedFactor * (s.speed < 0 ? -1f : 1f);
        }
        if (uTurn) {
            rate *= s.drifting ? U_TURN_DRIFT_MULT : U_TURN_STEER_MULT;
        }
        s.moveYaw += rate;
        float angle = classic ? DRIFT_ANGLE * (0.55f + 0.45f * curve / CLASSIC_CURVE_MAX) : DRIFT_ANGLE; // mas recto = menos cruzado
        float target = s.drifting ? s.driftDir * angle : 0f;
        s.bodyOffset += (target - s.bodyOffset) * 0.35f;

        // --- velocidad: manda siempre la velocidad MAS ALTA entre todas las fuentes activas ---
        boolean turbo = s.boostTicks > 0;
        double mult = 1.0;
        if (turbo) {
            mult = FIRE_MULT[Math.min(s.fireLevel, 3)];
        }
        if (s.sacred) {
            int tier = Mth.clamp(s.sacredTier, 1, 4);
            mult = Math.max(mult, s.chainBonus ? SACRED_TIER_CHAIN[tier] : SACRED_TIER_MULT[tier]);
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
            if (!uTurn) { // en el aire con S (U-Turn) no se frena: se conserva la velocidad
                s.speed -= BRAKE;
            }
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
        // Escalones en el aire: el hop sube solo unos 0,47 bloques, menos que un slab (0,5), asi que el kart se
        // chocaba de lado contra el bloque, frenaba (blocked) y perdia la velocidad. Marcamos "en el suelo" solo
        // para este movimiento: el juego solo intenta subir el escalon si cree que estas en el suelo, y despues
        // recalcula onGround el mismo. Si no hay choque horizontal, esto no cambia nada.
        if (!onGround && s.airTicks > 0 && s.airTicks <= STEP_ASSIST_TICKS && s.speed > 0.05) {
            horse.setOnGround(true);
        }
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
        // Las reservas se drenan SIEMPRE, sin pausas; el Sacred Fire dura mientras queden reservas
        if (s.energy > 0) {
            s.energy = Math.max(0f, s.energy - (s.energy > ENERGY_NORMAL ? ENERGY_DRAIN_OVER : ENERGY_DRAIN));
        }
        if (s.sacred && s.energy <= 0) {
            s.sacred = false;
            s.sacredTier = 0;
            s.chainBonus = false;
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

    /**
     * Activa un Sacred Fire de este nivel (1 rojo, 2 amarillo, 3 amarillo potenciado, 4 azul) salvo que ya tengas
     * uno igual o mayor. Devuelve true si el nivel cambio (se activo uno nuevo o subio de nivel).
     */
    private static boolean grantSacred(KartState s, int tier) {
        if (s.sacred && s.sacredTier >= tier) {
            return false;
        }
        s.sacred = true;
        s.sacredTier = tier;
        return true;
    }

    /**
     * Caida: da energia segun la altura y un Sacred Fire rojo (caida baja) o amarillo (caida alta, ~5 bloques o mas).
     * Si ya tenias un Sacred Fire de nivel mayor, solo te da las reservas (y la animacion). No entra en la memoria de 3.
     */
    private static void highFall(AbstractHorse horse, KartState s, double drop) {
        double t = Mth.clamp((drop - FALL_MIN) / (FALL_FULL - FALL_MIN), 0.0, 1.0);
        addEnergy(s, (float) (FALL_ENERGY_MIN + t * (FALL_ENERGY_MAX - FALL_ENERGY_MIN)));
        grantSacred(s, drop >= FALL_YELLOW_MIN ? 2 : 1);
        sacredBurst(horse, s); // siempre: sonido, explosion y caballito, aunque ya tuvieras un Sacred Fire mayor (solo reservas)
    }

    /** Bloque Sacred Pad: Sacred Fire azul, reservas y la animacion del Sacred Fire. */
    private static void padCheck(AbstractHorse horse, KartState s) {
        BlockPos below = new BlockPos(Mth.floor(horse.getX()), Mth.floor(horse.getY() - 0.1), Mth.floor(horse.getZ()));
        boolean onPad = horse.level().getBlockState(below).getBlock() == KartBlocks.SACRED_PAD;
        if (onPad && s.padOffTicks >= PAD_REARM_TICKS) {
            addEnergy(s, PAD_ENERGY);
            grantSacred(s, 4);
            sacredBurst(horse, s); // sonido, explosion, wheelie y motor, como un Sacred Fire
        }
        s.padOffTicks = onPad ? 0 : Math.min(1000, s.padOffTicks + 1);
    }

    /**
     * Sacred Fire hecho con ESPACIO (3a barra del derrape). Se guarda en la memoria de los ultimos 3,
     * SIEMPRE acumula energia (no solo la mantiene) y suena uno de los 3 audios.
     */
    private static void driftStrike(AbstractHorse horse, KartState s) {
        boolean chained = s.sacred; // llegas con el Sacred Fire anterior todavia activo
        s.strikeChained[0] = s.strikeChained[1];
        s.strikeChained[1] = s.strikeChained[2];
        s.strikeChained[2] = chained;
        s.strikeCount = Math.min(3, s.strikeCount + 1);
        if (s.strikeCount >= 3 && s.strikeChained[1] && s.strikeChained[2]) {
            s.chainBonus = true; // 3 seguidos: velocidad un poco mayor hasta que se acaben las reservas
        }
        addEnergy(s, SACRED_ENERGY);
        // Amarillo potenciado por defecto; azul con /ctrkart sacredazul true. Si ya tienes uno mayor, se conserva.
        grantSacred(s, KartConfig.blueSacred ? 4 : 3);
        sacredBurst(horse, s);
    }

    /** Al soltar el derrape: con 1 o 2 barras se cobra un turbo de ese nivel (la 3a barra ya fue Sacred Fire). */
    private static void endDrift(AbstractHorse horse, KartState s) {
        // La 3a barra llena sin Sacred Fire (en manejo clasico: sin click a tiempo) NO da ninguna recompensa
        int level = s.pending >= MAX_TURBOS ? 0 : s.pending;
        if (level > 0) {
            s.fireLevel = s.boostTicks > 0 ? Math.max(s.fireLevel, level) : level;
            s.boostTicks = Math.min(BOOST_CAP, s.boostTicks + TURBO_TICKS_PER_LEVEL * level);
            sfx(horse, SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.8f, 1.0f);
        }
        s.drifting = false;
        s.driftGrounded = false;
        s.driftDir = 0;
        s.pending = 0;
        s.charge = 0;
        s.sacredReady = false;
        s.sacredWindow = 0;
        s.sacredFailed = false;
        s.driftHits = 0;
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

    private static DustParticleOptions sacredDust(int tier) {
        return tier >= 4 ? FIRE_BLUE : tier == 3 ? FIRE_GOLD : tier == 2 ? FIRE_YELLOW : FIRE_RED;
    }

    private static ParticleOptions sacredFlame(int tier) {
        return tier >= 4 ? ParticleTypes.SOUL_FIRE_FLAME : tier >= 2 ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME;
    }

    private static void sacredBurst(AbstractHorse horse, KartState s) {
        s.sacredEvents++;
        int tier = Mth.clamp(s.sacredTier, 1, 4);
        DustParticleOptions dust = sacredDust(tier);
        ParticleOptions flame = tier >= 4 ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME;
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
                level.addParticle(dust, e.x, e.y, e.z, vx, vy, vz);
                level.addParticle(flame, e.x, e.y, e.z, vx, vy, vz);
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

        // Humo de los escapes: blanco en reposo / en marcha mientras no haya Sacred Fire; negro si fallaste el Sacred Fire
        boolean failing = s.failSmokeTicks > 0;
        if (failing || !s.sacred) {
            for (int side = -1; side <= 1; side += 2) {
                Vec3 e = local(horse, s, bodyYaw, EXHAUST_BACK, side * EXHAUST_SIDE, EXHAUST_Y);
                double jx = (r.nextDouble() - 0.5) * 0.05;
                double jz = (r.nextDouble() - 0.5) * 0.05;
                if (failing) {
                    level.addParticle(SMOKE_BLACK, e.x + jx, e.y, e.z + jz, ox * 0.06, 0.05, oz * 0.06);
                    level.addParticle(SMOKE_BLACK, e.x - jx, e.y + 0.03, e.z - jz, ox * 0.04, 0.06, oz * 0.04);
                    if (horse.tickCount % 2 == 0) {
                        level.addParticle(ParticleTypes.LARGE_SMOKE, e.x, e.y, e.z, ox * 0.04, 0.04, oz * 0.04);
                    }
                } else {
                    level.addParticle(SMOKE_WHITE, e.x + jx, e.y, e.z + jz, ox * 0.03, 0.03, oz * 0.03);
                    if (horse.tickCount % 6 == 0) {
                        level.addParticle(ParticleTypes.CLOUD, e.x, e.y, e.z, ox * 0.02, 0.03, oz * 0.02);
                    }
                }
            }
        }
        if (s.sacred || s.boostTicks > 0) {
            // Colores bien distintos: rojo (1), amarillo (2), amarillo potenciado (3), azul (4)
            int fl = s.sacred ? Mth.clamp(s.sacredTier, 1, 4) : s.fireLevel;
            ParticleOptions color = sacredDust(fl);
            ParticleOptions flame = sacredFlame(fl);
            int n = s.sacred ? (fl >= 3 ? 3 : 2) : Math.max(1, s.fireLevel);
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
        if (s.sacred && s.sacredTier == 3 && horse.tickCount % 2 == 0) { // el amarillo potenciado suelta chispas extra
            Vec3 e = local(horse, s, bodyYaw, EXHAUST_BACK, 0.0, EXHAUST_Y);
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, e.x, e.y, e.z, ox * 0.1, 0.05, oz * 0.1);
        }
        if (s.uTurn && horse.tickCount % 2 == 0) { // estela de nubes durante el U-Turn
            Vec3 w = local(horse, s, bodyYaw, 0.35, 0.0, 0.1);
            level.addParticle(ParticleTypes.CLOUD, w.x, w.y, w.z, 0, 0.02, 0);
        }
        if (s.drifting && horse.onGround()) {
            for (int side = -1; side <= 1; side += 2) { // humo + chispas del color del nivel en las ruedas traseras
                Vec3 w = local(horse, s, bodyYaw, 0.35, side * 0.46, 0.1);
                level.addParticle(ParticleTypes.CLOUD, w.x, w.y, w.z, 0, 0.02, 0);
                if (s.pending >= 1) {
                    level.addParticle(tierColor(s.pending), w.x, w.y + 0.1, w.z,
                            (r.nextDouble() - 0.5) * 0.1, 0.05, (r.nextDouble() - 0.5) * 0.1);
                }
            }
        }
        if (s.sacredReady) { // aviso visual: Sacred Fire listo, falta el click
            Vec3 e = local(horse, s, bodyYaw, EXHAUST_BACK, 0.0, EXHAUST_Y);
            level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, e.x, e.y, e.z, 0, 0.04, 0);
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
            String tierCol = s.sacredTier >= 4 ? "§9" : s.sacredTier == 3 ? "§6" : s.sacredTier == 2 ? "§e" : "§c";
            sb.append(tierCol).append("§lSACRED FIRE").append(s.sacredTier == 3 ? "+" : "").append("§r ");
            int stars = s.chainBonus ? 3 : (s.strikeCount > 0 ? (s.strikeChained[2] ? 2 : 1) : 0);
            if (stars > 0) { // memoria de Sacred Fire seguidos
                sb.append("§6").append("★".repeat(stars)).append("§8").append("☆".repeat(3 - stars)).append("§r ");
            }
        } else if (s.boostTicks > 0) {
            sb.append(s.fireLevel >= 3 ? "§b" : s.fireLevel == 2 ? "§e" : "§c").append("Turbo§r ");
        }

        // Barra: la primera barra (0-100%) en azul claro; de 101% a 200% se pinta encima, en un azul mas oscuro
        int total = 16;
        int filled = Math.min(total, Math.round(s.energy / ENERGY_NORMAL * total));
        int over = s.energy > ENERGY_NORMAL ? Math.round((s.energy - ENERGY_NORMAL) / ENERGY_NORMAL * total) : 0;
        String c1 = s.sacred ? "§9" : "§b";
        String c2 = s.sacred ? "§1" : "§9";
        sb.append("§7Energia ");
        String cur = "";
        for (int i = 0; i < total; i++) {
            String col = i < filled ? (i < over ? c2 : c1) : "§8";
            if (!col.equals(cur)) {
                sb.append(col);
                cur = col;
            }
            sb.append(i < filled ? "█" : "░");
        }
        sb.append(String.format(Locale.ROOT, " §f%d%%", Math.round(s.energy)));
        if (s.sacred) {
            float secs = (Math.min(s.energy, ENERGY_NORMAL) / ENERGY_DRAIN
                    + Math.max(0f, s.energy - ENERGY_NORMAL) / ENERGY_DRAIN_OVER) / 20f;
            sb.append(String.format(Locale.ROOT, " §9(%.1fs)", secs));
        }
        if (s.superTicks > 0) {
            sb.append(String.format(Locale.ROOT, " §dSuper Motor %.1fs", s.superTicks / 20f));
        }
        if (s.uTurn) {
            sb.append(" §d§l↻ U-TURN§r");
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
            if (KartConfig.classicHandling) {
                sb.append(" §6").append(s.driftHits).append("/").append(CLASSIC_MAX_HITS);
                if (s.sacredReady) {
                    sb.append(" §b§l¡CLICK IZQUIERDO!§r");
                } else if (s.sacredFailed) {
                    sb.append(" §c§l✖ SACRED PERDIDO§r");
                }
            }
        }
        HUD.accept(Component.literal(sb.toString()));
    }

    private static void sfx(AbstractHorse h, SoundEvent e, float volume, float pitch) {
        h.level().playLocalSound(h.getX(), h.getY(), h.getZ(), e, SoundSource.NEUTRAL, volume, pitch, false);
    }
}
