package com.ctrkart;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse; // si no existe: ...animal.horse.AbstractHorse
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Carrera con checkpoints (servidor).
 *
 * Los numeros que existen (sin contar las zonas libres) forman el recorrido: 0 (meta), luego los demas de menor a mayor,
 * y otra vez 0. La carrera empieza al tocar el 0. Para cada jugador que conduce un kart:
 *  - Entrar en el SIGUIENTE checkpoint: avanza el contador. Entrar al que acabas de recorrer, o seguir en el mismo: nada.
 *    Entrar en el ANTERIOR: retrocedes y el contador baja.
 *  - Entrar en cualquier otro checkpoint, o quedarte fuera de todas las zonas mas de OUT_GRACE_TICKS: RESCATE al centro del
 *    ultimo checkpoint recorrido. Dentro de una "zona sin restricciones" no se aplican las reglas.
 *  - Al volver al 0 desde el ultimo checkpoint: si se tocaron todos los demas, cuenta una vuelta; si no, no cuenta.
 *  - Si te bajas del kart mas de 5 segundos, la carrera se reinicia (empiezas de nuevo al tocar el 0).
 *
 * RESCATE (el cliente pone las particulas y mueve al kart; el servidor lleva el reloj):
 *  1) t 0..45: el kart se queda quieto mientras bajan particulas arcoiris; 2) t 45..75: sube al cielo;
 *  3) t 75: se coloca sobre el destino, en el cielo; 4) baja lento hasta el bloque de Rescate del checkpoint (o su centro si no tiene)
 *     y mirando hacia donde esta orientado (mantener ESPACIO carga la barra).
 */
public final class KartRace {
    static final double MAX_STEP = 16.0;       // un kart no recorre mas que esto en un tick: mas es un teletransporte, no un trayecto
    static final int OUT_GRACE_TICKS = 30;     // fuera de todas las zonas: tiempo antes del rescate (1,5 s)
    static final int DISMOUNT_RESET_TICKS = 100;
    static final int RESCUE_HOVER_TICKS = 45;
    static final int RESCUE_LIFT_TICKS = 30;
    static final double RESCUE_SKY = 24.0;     // altura sobre el destino desde la que baja
    static final int RESCUE_PAUSE_TICKS = 40;  // tras el rescate: sin comprobaciones
    static final double RESCUE_COLLIDE_HEIGHT = 1.0; // a esta altura sobre el bloque de Rescate vuelven las colisiones

    private static final class Rescue {
        long startTick;
        boolean warped;
        boolean collide; // true = ya se recupero la colision
        long warpTick;
        double tx, ty, tz;
    }

    private static final class State {
        boolean started;
        int idx;
        int lap;
        final Set<Integer> visited = new HashSet<>();
        CpBox lastCp;
        int outTicks;
        int dismount;
        int pause;
        Vec3 lastPos; // posicion del kart en el tick anterior (para no "saltarse" un checkpoint fino a mucha velocidad)
        Rescue rescue;
        double progress; // progreso en la carrera (ver KartRanking.progress); durante un rescate se queda el ultimo valido
        long cpTick;     // tick en que llego a su ultimo checkpoint (desempate de posiciones)
        int place;       // puesto actual (1 = primero) y corredores totales, tal como se enviaron al cliente
        int racers;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    /** Jugadores que activaron la Rueda de Carrera: solo para ellos cuentan los checkpoints y las vueltas. */
    private static final Set<UUID> ACTIVE = new HashSet<>();

    private KartRace() {}

    public static boolean isActive(UUID id) {
        return ACTIVE.contains(id);
    }

