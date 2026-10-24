package com.ctrkart;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Cliente: que caballos son karts (las etiquetas de entidad NO se sincronizan a los clientes). */
public final class KartTracker {
    private static final Map<Integer, KartState> STATES = new ConcurrentHashMap<>();

    private KartTracker() {}

    public static KartState get(int entityId) {
        return STATES.get(entityId);
    }

    public static void apply(KartSyncPayload p) {
        if (p.kartClass() < 0) {
            STATES.remove(p.entityId());
            return;
        }
        KartState s = STATES.computeIfAbsent(p.entityId(), id -> new KartState());
        KartClass[] all = KartClass.values();
        s.kartClass = all[Math.min(p.kartClass(), all.length - 1)];
        s.superTicks = p.superTicks();
    }

    public static void clear() {
        STATES.clear();
    }
}
