package com.ctrkart;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.world.phys.Vec3;

/**
 * Los checkpoints de una dimension, ya preparados para la carrera. Es INMUTABLE: cuando cambia algo se crea otro
 * (KartCheckpoints.changed() borra el anterior de la cache).
 *  - boxes: la lista de cajas (solo lectura).
 *  - seq: los numeros del recorrido (sin zonas libres), de menor a mayor.
 *  - indice espacial: cada caja se anota en TODOS los chunks (x>>4, z>>4) que ocupa; las cajas enormes (mas de 64 chunks)
 *    van aparte y se prueban siempre. Las zonas libres y las areas dibujadas (varias cajas con el mismo numero) son cajas
 *    normales: se indexan igual.
 *  - centros: de cada checkpoint numerado (para el progreso de la carrera): un area dibujada (group > 0) = UN centro (promedio
 *    de sus cajas, ponderado por volumen); una caja suelta (group 0) = su propio centro.
 */
public final class KartTrack {
    public static final KartTrack EMPTY = new KartTrack(List.of());

    private static final int BIG_CHUNKS = 64;       // cajas que ocupan mas chunks que esto: no se indexan
    private static final int MAX_QUERY_CHUNKS = 256; // trayectos mas largos que esto: se prueban todas las cajas

    /** Centro de un checkpoint numerado. */
    public record Center(int number, double x, double y, double z) {
        double distSqr(Vec3 p) {
            double dx = x - p.x;
            double dy = y - p.y;
            double dz = z - p.z;
            return dx * dx + dy * dy + dz * dz;
        }

        public Vec3 vec() {
            return new Vec3(x, y, z);
        }
    }

    private final List<CpBox> boxes;
    private final int[] seq;
    private final Map<Long, int[]> chunks = new HashMap<>();
    private final int[] big;
    private final Map<Integer, List<Center>> centers = new HashMap<>();
    private final int[] mark; // para no repetir una caja que esta en varios chunks dentro de una consulta
    private int gen;

    public KartTrack(List<CpBox> source) {
        this.boxes = Collections.unmodifiableList(new ArrayList<>(source));
        this.mark = new int[boxes.size()];

        TreeSet<Integer> set = new TreeSet<>();
        for (CpBox b : boxes) {
            if (!b.isFree()) {
                set.add(b.number());
            }
        }
        seq = new int[set.size()];
        int k = 0;
        for (int n : set) {
            seq[k++] = n;
        }

        Map<Long, List<Integer>> tmp = new HashMap<>();
        List<Integer> bigList = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            CpBox b = boxes.get(i);
            int cx1 = b.x1() >> 4;
            int cx2 = (b.x2() + 1) >> 4; // +1: contains() llega hasta x2 + 1 (sin incluirlo); asi nunca se queda corto
            int cz1 = b.z1() >> 4;
            int cz2 = (b.z2() + 1) >> 4;
            if ((long) (cx2 - cx1 + 1) * (cz2 - cz1 + 1) > BIG_CHUNKS) {
                bigList.add(i);
                continue;
            }
            for (int cx = cx1; cx <= cx2; cx++) {
                for (int cz = cz1; cz <= cz2; cz++) {
                    tmp.computeIfAbsent(chunkKey(cx, cz), x -> new ArrayList<>()).add(i);
                }
            }
        }
        for (Map.Entry<Long, List<Integer>> e : tmp.entrySet()) {
            chunks.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
        big = bigList.stream().mapToInt(Integer::intValue).toArray();

        // centros: un area dibujada (group > 0) = promedio ponderado de sus cajas; una caja suelta = su centro
        Map<Integer, double[]> groups = new HashMap<>(); // group -> {numero, suma vol*x, y, z, volumen}
        for (CpBox b : boxes) {
            if (b.isFree()) {
                continue;
            }
            Vec3 c = b.center();
            if (b.group() == 0) {
                centers.computeIfAbsent(b.number(), x -> new ArrayList<>()).add(new Center(b.number(), c.x, c.y, c.z));
            } else {
                double vol = (double) b.size(0) * b.size(1) * b.size(2);
                double[] g = groups.computeIfAbsent(b.group(), x -> new double[5]);
                g[0] = b.number();
                g[1] += c.x * vol;
                g[2] += c.y * vol;
                g[3] += c.z * vol;
                g[4] += vol;
            }
        }
        for (double[] g : groups.values()) {
            if (g[4] > 0) {
                centers.computeIfAbsent((int) g[0], x -> new ArrayList<>()).add(new Center((int) g[0], g[1] / g[4], g[2] / g[4], g[3] / g[4]));
            }
        }
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    public List<CpBox> boxes() {
        return boxes;
    }

    /** Numeros del recorrido (sin zonas libres), de menor a mayor. No modificar. */
    public int[] seq() {
        return seq;
    }

    /**
     * Las cajas que PUEDEN tocar el trayecto from -> to: las de los chunks que cubre su rectangulo (en X, Z) y las enormes,
     * sin repetir y en el mismo orden que la lista original (asi el resultado es identico a recorrer todas).
     */
    public List<CpBox> near(Vec3 from, Vec3 to) {
        int cx1 = (int) Math.floor(Math.min(from.x, to.x)) >> 4;
        int cx2 = (int) Math.floor(Math.max(from.x, to.x)) >> 4;
        int cz1 = (int) Math.floor(Math.min(from.z, to.z)) >> 4;
        int cz2 = (int) Math.floor(Math.max(from.z, to.z)) >> 4;
        if ((long) (cx2 - cx1 + 1) * (cz2 - cz1 + 1) > MAX_QUERY_CHUNKS) {
            return boxes; // trayecto larguisimo: se prueban todas
        }
        if (++gen == Integer.MAX_VALUE) {
            Arrays.fill(mark, 0);
            gen = 1;
        }
        int[] found = new int[8];
        int n = 0;
        for (int cx = cx1; cx <= cx2; cx++) {
            for (int cz = cz1; cz <= cz2; cz++) {
                int[] ids = chunks.get(chunkKey(cx, cz));
                if (ids == null) {
                    continue;
                }
                for (int id : ids) {
                    if (mark[id] != gen) {
                        mark[id] = gen;
                        if (n == found.length) {
                            found = Arrays.copyOf(found, n * 2);
                        }
                        found[n++] = id;
                    }
                }
            }
        }
        for (int id : big) {
            if (mark[id] != gen) {
                mark[id] = gen;
                if (n == found.length) {
                    found = Arrays.copyOf(found, n * 2);
                }
                found[n++] = id;
            }
        }
        Arrays.sort(found, 0, n);
        List<CpBox> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(boxes.get(found[i]));
        }
        return out;
    }

    /** El centro del checkpoint con ese numero que este mas cerca de "ref" (varios grupos con el mismo numero), o null. */
    public Center centerNear(int number, Vec3 ref) {
        List<Center> l = centers.get(number);
        if (l == null || l.isEmpty()) {
            return null;
        }
        Center best = l.get(0);
        double bd = best.distSqr(ref);
        for (int i = 1; i < l.size(); i++) {
            double d = l.get(i).distSqr(ref);
            if (d < bd) {
                bd = d;
                best = l.get(i);
            }
        }
        return best;
    }
}