    /**
     * Activa la carrera del jugador (Rueda de Carrera): lo lleva al checkpoint 0 (al bloque de Rescate de la meta si
     * lo hay) y empieza de cero. Devuelve false si no se pudo (no hay checkpoint 0).
     */
    public static boolean activate(ServerLevel level, ServerPlayer p) {
        KartTrack track = KartCheckpoints.track(level);
        List<CpBox> boxes = track.boxes();
        CpBox start = null;
        for (CpBox b : boxes) {
            if (b.number() == 0) {
                start = b;
                break;
            }
        }
        if (start == null) {
            p.sendSystemMessage(Component.literal("§c[Carrera] No hay un checkpoint 0 (meta) en esta dimension."));
            return false;
        }
        UUID id = p.getUUID();
        ACTIVE.add(id);
        STATES.remove(id);
        ServerPlayNetworking.send(p, new KartCpPayload(KartCpPayload.RACE, List.of(0, 0, 0, 0, 0, 0)));
        Entity v = p.getVehicle();
        if (v instanceof AbstractHorse horse && KartMod.serverClass(horse) >= 0) {
            int[] seq = track.seq();
            Dest dst = destination(level, boxes, start, horse.position(), seq.length > 1 ? seq[1] : -1, horse.getYRot());
            horse.setPos(dst.x(), dst.y(), dst.z());
            List<Integer> d = List.of(horse.getId(), (int) Math.round(dst.x() * 1000), (int) Math.round(dst.y() * 1000),
                    (int) Math.round(dst.z() * 1000), Math.round(dst.yaw() * 10));
            ServerPlayNetworking.send(p, new KartCpPayload(KartCpPayload.PLACE, d));
            p.sendSystemMessage(Component.literal("§a[Carrera] Activada. Estas en la meta (checkpoint 0)."));
        } else {
            p.sendSystemMessage(Component.literal("§a[Carrera] Activada. Montate en un kart y entra al checkpoint 0 para empezar."));
        }
        return true;
    }

    /** Desactiva la carrera del jugador: los checkpoints y las vueltas dejan de contarse. */
    public static void deactivate(ServerPlayer p, boolean notify) {
        UUID id = p.getUUID();
        ACTIVE.remove(id);
        State st = STATES.remove(id);
        if (st != null && st.rescue != null && p.getVehicle() instanceof AbstractHorse horse) {
            horse.noPhysics = false; // se corta el rescate
            ServerPlayNetworking.send(p, new KartCpPayload(KartCpPayload.RESCUE_END, List.of()));
        }
        ServerPlayNetworking.send(p, new KartCpPayload(KartCpPayload.RACE, List.of(0, 0, 0, 0, 0, 0)));
        if (notify) {
            p.sendSystemMessage(Component.literal("§e[Carrera] Desactivada: los checkpoints y las vueltas ya no cuentan."));
        }
    }

