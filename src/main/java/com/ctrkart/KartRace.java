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
    static final int OUT_GRACE_TICKS = 30;     // fuera de todas las zonas: tiempo antes del rescate (1,5 s)
    static final int DISMOUNT_RESET_TICKS = 100;
    static final int RESCUE_HOVER_TICKS = 45;
    static final int RESCUE_LIFT_TICKS = 30;
    static final double RESCUE_SKY = 24.0;     // altura sobre el destino desde la que baja
    static final int RESCUE_PAUSE_TICKS = 40;  // tras el rescate: sin comprobaciones

    private static final class Rescue {
        long startTick;
        boolean warped;
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
        Rescue rescue;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private KartRace() {}

    public static boolean isRescuing(UUID id) {
        State s = STATES.get(id);
        return s != null && s.rescue != null;
    }

    public static void tick(ServerLevel level) {
        KartCheckpoints.tickSync(level);
        for (ServerPlayer p : level.players()) {
            UUID id = p.getUUID();
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
            if (st.pause > 0) {
                st.pause--;
                continue;
            }
            tickRace(level, p, horse, st);
        }
    }

    // ------------------------------------------------------------------ carrera

    private static int[] sequence(List<CpBox> boxes) {
        TreeSet<Integer> set = new TreeSet<>();
        for (CpBox b : boxes) {
            if (!b.isFree()) {
                set.add(b.number());
            }
        }
        int[] seq = new int[set.size()];
        int i = 0;
        for (int n : set) {
            seq[i++] = n;
        }
        return seq;
    }

    private static void tickRace(ServerLevel level, ServerPlayer p, AbstractHorse horse, State st) {
        List<CpBox> boxes = KartCheckpoints.list(level);
        int[] seq = sequence(boxes);
        if (seq.length < 2 || seq[0] != 0) {
            return; // hace falta la meta (0) y al menos otro checkpoint
        }
        Vec3 pos = horse.position().add(0, 0.5, 0);
        List<CpBox> inside = new ArrayList<>();
        boolean inFree = false;
        for (CpBox b : boxes) {
            if (b.contains(pos)) {
                if (b.isFree()) {
                    inFree = true;
                } else {
                    inside.add(b);
                }
            }
        }
        int n = seq.length;

        if (!st.started) {
            for (CpBox b : inside) {
                if (b.number() == 0) {
                    st.started = true;
                    st.idx = 0;
                    st.lap = 0;
                    st.visited.clear();
                    st.lastCp = b;
                    st.outTicks = 0;
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
            sendRace(p, st, n);
        } else if (!inside.isEmpty()) { // solo estas en zonas que no te tocan
            if (!inFree) {
                startRescue(level, p, horse, st, boxes, seq);
            }
        } else if (inFree) {
            st.outTicks = 0;
        } else if (++st.outTicks > OUT_GRACE_TICKS) {
            startRescue(level, p, horse, st, boxes, seq);
        }
    }

    private static void sendRace(ServerPlayer p, State st, int total) {
        List<Integer> d = List.of(st.lap, st.idx, total, st.started ? 1 : 0);
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

    private static void startRescue(ServerLevel level, ServerPlayer p, AbstractHorse horse, State st, List<CpBox> boxes, int[] seq) {
        CpBox target = st.lastCp;
        if (target == null || !boxes.contains(target)) {
            target = nearest(boxes, seq[Math.min(st.idx, seq.length - 1)], horse.position());
        }
        if (target == null) {
            return;
        }
        Vec3 c = target.center();
        Rescue r = new Rescue();
        r.startTick = level.getGameTime();
        st.rescue = r;
        st.outTicks = 0;
        // Si ese checkpoint tiene un bloque de Rescate, el kart aparece ahi y mirando hacia donde lo orientaste.
        // Si no, (como antes) en el centro del checkpoint y mirando hacia el siguiente.
        RescuePoint spot = null;
        double best = Double.MAX_VALUE;
        for (RescuePoint pt : KartCheckpoints.points(level)) {
            for (CpBox b : boxes) {
                if (!b.isFree() && b.number() == target.number() && pt.inside(b)) {
                    double d = horse.position().distanceToSqr(pt.x() + 0.5, pt.y(), pt.z() + 0.5);
                    if (d < best) {
                        best = d;
                        spot = pt;
                    }
                    break;
                }
            }
        }
        float yaw = horse.getYRot();
        if (spot != null) {
            r.tx = spot.x() + 0.5;
            r.ty = spot.y();
            r.tz = spot.z() + 0.5;
            yaw = spot.yaw();
        } else {
            r.tx = c.x;
            r.tz = c.z;
            r.ty = landingY(level, target);
            CpBox nx = nearest(boxes, seq[(st.idx + 1) % seq.length], c);
            if (nx != null) {
                Vec3 nc = nx.center();
                yaw = (float) Math.toDegrees(Math.atan2(-(nc.x - c.x), nc.z - c.z));
            }
        }
        horse.noPhysics = true; // sin colisiones mientras dura el rescate (en el servidor y en el cliente)
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
        boolean landed = horse.getY() <= r.ty + 0.6;
        boolean timeout = now - r.warpTick > 20 * 15;
        if (landed || timeout) {
            horse.setPos(r.tx, r.ty, r.tz);
            horse.noPhysics = false;
            st.rescue = null;
            st.pause = RESCUE_PAUSE_TICKS;
            ServerPlayNetworking.send(p, new KartCpPayload(KartCpPayload.RESCUE_END, List.of()));
        }
    }
}
