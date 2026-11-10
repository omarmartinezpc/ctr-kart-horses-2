package com.ctrkart;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/**
 * Posiciones de la carrera. NO depende de ServerPlayer: recibe datos simples (vuelta, indice en la secuencia, secuencia,
 * posicion), asi sirve igual para jugadores y para bots.
 */
public final class KartRanking {
    private KartRanking() {}

    /** Un corredor para ordenar: quien es, cuanto ha avanzado y en que tick llego a su ultimo checkpoint. */
    public record Entry(UUID id, double progress, long lastCpTick) {}

    /**
     * progreso = vuelta * n + idx + fraccion, donde n = checkpoints del recorrido (seq.length) y fraccion = proyeccion de la
     * posicion sobre el segmento "centro del checkpoint actual -> centro del siguiente", limitada a 0..1.
     * Con varios grupos del mismo numero se usa el centro mas cercano a la posicion.
     * Si falta algun centro (o el recorrido es muy corto) la fraccion es 0.
     */
    public static double progress(int lap, int idx, int[] seq, KartTrack track, Vec3 pos) {
        int n = seq.length;
        if (n == 0) {
            return 0.0;
        }
        idx = Math.floorMod(idx, n);
        double base = (double) lap * n + idx;
        if (n < 2) {
            return base;
        }
        KartTrack.Center a = track.centerNear(seq[idx], pos);
        KartTrack.Center b = track.centerNear(seq[(idx + 1) % n], pos);
        if (a == null || b == null) {
            return base;
        }
        double abx = b.x() - a.x();
        double aby = b.y() - a.y();
        double abz = b.z() - a.z();
        double len2 = abx * abx + aby * aby + abz * abz;
        if (len2 < 1.0e-9) {
            return base; // dos centros en el mismo sitio
        }
        double t = ((pos.x - a.x()) * abx + (pos.y - a.y()) * aby + (pos.z - a.z()) * abz) / len2;
        return base + Math.max(0.0, Math.min(1.0, t));
    }

    /** De mayor a menor progreso; si empatan, va primero quien llego antes a su ultimo checkpoint. */
    public static void sort(List<Entry> entries) {
        // el progreso se compara con 6 decimales: dos corredores "iguales" desempatan por el tick de su ultimo checkpoint
        entries.sort(Comparator.comparingLong((Entry e) -> Math.round(e.progress() * 1.0e6)).reversed()
                .thenComparingLong(Entry::lastCpTick));
    }
}
