package com.ctrkart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Registro de checkpoints del mundo (servidor): se guarda en ctrkart_cp.txt dentro de la carpeta del mundo y se envia a
 * los clientes (solo los de su dimension) al entrar, al cambiar de dimension y cada vez que cambia algo.
 */
public final class KartCheckpoints {
    private static final Map<String, List<CpBox>> BY_DIM = new HashMap<>();
    private static final Map<String, List<RescuePoint>> POINTS_BY_DIM = new HashMap<>();
    private static final Map<UUID, String> SENT = new HashMap<>(); // dimension a la que se envio la lista a cada jugador
    /** Cache por dimension: lista de cajas, secuencia, indice por chunk y centros. Se borra en changed() y en load(). */
    private static final Map<String, KartTrack> TRACKS = new HashMap<>();
    private static Path file;

    private KartCheckpoints() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(KartCheckpoints::load);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendTo(handler.getPlayer()));
    }

    private static String dim(ServerLevel level) {
        return level.dimension().toString();
    }

    /**
     * Los checkpoints de esa dimension ya preparados (lista, secuencia, indice por chunk, centros). Se calculan una vez y se
     * reutilizan hasta que cambie algo: TODO metodo que modifica cajas termina en changed(), que borra esta cache.
     */
    public static KartTrack track(ServerLevel level) {
        return TRACKS.computeIfAbsent(dim(level), k -> new KartTrack(BY_DIM.getOrDefault(k, List.of())));
    }

    /** Lista de checkpoints de ese mundo: una foto de SOLO LECTURA (cambia por una nueva cuando se modifica algo). */
    public static List<CpBox> list(ServerLevel level) {
        return track(level).boxes();
    }

    /** Copia de la lista de bloques de Rescate de ese mundo. */
    public static List<RescuePoint> points(ServerLevel level) {
        return new ArrayList<>(POINTS_BY_DIM.getOrDefault(dim(level), List.of()));
    }

    public static void addPoint(ServerLevel level, RescuePoint p) {
        POINTS_BY_DIM.computeIfAbsent(dim(level), k -> new ArrayList<>()).add(p);
        changed(level);
    }

    public static void removePoint(ServerLevel level, RescuePoint p) {
        List<RescuePoint> l = POINTS_BY_DIM.get(dim(level));
        if (l != null && l.remove(p)) {
            changed(level);
        }
    }

    public static void replacePoint(ServerLevel level, RescuePoint old, RescuePoint now) {
        List<RescuePoint> l = POINTS_BY_DIM.get(dim(level));
        if (l == null) {
            return;
        }
        int i = l.indexOf(old);
        if (i >= 0) {
            l.set(i, now);
            changed(level);
        }
    }

    public static void add(ServerLevel level, CpBox box) {
        BY_DIM.computeIfAbsent(dim(level), k -> new ArrayList<>()).add(box);
        changed(level);
    }

    /** Anade varias cajas de una vez (un area dibujada): se guarda y se envia una sola vez. */
    public static void addAll(ServerLevel level, List<CpBox> boxes) {
        BY_DIM.computeIfAbsent(dim(level), k -> new ArrayList<>()).addAll(boxes);
        changed(level);
    }

    /** Numero de grupo libre para un area dibujada (los checkpoints normales son grupo 0). */
    public static int newGroup() {
        int max = 0;
        for (List<CpBox> l : BY_DIM.values()) {
            for (CpBox b : l) {
                max = Math.max(max, b.group());
            }
        }
        return max + 1;
    }

    /** Borra el area dibujada completa (todas sus partes). */
    public static void removeGroup(ServerLevel level, int group) {
        List<CpBox> l = BY_DIM.get(dim(level));
        if (l != null && l.removeIf(b -> b.group() == group)) {
            changed(level);
        }
    }

    /** Cambia el numero de un checkpoint; si es un area dibujada, de todas sus partes. */
    public static void renumber(ServerLevel level, CpBox box, int n) {
        if (box.group() == 0) {
            replace(level, box, box.withNumber(n));
            return;
        }
        List<CpBox> l = BY_DIM.get(dim(level));
        if (l == null) {
            return;
        }
        boolean any = false;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).group() == box.group()) {
                l.set(i, l.get(i).withNumber(n));
                any = true;
            }
        }
        if (any) {
            changed(level);
        }
    }

    /** Estira en VERTICAL un area dibujada (mueve su cara de arriba o de abajo, en todas sus partes). Devuelve el alto nuevo o -1 si no se pudo. */
    public static int stretchGroup(ServerLevel level, int group, int side, int target) {
        List<CpBox> l = BY_DIM.get(dim(level));
        if (l == null) {
            return -1;
        }
        int height = -1;
        for (CpBox b : l) {
            if (b.group() == group) {
                CpBox n = b.withBound(1, side, target);
                if (n == null) {
                    return -1;
                }
                height = n.size(1);
            }
        }
        if (height < 0) {
            return -1;
        }
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).group() == group) {
                l.set(i, l.get(i).withBound(1, side, target));
            }
        }
        changed(level);
        return height;
    }

    public static void remove(ServerLevel level, CpBox box) {
        List<CpBox> l = BY_DIM.get(dim(level));
        if (l != null && l.remove(box)) {
            changed(level);
        }
    }

    public static void replace(ServerLevel level, CpBox old, CpBox now) {
        List<CpBox> l = BY_DIM.get(dim(level));
        if (l == null) {
            return;
        }
        int i = l.indexOf(old);
        if (i >= 0) {
            l.set(i, now);
            changed(level);
        }
    }

    private static void changed(ServerLevel level) {
        TRACKS.remove(dim(level)); // invalida la cache (todos los metodos que modifican cajas o puntos pasan por aqui)
        save();
        for (ServerPlayer p : level.players()) {
            sendTo(p);
        }
    }

    /** Cada tick: si un jugador cambio de dimension (o acaba de entrar), se le envia la lista de su dimension. */
    public static void tickSync(ServerLevel level) {
        String d = dim(level);
        for (ServerPlayer p : level.players()) {
            if (!d.equals(SENT.get(p.getUUID()))) {
                sendTo(p);
            }
        }
    }

    public static void sendTo(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level)) {
            return;
        }
        SENT.put(p.getUUID(), dim(level));
        List<Integer> data = new ArrayList<>();
        for (CpBox b : BY_DIM.getOrDefault(dim(level), List.of())) {
            b.writeTo(data);
        }
        ServerPlayNetworking.send(p, new KartCpPayload(KartCpPayload.SYNC, data));
        List<Integer> pts = new ArrayList<>();
        for (RescuePoint r : POINTS_BY_DIM.getOrDefault(dim(level), List.of())) {
            pts.add(r.x());
            pts.add(r.y());
            pts.add(r.z());
            pts.add(r.yaw());
        }
        ServerPlayNetworking.send(p, new KartCpPayload(KartCpPayload.POINTS, pts));
    }

    private static void load(MinecraftServer server) {
        BY_DIM.clear();
        POINTS_BY_DIM.clear();
        SENT.clear();
        TRACKS.clear();
        try {
            file = server.getWorldPath(LevelResource.ROOT).resolve("ctrkart_cp.txt");
            int n = 0;
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    String[] p = line.split("\t");
                    if (p.length == 6 && p[1].equals("R")) { // bloque de Rescate: dim R x y z giro
                        POINTS_BY_DIM.computeIfAbsent(p[0], k -> new ArrayList<>()).add(new RescuePoint(
                                Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()), Integer.parseInt(p[4].trim()),
                                Integer.parseInt(p[5].trim())));
                    } else if (p.length == 8 || p.length == 9) { // la 9a columna (grupo) solo existe en las areas dibujadas
                        BY_DIM.computeIfAbsent(p[0], k -> new ArrayList<>()).add(new CpBox(
                                Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()),
                                Integer.parseInt(p[4].trim()), Integer.parseInt(p[5].trim()), Integer.parseInt(p[6].trim()),
                                Integer.parseInt(p[7].trim()), p.length == 9 ? Integer.parseInt(p[8].trim()) : 0));
                        n++;
                    }
                }
            }
            System.out.println("[ctrkart] Checkpoints: " + n + " cargados.");
        } catch (IOException | RuntimeException ex) {
            System.err.println("[ctrkart] Checkpoints: no pude leer el registro: " + ex);
        }
    }

    private static void save() {
        if (file == null) {
            return;
        }
        try {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, List<CpBox>> e : BY_DIM.entrySet()) {
                for (CpBox b : e.getValue()) {
                    lines.add(e.getKey() + "\t" + b.number() + "\t" + b.x1() + "\t" + b.y1() + "\t" + b.z1() + "\t"
                            + b.x2() + "\t" + b.y2() + "\t" + b.z2() + (b.group() != 0 ? "\t" + b.group() : ""));
                }
            }
            for (Map.Entry<String, List<RescuePoint>> e : POINTS_BY_DIM.entrySet()) {
                for (RescuePoint r : e.getValue()) {
                    lines.add(e.getKey() + "\tR\t" + r.x() + "\t" + r.y() + "\t" + r.z() + "\t" + r.yaw());
                }
            }
            Files.write(file, lines);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[ctrkart] Checkpoints: no pude guardar el registro: " + ex);
        }
    }
}