    private static boolean hasWheel(ServerPlayer p) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (p.getInventory().getItem(i).getItem() == KartRaceItem.WHEEL_ON) {
                return true;
            }
        }
        return false;
    }

    public static boolean isRescuing(UUID id) {
        State s = STATES.get(id);
        return s != null && s.rescue != null;
    }

    public static void tick(ServerLevel level) {
        KartCheckpoints.tickSync(level);
        for (ServerPlayer p : level.players()) {
            UUID id = p.getUUID();
            if (!ACTIVE.contains(id)) {
                continue;
            }
            if (level.getGameTime() % 20 == 0 && !hasWheel(p)) { // perdiste la rueda activa: se desactiva sola
                deactivate(p, true);
                continue;
            }
            Entity v = p.getVehicle();
            State st = STATES.get(id);
            if (!(v instanceof AbstractHorse horse) || KartMod.serverClass(horse) < 0) {
                if (st != null) {
                    if (st.rescue != null) {
                        st.rescue = null; // te bajaste en pleno rescate
                    }
                    if (++st.dismount > DISMOUNT_RESET_TICKS) {
                        STATES.remove(id);
                    }
                }
                continue;
            }
            if (st == null) {
                st = new State();
                STATES.put(id, st);
            }
            st.dismount = 0;
            if (st.rescue != null) {
                tickRescue(level, p, horse, st);
                continue;
            }
            // Justo tras un rescate no se rescata otra vez (allowRescue = false), pero SI se siguen contando los checkpoints:
            // antes se ignoraba todo durante ese tiempo y, con la velocidad de salida, el kart podia pasar el siguiente
            // checkpoint sin que se notara y luego se le rescataba por "saltarselo".
            boolean allowRescue = st.pause <= 0;
            if (st.pause > 0) {
                st.pause--;
            }
            tickRace(level, p, horse, st, allowRescue);
            if (st.started && st.rescue == null) { // durante un rescate se queda el ultimo progreso valido
                KartTrack track = KartCheckpoints.track(level);
                if (track.seq().length >= 2) {
                    st.progress = KartRanking.progress(st.lap, st.idx, track.seq(), track, horse.position().add(0, 0.5, 0));
                }
            }
        }
        updatePlaces(level);
    }

    /** Ordena a los corredores de esta dimension (con la carrera activa y ya empezada) y avisa a cada uno de su puesto. */
    private static void updatePlaces(ServerLevel level) {
        List<ServerPlayer> racers = new ArrayList<>();
        List<KartRanking.Entry> entries = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            State st = ACTIVE.contains(p.getUUID()) ? STATES.get(p.getUUID()) : null;
            if (st != null && st.started) {
                racers.add(p);
                entries.add(new KartRanking.Entry(p.getUUID(), st.progress, st.cpTick));
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        KartRanking.sort(entries);
        int total = KartCheckpoints.track(level).seq().length;
        for (int i = 0; i < entries.size(); i++) {
            UUID id = entries.get(i).id();
            State st = STATES.get(id);
            if (st.place != i + 1 || st.racers != entries.size()) { // solo se envia cuando cambia
                st.place = i + 1;
                st.racers = entries.size();
                for (ServerPlayer p : racers) {
                    if (p.getUUID().equals(id)) {
                        sendRace(p, st, total);
                        break;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ carrera

    private static void tickRace(ServerLevel level, ServerPlayer p, AbstractHorse horse, State st, boolean allowRescue) {
        KartTrack track = KartCheckpoints.track(level); // cache: solo se recalcula cuando cambian los checkpoints
        List<CpBox> boxes = track.boxes();
        int[] seq = track.seq();
        if (seq.length < 2 || seq[0] != 0) {
            return; // hace falta la meta (0) y al menos otro checkpoint
        }
        // Se miran todas las zonas que toco el kart en este tick (el camino entre la posicion anterior y la actual),
        // no solo el punto final: a mucha velocidad un checkpoint fino se podia "saltar" entre dos ticks.
        // Test exacto del segmento (CpBox.touchedBy) y solo contra las cajas de los chunks que cruza el trayecto.
        Vec3 pos = horse.position().add(0, 0.5, 0);
        Vec3 from = st.lastPos == null ? pos : st.lastPos;
        if (from.distanceToSqr(pos) > MAX_STEP * MAX_STEP) {
            from = pos; // salto enorme (teletransporte...): no es un trayecto, no se "atraviesan" las zonas del medio
        }
        st.lastPos = pos;
        List<CpBox> inside = new ArrayList<>();
        boolean inFree = false;
        for (CpBox b : track.near(from, pos)) {
            if (b.touchedBy(from, pos)) {
                if (b.isFree()) {
                    inFree = true;
                } else {
                    inside.add(b);
                }
            }
        }
        int n = seq.length;
        long now = level.getGameTime();

        if (!st.started) {
            for (CpBox b : inside) {
                if (b.number() == 0) {
                    st.started = true;
                    st.idx = 0;
                    st.lap = 0;
                    st.visited.clear();
                    st.lastCp = b;
                    st.outTicks = 0;
                    st.cpTick = now;
                    sendRace(p, st, n);
                    return;
                }
            }
            return;
        }
        if (st.idx >= n) { // cambio la lista de checkpoints
            st.idx = 0;
        }
        int nextIdx = (st.idx + 1) % n;
        int prevIdx = (st.idx - 1 + n) % n;
        CpBox next = null;
        CpBox same = null;
        CpBox prev = null;
        for (CpBox b : inside) {
            if (b.number() == seq[nextIdx]) {
                next = b;
            } else if (b.number() == seq[st.idx]) {
                same = b;
            } else if (b.number() == seq[prevIdx]) {
                prev = b;
            }
        }
        if (next != null) {
            if (nextIdx == 0) { // llegas a la meta desde el ultimo checkpoint
                boolean all = true;
                for (int i = 1; i < n; i++) {
                    if (!st.visited.contains(seq[i])) {
                        all = false;
                    }
                }
                if (all) {
                    st.lap++;
                }
                st.visited.clear();
            } else {
                st.visited.add(seq[nextIdx]);
            }
            st.idx = nextIdx;
            st.lastCp = next;
            st.outTicks = 0;
            st.cpTick = now;
            sendRace(p, st, n);
        } else if (same != null) {
            st.lastCp = same;
            st.outTicks = 0;
        } else if (prev != null) {
            if (st.idx == 0) { // retrocedes sobre la meta: se deshace la vuelta
                if (st.lap > 0) {
                    st.lap--;
                    for (int i = 1; i < n; i++) {
                        st.visited.add(seq[i]);
                    }
                }
            }
            st.idx = prevIdx;
            st.lastCp = prev;
            st.outTicks = 0;
            st.cpTick = now;
            sendRace(p, st, n);
        } else if (!inside.isEmpty()) { // solo estas en zonas que no te tocan
            if (!inFree && allowRescue) {
                logRescue("zona que no corresponde", st, seq, inside, pos);
                startRescue(level, p, horse, st, boxes, seq);
            }
        } else if (inFree) {
            st.outTicks = 0;
        } else if (allowRescue && ++st.outTicks > OUT_GRACE_TICKS) {
            logRescue("fuera de todas las zonas", st, seq, inside, pos);
            startRescue(level, p, horse, st, boxes, seq);
        }
    }

    /** Deja en latest.log por que se activo un rescate (para encontrar rescates que no deberian pasar). */
    private static void logRescue(String why, State st, int[] seq, List<CpBox> inside, Vec3 pos) {
        StringBuilder sb = new StringBuilder();
        for (CpBox b : inside) {
            sb.append(b.number()).append("(area=").append(b.group()).append(" y=").append(b.y1()).append("..").append(b.y2()).append(") ");
        }
        System.out.println("[ctrkart] Rescate: " + why + " | esperaba el checkpoint " + seq[Math.min(st.idx, seq.length - 1)]
                + " | dentro de: " + (sb.length() == 0 ? "nada" : sb.toString().trim())
                + String.format(java.util.Locale.ROOT, " | pos %.2f %.2f %.2f", pos.x, pos.y, pos.z));
    }

    private static void sendRace(ServerPlayer p, State st, int total) {
        List<Integer> d = List.of(st.lap, st.idx, total, st.started ? 1 : 0, st.place, st.racers);
        ServerPlayNetworking.send(p, new KartCpPayload(KartCpPayload.RACE, d));
    }

    // ------------------------------------------------------------------ rescate

    private static CpBox nearest(List<CpBox> boxes, int number, Vec3 ref) {
        CpBox best = null;
        double bd = Double.MAX_VALUE;
        for (CpBox b : boxes) {
            if (b.number() == number) {
                double d = b.center().distanceToSqr(ref);
                if (d < bd) {
                    bd = d;
                    best = b;
                }
            }
        }
        return best;
    }

    /** Altura de aterrizaje: la primera posicion libre con suelo debajo, mirando de arriba abajo en el centro de la caja. */
    private static double landingY(ServerLevel level, CpBox box) {
        Vec3 c = box.center();
        int cx = (int) Math.floor(c.x);
        int cz = (int) Math.floor(c.z);
        for (int y = box.y2(); y >= box.y1(); y--) {
            BlockState here = level.getBlockState(new BlockPos(cx, y, cz));
            BlockState below = level.getBlockState(new BlockPos(cx, y - 1, cz));
            if (here.isAir() && !below.isAir()) {
                return y;
            }
        }
        return box.y1();
    }

    private record Dest(double x, double y, double z, float yaw) {}

    /** Donde aparece el kart en un checkpoint: su bloque de Rescate (el mas cercano a "ref") o, si no hay, el centro. */
    private static Dest destination(ServerLevel level, List<CpBox> boxes, CpBox target, Vec3 ref, int nextNumber, float defaultYaw) {
        RescuePoint spot = null;
        double best = Double.MAX_VALUE;
        for (RescuePoint pt : KartCheckpoints.points(level)) {
            for (CpBox b : boxes) {
                if (!b.isFree() && b.number() == target.number() && pt.inside(b)) {
                    double d = ref.distanceToSqr(pt.x() + 0.5, pt.y(), pt.z() + 0.5);
                    if (d < best) {
                        best = d;
                        spot = pt;
                    }
                    break;
                }
            }
        }
        if (spot != null) {
            return new Dest(spot.x() + 0.5, spot.y(), spot.z() + 0.5, spot.yaw());
        }
        Vec3 c = target.center();
        float yaw = defaultYaw;
        CpBox nx = nextNumber >= 0 ? nearest(boxes, nextNumber, c) : null;
        if (nx != null) {
            Vec3 nc = nx.center();
            yaw = (float) Math.toDegrees(Math.atan2(-(nc.x - c.x), nc.z - c.z));
        }
        return new Dest(c.x, landingY(level, target), c.z, yaw);
    }

    private static void startRescue(ServerLevel level, ServerPlayer p, AbstractHorse horse, State st, List<CpBox> boxes, int[] seq) {
        CpBox target = st.lastCp;
        if (target == null || !boxes.contains(target)) {
            target = nearest(boxes, seq[Math.min(st.idx, seq.length - 1)], horse.position());
        }
        if (target == null) {
            return;
        }
        Rescue r = new Rescue();
        r.startTick = level.getGameTime();
        st.rescue = r;
        st.outTicks = 0;
        st.lastPos = null;
        // Si ese checkpoint tiene un bloque de Rescate, el kart aparece ahi y mirando hacia donde lo orientaste.
        // Si no, en el centro del checkpoint y mirando hacia el siguiente.
        Dest dst = destination(level, boxes, target, horse.position(), seq[(st.idx + 1) % seq.length], horse.getYRot());
        r.tx = dst.x();
        r.ty = dst.y();
        r.tz = dst.z();
        float yaw = dst.yaw();
        horse.noPhysics = true; // sin colisiones mientras dura el rescate (se recuperan a 1 bloque sobre el bloque de Rescate)
        KartMod.clearSuper(horse.getUUID()); // y se pierde el Super Motor
        horse.setDeltaMovement(Vec3.ZERO);
        List<Integer> d = List.of(horse.getId(), (int) Math.round(r.tx * 1000), (int) Math.round(r.ty * 1000),
                (int) Math.round(r.tz * 1000), Math.round(yaw * 10));
        ServerPlayNetworking.send(p, new KartCpPayload(KartCpPayload.RESCUE_START, d));
    }

    private static void tickRescue(ServerLevel level, ServerPlayer p, AbstractHorse horse, State st) {
        Rescue r = st.rescue;
        long now = level.getGameTime();
        if (!r.warped) {
            if (now - r.startTick >= RESCUE_HOVER_TICKS + RESCUE_LIFT_TICKS) {
                r.warped = true;
                r.warpTick = now;
                double sy = r.ty + RESCUE_SKY;
                horse.setPos(r.tx, sy, r.tz);
                List<Integer> d = List.of((int) Math.round(r.tx * 1000), (int) Math.round(sy * 1000), (int) Math.round(r.tz * 1000));
                ServerPlayNetworking.send(p, new KartCpPayload(KartCpPayload.RESCUE_WARP, d));
            }
            return;
        }
        if (!r.collide && horse.getY() <= r.ty + RESCUE_COLLIDE_HEIGHT) {
            r.collide = true;
            horse.noPhysics = false; // el ultimo bloque de la bajada ya choca con el suelo
        }
        boolean landed = horse.getY() <= r.ty + 0.6;
        boolean timeout = now - r.warpTick > 20 * 15;
        if (landed || timeout) {
            horse.setPos(r.tx, r.ty, r.tz);
            horse.noPhysics = false;
            st.rescue = null;
            st.lastPos = null;
            st.pause = RESCUE_PAUSE_TICKS;
            ServerPlayNetworking.send(p, new KartCpPayload(KartCpPayload.RESCUE_END, List.of()));
        }
    }
}
