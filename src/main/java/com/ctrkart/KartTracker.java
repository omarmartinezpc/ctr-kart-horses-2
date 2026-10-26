package com.ctrkart;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Cliente: que caballos son karts (las etiquetas de entidad NO se sincronizan a los clientes). */
public final class KartTracker {
    private static final Map<Integer, KartState> STATES = new ConcurrentHashMap<>();
    /** true cuando el modelo de kart se creo bien: solo entonces se oculta el caballo. */
    public static volatile boolean modelReady;

    private KartTracker() {}

    public static KartState get(int entityId) {
        return STATES.get(entityId);
    }

    public static Set<Map.Entry<Integer, KartState>> entries() {
        return STATES.entrySet();
    }

    public static void apply(KartSyncPayload p) {
        int raw = p.kartClass();
        if (raw < 0) {
            STATES.remove(p.entityId());
            return;
        }
        boolean flip = raw >= KartClass.FLIP_BASE;
        int cls = raw % KartClass.FLIP_BASE;
        KartState s = STATES.computeIfAbsent(p.entityId(), id -> new KartState());
        KartClass[] all = KartClass.values();
        s.kartClass = all[Math.min(cls, all.length - 1)];
        s.flip = flip;
        s.superTicks = p.superTicks();
    }

    /** Energia recibida al alimentar al caballo (la envia el servidor). */
    public static void addEnergy(int entityId, int amount) {
        KartState s = STATES.get(entityId);
        if (s != null) {
            s.energy = Math.min(KartPhysics.ENERGY_MAX, s.energy + amount);
        }
    }

    public static void clear() {
        STATES.clear();
        modelReady = false;
    }
}
