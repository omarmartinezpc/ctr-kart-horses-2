package com.ctrkart.client;

import com.ctrkart.KartMod;
import com.ctrkart.KartPhysics;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
    /** Ids negativos para no chocar con los ids de entidades reales del servidor. */
    private static final int FAKE_ID_BASE = -1_000_000;
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
                if (horse != null && !horse.isRemoved()) {
                    horse.setInvisible(false);
                }
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
            if (KartTracker.modelReady) {
                horse.setInvisible(true); // el caballo no se ve; solo el modelo de kart
            }

            if (failed) {
                continue;
            }
            Display.ItemDisplay d = DISPLAYS.get(e.getKey());
            if (d == null) {
                d = create(level, e.getKey());
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
        float pitch = (float) Math.min(2.0, 0.6 + speed * 2.0); // 0.6 parado -> 2.0 a gran velocidad
        float volume = speed < 0.02 ? 0.12f : 0.28f;
        level.playLocalSound(horse.getX(), horse.getY(), horse.getZ(), KartSounds.ENGINE,
                SoundSource.NEUTRAL, volume, pitch, false);
    }

    private static Display.ItemDisplay create(ClientLevel level, int horseId) {
        try {
            EntityType<?> type = itemDisplayType();
            if (type == null) {
                fail("no encontre el tipo de entidad item_display");
                return null;
            }
            Display.ItemDisplay d = new Display.ItemDisplay(type, level);
            d.setId(FAKE_ID_BASE - horseId);
            if (!setItem(d)) {
                fail("no encontre el metodo para asignar el objeto al item display; candidatos: " + names(Display.ItemDisplay.class.getDeclaredMethods(), ItemStack.class));
                return null;
            }
            if (!addToLevel(level, d)) {
                fail("no encontre el metodo para anadir la entidad al mundo del cliente; candidatos: " + names(level.getClass().getMethods(), Entity.class));
                return null;
            }
            KartTracker.modelReady = true; // a partir de ahora se oculta el caballo
            System.out.println("[ctrkart] Modelo de kart creado para el caballo " + horseId);
            return d;
        } catch (Exception ex) {
            fail("error creando el modelo: " + ex);
            return null;
        }
    }

    /** El tipo se busca en el registro del juego por su nombre (las constantes de EntityType cambiaron en 26.x). */
    private static EntityType<?> itemDisplayType() {
        if (itemDisplayType == null) {
            for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
                Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(t);
                if (id != null && id.getPath().equals("item_display")) {
                    itemDisplayType = t;
                    break;
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

    /** Nombres de metodos que reciben un parametro de cierto tipo (solo para mensajes de diagnostico). */
    private static String names(Method[] methods, Class<?> param) {
        StringBuilder sb = new StringBuilder();
        for (Method m : methods) {
            for (Class<?> p : m.getParameterTypes()) {
                if (param.isAssignableFrom(p)) {
                    sb.append(m.getName()).append(' ');
                    break;
                }
            }
        }
        return sb.toString().trim();
    }

    private static void fail(String why) {
        failed = true;
        KartTracker.modelReady = false; // el caballo vuelve a verse
        System.err.println("[ctrkart] Modelo de kart desactivado: " + why);
        KartPhysics.HUD.accept(Component.literal("§c[ctrkart] Modelo de kart desactivado: " + why));
    }
}
