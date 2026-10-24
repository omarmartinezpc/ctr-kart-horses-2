package com.ctrkart;

import java.util.Set;
import net.minecraft.world.entity.Entity;

/**
 * Clases de kart, inspiradas en las clases de personaje de CTR (Speed / Accel / Turn / Balance / Big).
 * Se eligen con etiquetas de entidad: "kart" (equilibrado) + opcionalmente una de las demas.
 *
 * Unidades: top = bloques/tick, accel = bloques/tick por tick, turn = grados/tick.
 * Los valores son de balance propio (no son datos extraidos del juego original).
 */
public enum KartClass {
    BALANCED("kart", 0.52, 0.020, 4.8f),
    SPEED("kart_speed", 0.58, 0.012, 4.0f),
    ACCEL("kart_accel", 0.49, 0.034, 4.8f),
    TURN("kart_turn", 0.48, 0.018, 6.4f),
    BIG("kart_big", 0.55, 0.014, 3.6f);

    public static final String BASE_TAG = "kart";

    public final String tag;
    public final double top;
    public final double accel;
    public final float turn;

    KartClass(String tag, double top, double accel, float turn) {
        this.tag = tag;
        this.top = top;
        this.accel = accel;
        this.turn = turn;
    }

    private static final java.lang.reflect.Method TAGS_METHOD = findTagsMethod();

    /** En 26.x el metodo de etiquetas cambio de nombre: probamos los nombres conocidos. */
    private static java.lang.reflect.Method findTagsMethod() {
        for (String name : new String[] {"entityTags", "getTags"}) {
            try {
                java.lang.reflect.Method m = Entity.class.getMethod(name);
                if (Set.class.isAssignableFrom(m.getReturnType())) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
                // probar el siguiente nombre
            }
        }
        System.err.println("[ctrkart] No se encontro el metodo de etiquetas de Entity");
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> tagsOf(Entity e) {
        if (TAGS_METHOD == null) {
            return Set.of();
        }
        try {
            return (Set<String>) TAGS_METHOD.invoke(e);
        } catch (ReflectiveOperationException ex) {
            return Set.of();
        }
    }

    /** -1 si la entidad no es kart; si no, el ordinal de su clase. Solo fiable en el servidor. */
    public static int idOf(Entity e) {
        Set<String> tags = tagsOf(e);
        if (!tags.contains(BASE_TAG)) {
            return -1;
        }
        for (KartClass c : values()) {
            if (c != BALANCED && tags.contains(c.tag)) {
                return c.ordinal();
            }
        }
        return BALANCED.ordinal();
    }
}
