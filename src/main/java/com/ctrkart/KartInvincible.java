package com.ctrkart;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse; // si no existe: ...animal.horse.AbstractHorse

/**
 * Los karts son invencibles (en cualquier modo de manejo). Como los nombres del juego cambian entre versiones,
 * se usan varias vias a la vez, y cada una se ignora sola si no existe:
 *  1) el interruptor interno "invulnerable" del caballo (por reflexion, porque setInvulnerable ya no compila);
 *  2) el evento ALLOW_DAMAGE de Fabric API (por reflexion): rechaza el dano antes de que se aplique;
 *  3) curar al kart por si algun dano se cuela.
 * El dano que ignora la invulnerabilidad (vacio, /kill) se deja pasar para que el caballo se pueda quitar.
 */
public final class KartInvincible {
    private static boolean searched;
    private static Method setter;
    private static Field field;
    private static boolean silentSearched;
    private static Method silentSetter;

    private KartInvincible() {}

    /** Se llama al iniciar el mod. */
    public static void register() {
        try {
            Class<?> events = Class.forName("net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents");
            Object event = events.getField("ALLOW_DAMAGE").get(null);
            Class<?> listener = Class.forName("net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents$AllowDamage");
            Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] {listener}, (self, m, args) -> {
                switch (m.getName()) {
                    case "allowDamage":
                        return args == null || args.length < 2 || !isProtected(args[0], args[1]);
                    case "toString":
                        return "ctrkart-invincible";
                    case "hashCode":
                        return System.identityHashCode(self);
                    case "equals":
                        return args != null && args.length == 1 && self == args[0];
                    default:
                        return null;
                }
            });
            Method reg = Class.forName("net.fabricmc.fabric.api.event.Event").getMethod("register", Object.class);
            reg.invoke(event, proxy);
            System.out.println("[ctrkart] Invencibilidad: evento ALLOW_DAMAGE registrado");
            logWaterCandidates();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            System.err.println("[ctrkart] Invencibilidad: no pude usar ALLOW_DAMAGE (" + ex + "); quedan las otras vias");
        }
    }

    /** true = este dano hay que rechazarlo (es un kart y no es dano que ignora la invulnerabilidad). */
    private static boolean isProtected(Object entity, Object source) {
        if (!(entity instanceof AbstractHorse horse) || KartMod.serverClass(horse) < 0) {
            return false;
        }
        return !(source instanceof DamageSource ds && ds.is(DamageTypeTags.BYPASSES_INVULNERABILITY));
    }

    /** Se llama cada 10 ticks por cada kart (on = true) y una vez al quitarle la etiqueta (on = false). */
    public static void apply(AbstractHorse horse, boolean on) {
        setInvulnerable(horse, on);
        setSilent(horse, on); // un kart no hace ruidos de caballo
        if (on && horse.getHealth() < horse.getMaxHealth()) {
            horse.setHealth(horse.getMaxHealth());
        }
    }

    private static void setInvulnerable(Entity e, boolean on) {
        if (e.isInvulnerable() == on) {
            return;
        }
        if (!searched) {
            searched = true;
            try {
                for (Method m : Entity.class.getDeclaredMethods()) {
                    if (m.getName().equals("setInvulnerable") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == boolean.class) {
                        m.setAccessible(true);
                        setter = m;
                    }
                }
                if (setter == null) {
                    for (Field f : Entity.class.getDeclaredFields()) {
                        if (f.getType() == boolean.class && f.getName().toLowerCase().contains("invulnerable")) {
                            f.setAccessible(true);
                            field = f;
                            break;
                        }
                    }
                }
            } catch (RuntimeException ex) {
                System.err.println("[ctrkart] Invencibilidad: error buscando el interruptor: " + ex);
            }
            System.out.println("[ctrkart] Invencibilidad: metodo=" + setter + " campo=" + field);
        }
        try {
            if (setter != null) {
                setter.invoke(e, on);
            } else if (field != null) {
                field.setBoolean(e, on);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // queda la via del evento y la curacion
        }
    }

    /** Marca al caballo como "silencioso" (por reflexion: el nombre del metodo puede cambiar entre versiones). */
    private static void setSilent(Entity e, boolean on) {
        if (!silentSearched) {
            silentSearched = true;
            try {
                for (Method m : Entity.class.getDeclaredMethods()) {
                    if (m.getName().equals("setSilent") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == boolean.class) {
                        m.setAccessible(true);
                        silentSetter = m;
                    }
                }
            } catch (RuntimeException ex) {
                System.err.println("[ctrkart] Silencio: error buscando setSilent: " + ex);
            }
            System.out.println("[ctrkart] Silencio: setSilent=" + silentSetter);
        }
        if (silentSetter != null) {
            try {
                silentSetter.invoke(e, on);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                // queda el filtro de sonidos del mixin
            }
        }
    }

    /** Solo para el log: que metodos de Entity tienen que ver con desmontar bajo el agua en esta version. */
    private static void logWaterCandidates() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Method m : Entity.class.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                if (m.getParameterCount() == 0 && m.getReturnType() == boolean.class
                        && (n.contains("underwater") || n.contains("dismount") || n.contains("rideable"))) {
                    sb.append(m.getName()).append(' ');
                }
            }
            System.out.println("[ctrkart] Agua: metodos candidatos en Entity = " + sb);
        } catch (RuntimeException ex) {
            // solo es informativo
        }
    }
}
