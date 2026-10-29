package com.ctrkart.client;

import com.ctrkart.KartMod;
import com.ctrkart.KartPhysics;
import com.ctrkart.KartState;
import com.ctrkart.KartTracker;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
    private static final Map<Integer, KartEngineSound> ENGINES = new HashMap<>();
    private static ClientLevel lastLevel;
    private static ItemStack stack;
    private static boolean failed;
    /** Ids negativos para no chocar con los ids de entidades reales del servidor. */
    private static final int FAKE_ID_BASE = -1_000_000;
    private static EntityType<?> itemDisplayType;

    // --- silla de montar: se le quita el "aspecto" en el cliente (sigue contando como ensillado) ---
    private static final Map<Integer, ItemStack> REAL_SADDLES = new HashMap<>();
    private static EquipmentSlot saddleSlot;
    private static DataComponentType<?> equippableType;
    private static boolean saddleSearched;

    private KartVisuals() {}

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level != lastLevel) {
            DISPLAYS.clear();
            LAST_POS.clear();
            ENGINES.clear();
            REAL_SADDLES.clear();
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
                    restoreSaddle(horse);
                }
                e.getValue().discard();
                it.remove();
            }
        }

        LAST_POS.keySet().removeIf(k -> KartTracker.get(k) == null);
        Iterator<Map.Entry<Integer, KartEngineSound>> eit = ENGINES.entrySet().iterator();
        while (eit.hasNext()) {
            Map.Entry<Integer, KartEngineSound> en = eit.next();
            Entity h = level.getEntity(en.getKey());
            if (h == null || h.isRemoved() || KartTracker.get(en.getKey()) == null) {
                en.getValue().requestStop();
                eit.remove();
            }
        }

        for (Map.Entry<Integer, KartState> e : KartTracker.entries()) {
            Entity horse = level.getEntity(e.getKey());
            if (horse == null || horse.isRemoved()) {
                continue;
            }
            updateEngine(mc, horse, e.getKey(), e.getValue());
            if (KartTracker.modelReady) {
                horse.setInvisible(true); // el caballo no se ve; solo el modelo de kart
                hideSaddle(horse);        // y su silla tampoco (las capas de equipo se dibujan aunque sea invisible)
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

    private static void findSaddle() {
        saddleSearched = true;
        for (EquipmentSlot sl : EquipmentSlot.values()) {
            if (sl.name().equals("SADDLE")) {
                saddleSlot = sl;
            }
        }
        try {
            Object o = DataComponents.class.getField("EQUIPPABLE").get(null);
            if (o instanceof DataComponentType<?> t) {
                equippableType = t;
            }
        } catch (ReflectiveOperationException ignored) {
            // sin esto no se puede ocultar la silla, pero el resto funciona
        }
        if (saddleSlot == null || equippableType == null) {
            System.err.println("[ctrkart] No pude preparar el ocultado de la silla (slot=" + saddleSlot
                    + ", componente=" + equippableType + ")");
        }
    }

    /** Quita al objeto de la silla su componente de equipo: sigue ensillado, pero la capa de la silla no se dibuja. */
    private static void hideSaddle(Entity e) {
        if (!(e instanceof LivingEntity horse)) {
            return;
        }
        if (!saddleSearched) {
            findSaddle();
        }
        if (saddleSlot == null || equippableType == null) {
            return;
        }
        ItemStack cur = horse.getItemBySlot(saddleSlot);
        if (cur.isEmpty() || cur.get(equippableType) == null) {
            return; // vacia o ya oculta
        }
        REAL_SADDLES.put(horse.getId(), cur);
        ItemStack copy = cur.copy();
        copy.remove(equippableType);
        horse.setItemSlot(saddleSlot, copy);
    }

    /** Si el caballo deja de ser kart, vuelve a tener su silla normal. */
    private static void restoreSaddle(Entity e) {
        ItemStack real = REAL_SADDLES.remove(e.getId());
        if (real != null && saddleSlot != null && e instanceof LivingEntity horse) {
            horse.setItemSlot(saddleSlot, real);
        }
    }

    /** Motor en bucle: suena mientras alguien va montado; su tono y volumen los gobierna KartEngineSound. */
    private static void updateEngine(Minecraft mc, Entity horse, int id, KartState st) {
        double[] last = LAST_POS.get(id);
        double speed = last == null ? 0.0 : Math.hypot(horse.getX() - last[0], horse.getZ() - last[1]);
        LAST_POS.put(id, new double[] {horse.getX(), horse.getZ()});

        KartEngineSound eng = ENGINES.get(id);
        if (!horse.isVehicle()) {
            if (eng != null) {
                eng.requestStop();
                ENGINES.remove(id);
            }
            return;
        }
        if (eng == null) {
            eng = new KartEngineSound(horse);
            ENGINES.put(id, eng);
            mc.getSoundManager().play(eng);
        }
        eng.update(speed, st);
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
