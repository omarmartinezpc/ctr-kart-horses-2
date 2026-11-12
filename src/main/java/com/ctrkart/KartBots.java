package com.ctrkart;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.equine.AbstractHorse; // si no existe: ...animal.horse.AbstractHorse
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * BOTS de carrera. TODO es del servidor y esta separado de la fisica de los jugadores (que corre en el cliente):
 *  - Item "Bot" (kart_bot): click derecho sobre un bloque = aparece un bot (caballo con las etiquetas "kart" y "kart_bot"
 *    sin IA + un personaje con skin verde montado encima). El cliente le pone el modelo de kart como a cualquier kart.
 *  - Fisica propia: gravedad, colisiones y escalones con Entity.move; avanza hacia el siguiente nodo de ruta (KartTrack.route)
 *    por persecucion pura, a la velocidad base del Sacred Fire amarillo (nivel 2): base de la clase x 1,32.
 *  - Quieto si no hay ninguna carrera activa en la dimension (un jugador con la Rueda activa); al activarse arranca.
 *  - Mismas reglas de checkpoints que los jugadores (copia propia de la logica: empieza al tocar el 0, vueltas, rescate si
 *    sale del area de checkpoints...) y entra en las posiciones (KartRanking). El rescate es solo del servidor (sin barra).
 *  - Si falta ruta se avisa al jugador y el bot sigue solo la que exista; sin ruta se queda quieto.
 */
public final class KartBots {
    public static final Item ITEM = register("kart_bot");

    static final String BOT_TAG = "kart_bot";
    static final double SACRED_YELLOW_MULT = 1.32;                          // Sacred Fire amarillo (nivel 2): x1,32 (como KartPhysics)
    static final double SPEED = KartClass.BALANCED.top * SACRED_YELLOW_MULT; // velocidad de crucero por defecto
    static final double ACCEL = KartClass.BALANCED.accel;
    static final float TURN = KartClass.BALANCED.turn;                       // grados por tick
    static final double LOOKAHEAD = 3.0;   // bloques por delante de la linea de la ruta a los que apunta
    static final double REACH = 1.8;       // a esta distancia (horizontal) de un nodo se da por alcanzado
    static final int STUCK_TICKS = 50;     // atascado (sin avanzar yendo a velocidad) = rescate
    static final double RESCUE_DESCEND = 0.3;

    private static final class Bot {
        String dim;
        UUID horse;
        UUID rider;
        int missing;
        // carrera
        boolean started;
        int idx;
        int lap;
        final Set<Integer> visited = new HashSet<>();
        CpBox lastCp;
        int outTicks;
        int pause;
        Vec3 lastPos;
        double progress;
        long cpTick;
        // ruta y fisica
        int target = -1;
        boolean finished;
        double vy;
        double speed;
        int stuck;
        Vec3 stuckRef;
        // rescate
        long rescueStart = -1;
        long warpTick;
        boolean warped;
        double tx, ty, tz;
        float tyaw;
    }

    private static final Map<UUID, Bot> BOTS = new HashMap<>();
    private static final Map<String, Boolean> RACE_ON = new HashMap<>();

    private KartBots() {}

