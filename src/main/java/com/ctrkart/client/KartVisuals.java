package com.ctrkart.client;

import com.ctrkart.KartMod;
import com.ctrkart.KartSounds;
import com.ctrkart.KartState;
import com.ctrkart.KartTracker;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Modelo del kart: para cada caballo-kart se crea en el CLIENTE una entidad "item display" que muestra
 * el modelo assets/ctrkart/models/item/kart.json y se coloca cada tick exactamente donde esta el caballo
 * (sin retraso, porque no pasa por el servidor). El caballo en si se oculta con EntityRendererMixin.
 */
public final class KartVisuals {
    private static final Map<Integer, Display.ItemDisplay> DISPLAYS = new HashMap<>();
    private static final Map<Integer, double[]> LAST_POS = new HashMap<>();
    private static ClientLevel lastLevel;
    private static ItemStack stack;
    private static boolean failed;
    private static EntityType<?> itemDisplayType;

    private KartVisuals() {}

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level != lastLevel) {
            DISPLAYS.clear();
            LAST_POS.clear();
            lastLevel = level;
        }
        if (level == null) {
            return;
        }

        Iterator<Map.Entry<Integer, Display.ItemDisplay>> it = DISPLAYS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Display.ItemDisplay> e = it.next();
            Entity horse = level.getEntity(e.getKey());
            if (horse == null || horse.isRemoved() || KartTracker.get(e.getKey()) == null) {
                e.getValue().discard();
                it.remove();
            }
        }

        LAST_POS.keySet().removeIf(k -> KartTracker.get(k) == null);

        for (Map.Entry<Integer, KartState> e : KartTracker.entries()) {
            Entity horse = level.getEntity(e.getKey());
            if (horse == null || horse.isRemoved()) {
                continue;
            }
            engineSound(level, horse, e.getKey());

            if (failed) {
                continue;
            }
            Display.ItemDisplay d = DISPLAYS.get(e.getKey());
            if (d == null) {
                d = create(level);
                if (d == null) {
                    continue;
                }
                DISPLAYS.put(e.getKey(), d);
            }
            d.setPos(horse.getX(), horse.getY(), horse.getZ());
            d.setYRot(horse.getYRot() + (e.getValue().flip ? 180f : 0f));
            d.setXRot(0f);
        }
    }

    /** Motor de prueba: un trozo corto de audio repetido cada 3 ticks, mas agudo cuanto mas rapido va el kart. */
    private static void engineSound(ClientLevel level, Entity horse, int id) {
        double[] last = LAST_POS.get(id);
        double speed = last == null ? 0.0 : Math.hypot(horse.getX() - last[0], horse.getZ() - last[1]);
        LAST_POS.put(id, new double[] {horse.getX(), horse.getZ()});
        if (!horse.isVehicle() || (level.getGameTime() + id) % 3 != 0) {
            return; // solo suena con alguien montado
        }
        float pitch = (float) Math.min(1.8, 0.7 + speed * 1.4);
        float volume = speed < 0.02 ? 0.25f : 0.5f;
        level.playLocalSound(horse.getX(), horse.getY(), horse.getZ(), KartSounds.ENGINE,
                SoundSource.NEUTRAL, volume, pitch, false);
    }

    private static Display.ItemDisplay create(ClientLevel level) {
        try {
            EntityType<?> type = itemDisplayType();
            if (type == null) {
                fail("no encontre el tipo de entidad item_display");
                return null;
            }
            Display.ItemDisplay d = new Display.ItemDisplay(type, level);
            if (!setItem(d)) {
                fail("no encontre el metodo para asignar el objeto al item display");
                return null;
            }
            if (!addToLevel(level, d)) {
                fail("no encontre el metodo para anadir la entidad al mundo del cliente");
                return null;
            }
            return d;
        } catch (Exception ex) {
            fail("error creando el modelo: " + ex);
            return null;
        }
    }

    /** El nombre de la constante cambio en 26.x, asi que se busca por nombre entre los campos de EntityType. */
    private static EntityType<?> itemDisplayType() {
        if (itemDisplayType == null) {
            for (java.lang.reflect.Field f : EntityType.class.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || !EntityType.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    Object v = f.get(null);
                    boolean byName = f.getName().toLowerCase().contains("item_display");
                    if (v != null && (byName || String.valueOf(v).endsWith("item_display"))) {
                        itemDisplayType = (EntityType<?>) v;
                        break;
                    }
                } catch (IllegalAccessException ignored) {
                    // seguir buscando
                }
            }
        }
        return itemDisplayType;
    }

    private static ItemStack stack() {
        if (stack == null) {
            ItemStack s = new ItemStack(Items.STICK);
            s.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(KartMod.MOD_ID, "kart"));
            stack = s;
        }
        return stack;
    }

    private static boolean setItem(Display.ItemDisplay d) throws ReflectiveOperationException {
        for (Method m : Display.ItemDisplay.class.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == ItemStack.class && m.getName().toLowerCase().contains("itemstack")) {
                m.setAccessible(true);
                m.invoke(d, stack());
                return true;
            }
        }
        return false;
    }

    private static boolean addToLevel(ClientLevel level, Display.ItemDisplay d) throws ReflectiveOperationException {
        for (String name : new String[] {"addEntity", "putNonPlayerEntity"}) {
            for (Method m : level.getClass().getMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && Entity.class.isAssignableFrom(p[0])) {
                    m.invoke(level, d);
                    return true;
                }
                if (p.length == 2 && p[0] == int.class && Entity.class.isAssignableFrom(p[1])) {
                    m.invoke(level, d.getId(), d);
                    return true;
                }
            }
        }
        return false;
    }

    private static void fail(String why) {
        failed = true;
        System.err.println("[ctrkart] Modelo de kart desactivado: " + why);
    }
}
