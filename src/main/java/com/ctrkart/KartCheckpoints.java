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
    private static Path file;

    private KartCheckpoints() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(KartCheckpoints::load);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendTo(handler.getPlayer()));
    }

    private static String dim(ServerLevel level) {
        return level.dimension().toString();
    }

    /** Copia de la lista de checkpoints de ese mundo. */
    public static List<CpBox> list(ServerLevel level) {
        return new ArrayList<>(BY_DIM.getOrDefault(dim(level), List.of()));
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
                    } else if (p.length == 8) {
                        BY_DIM.computeIfAbsent(p[0], k -> new ArrayList<>()).add(new CpBox(
                                Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()),
                                Integer.parseInt(p[4].trim()), Integer.parseInt(p[5].trim()), Integer.parseInt(p[6].trim()),
                                Integer.parseInt(p[7].trim())));
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
                            + b.x2() + "\t" + b.y2() + "\t" + b.z2());
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