    private static Item register(String path) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(KartMod.MOD_ID, path));
        return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(1)));
    }

    public static void register() {
        UseBlockCallback.EVENT.register(KartBots::onUseBlock);
        if (!KartItem.registerCreativeEntry(CreativeModeTabs.SPAWN_EGGS, () -> new ItemStack(ITEM), "Bot")) {
            System.err.println("[ctrkart] No pude anadir el Bot al creativo. Alternativa: /give @s ctrkart:kart_bot");
        }
    }

    // ------------------------------------------------------------------ aparicion

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
        if (player.getItemInHand(hand).getItem() != ITEM) {
            return InteractionResult.PASS;
        }
        if (world instanceof ServerLevel level && player instanceof ServerPlayer sp) {
            spawn(level, sp, hit.getBlockPos().relative(hit.getDirection()), player.getYRot());
        }
        return InteractionResult.SUCCESS;
    }

    private static String uuidNbt(UUID id) {
        long m = id.getMostSignificantBits();
        long l = id.getLeastSignificantBits();
        return String.format(Locale.ROOT, "[I;%d,%d,%d,%d]", (int) (m >> 32), (int) m, (int) (l >> 32), (int) l);
    }

    /** Aparece con /summon (como el item Kart): el caballo kart sin IA, el personaje verde y /ride para montarlo. */
    private static void spawn(ServerLevel level, ServerPlayer sp, BlockPos pos, float yaw) {
        MinecraftServer server = level.getServer();
        Vec3 at = Vec3.atBottomCenterOf(pos);
        CommandSourceStack src = server.createCommandSourceStack().withLevel(level).withPosition(at).withSuppressedOutput();
        UUID horseId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        String saddle = "{id:\"minecraft:saddle\",count:1}";
        server.getCommands().performPrefixedCommand(src, String.format(Locale.ROOT,
                "summon minecraft:horse %.2f %.2f %.2f {UUID:%s,Tags:[\"kart\",\"%s\"],Tame:1b,PersistenceRequired:1b,NoAI:1b,Silent:1b,"
                        + "Rotation:[%.1ff,0.0f],SaddleItem:%s,equipment:{saddle:%s}}",
                at.x, at.y, at.z, uuidNbt(horseId), BOT_TAG, yaw, saddle, saddle));
        // personaje: modelo de jugador (maniqui) con skin verde (placeholder), a la mitad de tamano como los jugadores en un kart
        server.getCommands().performPrefixedCommand(src, String.format(Locale.ROOT,
                "summon minecraft:mannequin %.2f %.2f %.2f {UUID:%s,Tags:[\"kart_bot_rider\"],Invulnerable:1b,Silent:1b,immovable:1b,"
                        + "hide_description:1b,Rotation:[%.1ff,0.0f],profile:{texture:\"ctrkart:entity/bot_green\",model:\"wide\"},"
                        + "attributes:[{id:\"minecraft:scale\",base:0.5d}]}",
                at.x, at.y + 1.0, at.z, uuidNbt(riderId), yaw));
        server.getCommands().performPrefixedCommand(src, "ride " + riderId + " mount " + horseId);

        Entity horse = level.getEntity(horseId);
        if (horse == null) {
            sp.sendSystemMessage(Component.literal("§c[Bots] No pude crear el bot (mira latest.log)."));
            System.err.println("[ctrkart] Bots: el /summon del caballo no creo la entidad.");
            return;
        }
        Bot b = new Bot();
        b.dim = level.dimension().toString();
        b.horse = horseId;
        b.rider = level.getEntity(riderId) != null ? riderId : null;
        BOTS.put(horseId, b);
        if (b.rider == null) {
            sp.sendSystemMessage(Component.literal("§e[Bots] El bot aparecio sin personaje (no pude crear el maniqui)."));
            System.err.println("[ctrkart] Bots: el /summon del maniqui no creo la entidad (minecraft:mannequin / skin ctrkart:entity/bot_green).");
        }
        sp.sendSystemMessage(Component.literal("§a[Bots] Bot colocado. Se mueve cuando haya una carrera activa (Rueda de Carrera)."));
        warn(level, sp);
    }

    // ------------------------------------------------------------------ avisos

    /** Avisa al jugador si el recorrido de los bots no esta completo. */
    public static void warn(ServerLevel level, ServerPlayer p) {
        KartTrack track = KartCheckpoints.track(level);
        int[] seq = track.seq();
        if (seq.length < 2 || seq[0] != 0) {
            p.sendSystemMessage(Component.literal("§e[Bots] Hace falta el checkpoint 0 (meta) y al menos otro checkpoint numerado."));
            return;
        }
        KartTrack.Route r = track.route();
        if (track.nodes().isEmpty()) {
            p.sendSystemMessage(Component.literal("§e[Bots] No hay recorrido: coloca Nodos de Ruta dentro de los checkpoints. Los bots se quedaran quietos."));
        } else if (r.chain().isEmpty()) {
            p.sendSystemMessage(Component.literal("§e[Bots] No hay ningun nodo en la meta (checkpoint 0): los bots se quedaran quietos."));
        } else if (!r.missing().isEmpty()) {
            p.sendSystemMessage(Component.literal("§e[Bots] Falta recorrido en el/los checkpoint(s): " + r.missing()
                    + ". Los bots solo seguiran la ruta que exista (hasta antes del checkpoint " + r.missing().get(0) + ")."));
        }
    }

    public static boolean hasBots(ServerLevel level) {
        String dim = level.dimension().toString();
        for (Bot b : BOTS.values()) {
            if (dim.equals(b.dim)) {
                return true;
            }
        }
        return false;
    }

    /** Bots con la carrera empezada, para las posiciones. */
    static List<KartRanking.Entry> entries(ServerLevel level) {
        List<KartRanking.Entry> out = new ArrayList<>();
        String dim = level.dimension().toString();
        for (Bot b : BOTS.values()) {
            if (dim.equals(b.dim) && b.started) {
                out.add(new KartRanking.Entry(b.horse, b.progress, b.cpTick));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ tick

    private static UUID riderOf(AbstractHorse horse) {
        for (Entity p : horse.getPassengers()) {
            if (!(p instanceof Player)) {
                return p.getUUID();
            }
        }
        return null;
    }

    private static void discover(ServerLevel level, String dim) {
        for (AbstractHorse h : level.getEntities(EntityTypeTest.forClass(AbstractHorse.class), e -> true)) {
            if (!KartClass.hasTag(h, BOT_TAG)) {
                continue;
            }
            Bot b = BOTS.get(h.getUUID());
            if (b == null) { // un bot que ya existia (el mundo se recargo)
                b = new Bot();
                b.dim = dim;
                b.horse = h.getUUID();
                BOTS.put(b.horse, b);
            }
            if (b.rider == null) {
                b.rider = riderOf(h);
            }
        }
    }

    private static void resetRace(Bot b) {
        b.started = false;
        b.idx = 0;
        b.lap = 0;
        b.visited.clear();
        b.lastCp = null;
        b.outTicks = 0;
        b.pause = 0;
        b.lastPos = null;
        b.progress = 0;
        b.target = -1;
        b.finished = false;
        b.stuck = 0;
        b.stuckRef = null;
        b.speed = 0;
    }

    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        String dim = level.dimension().toString();
        if (now % 20 == 0) {
            discover(level, dim);
        }
        if (BOTS.isEmpty()) {
            return;
        }
        boolean raceOn = false;
        for (ServerPlayer p : level.players()) {
            if (KartRace.isActive(p.getUUID())) {
                raceOn = true;
                break;
            }
        }
        Boolean was = RACE_ON.put(dim, raceOn);
        boolean changed = was == null || was != raceOn;
        KartTrack track = KartCheckpoints.track(level);
        KartTrack.Route route = track.route();
        Iterator<Bot> it = BOTS.values().iterator();
        while (it.hasNext()) {
            Bot b = it.next();
            if (!dim.equals(b.dim)) {
                continue;
            }
            Entity e = level.getEntity(b.horse);
            if (!(e instanceof AbstractHorse horse) || horse.isRemoved()) {
                if (++b.missing > 200) { // 10 s sin encontrarlo: eliminado
                    it.remove();
                }
                continue;
            }
            b.missing = 0;
            if (changed) { // se activo o termino la carrera: todo de cero
                if (b.rescueStart >= 0) {
                    horse.noPhysics = false;
                    b.rescueStart = -1;
                }
                resetRace(b);
            }
            tickBot(level, horse, b, track, route, raceOn, now);
        }
    }

    private static void tickBot(ServerLevel level, AbstractHorse horse, Bot b, KartTrack track, KartTrack.Route route,
                                boolean raceOn, long now) {
        if (b.rescueStart >= 0) {
            tickRescue(level, horse, b, now);
            return;
        }
        // 1) donde quiere ir
        double target = 0.0;
        if (raceOn && !b.finished && !route.chain().isEmpty()) {
            target = steer(horse, b, route);
        }
        if (b.speed < target) {
            b.speed = Math.min(target, b.speed + ACCEL);
        } else {
            b.speed = Math.max(target, b.speed - 0.06);
        }
        // 2) fisica (siempre: tambien parado le afecta la gravedad)
        Vec3 before = horse.position();
        physics(horse, b);
        alignRider(level, horse, b);
        // 3) atascado
        if (raceOn && target > 0.1) {
            if (b.stuckRef == null || horse.position().distanceToSqr(b.stuckRef) > 0.25) {
                b.stuckRef = horse.position();
                b.stuck = 0;
            } else if (++b.stuck > STUCK_TICKS && b.pause <= 0) {
                System.out.println("[ctrkart] Bots: atascado en " + horse.position() + "; rescate");
                startRescue(level, horse, b, track, now);
                return;
            }
        } else {
            b.stuck = 0;
            b.stuckRef = null;
        }
        // 4) reglas de checkpoints (solo con la carrera activa)
        if (raceOn) {
            zones(level, horse, b, track, now);
            if (b.started && b.rescueStart < 0 && track.seq().length >= 2) {
                b.progress = KartRanking.progress(b.lap, b.idx, track.seq(), track, horse.position().add(0, 0.5, 0));
            }
        }
        if (before.distanceToSqr(horse.position()) < 1.0e-12 && b.speed < 1.0e-6) {
            horse.setDeltaMovement(Vec3.ZERO);
        }
    }

    // ------------------------------------------------------------------ ruta y fisica

    private static double wrap(double d) {
        return ((d + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }

    /** El nodo de la ruta por el que empezar: el mas cercano, o el siguiente si queda detras del bot. */
    private static int initialTarget(AbstractHorse horse, List<RouteNode> chain, boolean circular) {
        Vec3 pos = horse.position();
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < chain.size(); i++) {
            Vec3 g = chain.get(i).groundPoint();
            double d = (g.x - pos.x) * (g.x - pos.x) + (g.z - pos.z) * (g.z - pos.z);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        double rad = Math.toRadians(horse.getYRot());
        Vec3 g = chain.get(best).groundPoint();
        double dot = -Math.sin(rad) * (g.x - pos.x) + Math.cos(rad) * (g.z - pos.z);
        if (dot < 0.0) {
            best = best + 1 < chain.size() ? best + 1 : (circular ? 0 : best);
        }
        return best;
    }

    /** Persecucion pura sobre la linea "nodo anterior -> nodo siguiente". Gira el kart y devuelve la velocidad que quiere. */
    private static double steer(AbstractHorse horse, Bot b, KartTrack.Route route) {
        List<RouteNode> chain = route.chain();
        int n = chain.size();
        boolean circular = route.complete();
        if (b.target < 0 || b.target >= n) {
            b.target = initialTarget(horse, chain, circular);
        }
        Vec3 pos = horse.position();
        Vec3 goal = chain.get(b.target).groundPoint();
        int prevIdx = b.target - 1 >= 0 ? b.target - 1 : (circular ? n - 1 : -1);
        double aimX = goal.x;
        double aimZ = goal.z;
        boolean passed = false;
        if (prevIdx >= 0) {
            Vec3 a = chain.get(prevIdx).groundPoint();
            double abx = goal.x - a.x;
            double abz = goal.z - a.z;
            double len2 = abx * abx + abz * abz;
            if (len2 > 1.0e-6) {
                double s = ((pos.x - a.x) * abx + (pos.z - a.z) * abz) / len2;
                double ahead = Math.min(1.0, Math.max(0.0, s) + LOOKAHEAD / Math.sqrt(len2));
                aimX = a.x + abx * ahead;
                aimZ = a.z + abz * ahead;
                passed = s >= 1.0;
            }
        }
        double dx = goal.x - pos.x;
        double dz = goal.z - pos.z;
        if (Math.sqrt(dx * dx + dz * dz) < REACH || passed) { // nodo alcanzado: al siguiente
            if (b.target + 1 < n) {
                b.target++;
            } else if (circular) {
                b.target = 0;
            } else {
                b.finished = true; // fin de la ruta que existe: se queda quieto
                return 0.0;
            }
        }
        double want = Math.toDegrees(Math.atan2(-(aimX - pos.x), aimZ - pos.z));
        double diff = wrap(want - horse.getYRot());
        double turn = Math.max(-TURN, Math.min(TURN, diff));
        float yaw = (float) (horse.getYRot() + turn);
        horse.setYRot(yaw);
        horse.setYHeadRot(yaw);
        return Math.abs(diff) > 60.0 ? SPEED * 0.55 : SPEED; // en una curva muy cerrada frena un poco
    }

    /** Gravedad, colisiones y escalones: Entity.move (el mismo que usa el juego para cualquier entidad). */
    private static void physics(AbstractHorse horse, Bot b) {
        double rad = Math.toRadians(horse.getYRot());
        double vx = -Math.sin(rad) * b.speed;
        double vz = Math.cos(rad) * b.speed;
        double y0 = horse.getY();
        horse.move(MoverType.SELF, new Vec3(vx, b.vy, vz));
        double dy = horse.getY() - y0;
        boolean grounded = b.vy < 0.0 && dy > b.vy + 1.0e-6; // bajaba y no pudo bajar todo: hay suelo
        b.vy = grounded ? -0.0784 : (b.vy - 0.08) * 0.98;
    }

    /** El personaje mira hacia donde mira el kart. */
    private static void alignRider(ServerLevel level, AbstractHorse horse, Bot b) {
        if (b.rider == null) {
            return;
        }
        Entity r = level.getEntity(b.rider);
        if (r == null) {
            return;
        }
        float yaw = horse.getYRot();
        r.setYRot(yaw);
        if (r instanceof LivingEntity le) {
            le.setYHeadRot(yaw);
            le.setYBodyRot(yaw);
        }
    }

    // ------------------------------------------------------------------ checkpoints (reglas iguales a las del jugador)

    private static void zones(ServerLevel level, AbstractHorse horse, Bot b, KartTrack track, long now) {
        int[] seq = track.seq();
        if (seq.length < 2 || seq[0] != 0) {
            return;
        }
        Vec3 pos = horse.position().add(0, 0.5, 0);
        Vec3 from = b.lastPos == null ? pos : b.lastPos;
        if (from.distanceToSqr(pos) > KartRace.MAX_STEP * KartRace.MAX_STEP) {
            from = pos;
        }
        b.lastPos = pos;
        boolean allowRescue = b.pause <= 0;
        if (b.pause > 0) {
            b.pause--;
        }
        List<CpBox> inside = new ArrayList<>();
        boolean inFree = false;
        for (CpBox c : track.near(from, pos)) {
            if (c.touchedBy(from, pos)) {
                if (c.isFree()) {
                    inFree = true;
                } else {
                    inside.add(c);
                }
            }
        }
        int n = seq.length;
        if (!b.started) {
            for (CpBox c : inside) {
                if (c.number() == 0) {
                    b.started = true;
                    b.idx = 0;
                    b.lap = 0;
                    b.visited.clear();
                    b.lastCp = c;
                    b.outTicks = 0;
                    b.cpTick = now;
                    return;
                }
            }
            return;
        }
        if (b.idx >= n) {
            b.idx = 0;
        }
        int nextIdx = (b.idx + 1) % n;
        int prevIdx = (b.idx - 1 + n) % n;
        CpBox next = null;
        CpBox same = null;
        CpBox prev = null;
        for (CpBox c : inside) {
            if (c.number() == seq[nextIdx]) {
                next = c;
            } else if (c.number() == seq[b.idx]) {
                same = c;
            } else if (c.number() == seq[prevIdx]) {
                prev = c;
            }
        }
        if (next != null) {
            if (nextIdx == 0) { // llega a la meta desde el ultimo checkpoint
                boolean all = true;
                for (int i = 1; i < n; i++) {
                    if (!b.visited.contains(seq[i])) {
                        all = false;
                    }
                }
                if (all) {
                    b.lap++;
                }
                b.visited.clear();
            } else {
                b.visited.add(seq[nextIdx]);
            }
            b.idx = nextIdx;
            b.lastCp = next;
            b.outTicks = 0;
            b.cpTick = now;
        } else if (same != null) {
            b.lastCp = same;
            b.outTicks = 0;
        } else if (prev != null) {
            if (b.idx == 0 && b.lap > 0) {
                b.lap--;
                for (int i = 1; i < n; i++) {
                    b.visited.add(seq[i]);
                }
            }
            b.idx = prevIdx;
            b.lastCp = prev;
            b.outTicks = 0;
            b.cpTick = now;
        } else if (!inside.isEmpty()) { // solo esta en zonas que no le tocan
            if (!inFree && allowRescue) {
                System.out.println("[ctrkart] Bots: rescate (zona que no corresponde) en " + pos);
                startRescue(level, horse, b, track, now);
            }
        } else if (inFree) {
            b.outTicks = 0;
        } else if (allowRescue && ++b.outTicks > KartRace.OUT_GRACE_TICKS) {
            System.out.println("[ctrkart] Bots: rescate (fuera de todas las zonas) en " + pos);
            startRescue(level, horse, b, track, now);
        }
    }

    // ------------------------------------------------------------------ rescate (solo servidor)

    private static void startRescue(ServerLevel level, AbstractHorse horse, Bot b, KartTrack track, long now) {
        int[] seq = track.seq();
        List<CpBox> boxes = track.boxes();
        if (seq.length < 2) {
            return;
        }
        CpBox target = b.lastCp;
        if (target == null || !boxes.contains(target)) {
            target = KartRace.nearestBox(boxes, seq[Math.min(b.idx, seq.length - 1)], horse.position());
        }
        if (target == null) {
            return;
        }
        double[] d = KartRace.rescueDestination(level, boxes, target, horse.position(), seq[(b.idx + 1) % seq.length], horse.getYRot());
        b.rescueStart = now;
        b.warped = false;
        b.tx = d[0];
        b.ty = d[1];
        b.tz = d[2];
        b.tyaw = (float) d[3];
        b.lastPos = null;
        b.outTicks = 0;
        b.speed = 0.0;
        b.vy = 0.0;
        b.stuck = 0;
        b.stuckRef = null;
        horse.noPhysics = true; // sin colisiones mientras dura el rescate
        horse.setDeltaMovement(Vec3.ZERO);
        KartMod.clearSuper(horse.getUUID());
    }

    private static void tickRescue(ServerLevel level, AbstractHorse horse, Bot b, long now) {
        long t = now - b.rescueStart;
        horse.setDeltaMovement(Vec3.ZERO);
        if (!b.warped) {
            if (t < KartRace.RESCUE_HOVER_TICKS + KartRace.RESCUE_LIFT_TICKS) {
                if (t >= KartRace.RESCUE_HOVER_TICKS) { // sube al cielo
                    horse.setPos(horse.getX(), horse.getY() + 1.0, horse.getZ());
                }
                level.sendParticles(ParticleTypes.END_ROD, horse.getX(), horse.getY() + 0.8, horse.getZ(), 2, 0.4, 0.5, 0.4, 0.02);
                return;
            }
            b.warped = true;
            b.warpTick = now;
            horse.setPos(b.tx, b.ty + KartRace.RESCUE_SKY, b.tz);
            horse.setYRot(b.tyaw);
            horse.setYHeadRot(b.tyaw);
            alignRider(level, horse, b);
            return;
        }
        double y = horse.getY() - RESCUE_DESCEND;
        boolean landed = y <= b.ty + 0.6 || now - b.warpTick > 20 * 15;
        if (landed) {
            horse.setPos(b.tx, b.ty, b.tz);
            horse.noPhysics = false;
            b.rescueStart = -1;
            b.pause = KartRace.RESCUE_PAUSE_TICKS;
            b.vy = 0.0;
            b.lastPos = null;
            b.target = -1; // vuelve a elegir el nodo mas cercano
            b.finished = false;
        } else {
            horse.setPos(b.tx, y, b.tz);
            level.sendParticles(ParticleTypes.END_ROD, b.tx, y + 0.8, b.tz, 1, 0.3, 0.3, 0.3, 0.01);
        }
    }
}
