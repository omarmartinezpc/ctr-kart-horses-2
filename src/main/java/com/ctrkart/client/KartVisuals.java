package com.ctrkart.client;

import com.ctrkart.KartMod;
import com.ctrkart.KartPhysics;
import com.ctrkart.KartSeat;
import com.ctrkart.KartState;
import com.ctrkart.KartTracker;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

/**
 * Modelo del kart: para cada caballo-kart se crea en el CLIENTE una entidad "item display" que muestra
 * el modelo assets/ctrkart/models/item/kart.json y se coloca cada tick exactamente donde esta el caballo
 * (sin retraso, porque no pasa por el servidor). El caballo en si se oculta con EntityRendererMixin.
 */
public final class KartVisuals {
    /** Todo lo que se dibuja de un kart: el cuerpo y las 4 ruedas (entidades de display, solo en el cliente). */
    private static final class Rig {
        Display.ItemDisplay body;
        final Display.ItemDisplay[] wheels = new Display.ItemDisplay[4];
        boolean wheelsTried;
        double spin;               // giro acumulado de las ruedas, en grados (sin limite)
        final double[] lastPitch = new double[5]; // angulo real del tick anterior: 0 = cuerpo, 1..4 = ruedas
        final boolean[] hasPitch = new boolean[5];
        double lastX, lastZ;
        boolean hasLast;
        int lastSacred = -1;       // para detectar un Sacred Fire nuevo
        int wheelieTick = -1;      // -1 = sin caballito
        int wheelieTotal;
    }

    private static final Map<Integer, Rig> RIGS = new HashMap<>();

    // --- animacion ---
    /** Grados que gira la rueda por tick por cada bloque/tick de velocidad (sube y baja con la velocidad). */
    private static final double WHEEL_SPIN = 100.0;
    private static final double WHEEL_SPIN_MAX = 120.0;
    /** Cuanto giran las ruedas delanteras al pulsar A o D (grados). */
    private static final float STEER_MAX_DEG = 28f;
    /** Inclinacion maxima del caballito (grados). */
    private static final float WHEELIE_MAX = 28f;
    /** Duracion del caballito: 2 s con pocas reservas, 4 s con las reservas llenas (en ticks). */
    private static final int WHEELIE_MIN_TICKS = 40;
    private static final int WHEELIE_EXTRA_TICKS = 40;
    /** Ticks que tarda en subir y en bajar el morro (antes 8 y 10). */
    private static final double WHEELIE_UP_TICKS = 5.0;
    private static final double WHEELIE_DOWN_TICKS = 6.0;
    // posicion de cada rueda respecto al centro del kart, en bloques: derecha, arriba, delante
    private static final double[] W_LAT = {0.46875, -0.46875, 0.4625, -0.4625};
    private static final double[] W_UP = {0.197, 0.197, 0.21875, 0.21875};
    private static final double[] W_FWD = {0.4906, 0.4906, -0.3469, -0.3469};
    private static final String[] W_MODEL = {"kart_wheel_front", "kart_wheel_front", "kart_wheel_rear", "kart_wheel_rear"};
    private static boolean wheelsFailed;
    private static final Map<Integer, double[]> LAST_POS = new HashMap<>();
    private static final Map<Integer, KartEngineSound> ENGINES = new HashMap<>();
    private static ClientLevel lastLevel;
    private static final Map<String, ItemStack> STACKS = new HashMap<>();
    private static boolean failed;
    /** Ids negativos para no chocar con los ids de entidades reales del servidor. */
    private static final int FAKE_ID_BASE = -1_000_000;
    private static EntityType<?> itemDisplayType;

    // --- silla de montar: se le quita el "aspecto" en el cliente (sigue contando como ensillado) ---
    private static final Map<Integer, ItemStack> REAL_SADDLES = new HashMap<>();
    private static EquipmentSlot saddleSlot;
    private static DataComponentType<?> equippableType;
    private static boolean saddleSearched;
    /**
     * Oculta la silla en el cliente. Ya NO se quita el componente de equipo entero (eso dejaba al jugador sin
     * control): solo se vacia el "aspecto" (assetId) para que no se dibuje, y si aun asi el kart se queda sin
     * conductor, se desactiva solo y se devuelve la silla.
     */
    private static final boolean HIDE_SADDLE_ITEM = true;
    private static final Map<Integer, ItemStack> HIDDEN_SADDLES = new HashMap<>();
    private static boolean saddleDisabled;
    private static boolean saddleWarned;
    private static int saddleBadTicks;
    private static int diagCooldown;

    private KartVisuals() {}

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level != lastLevel) {
            RIGS.clear();
            LAST_POS.clear();
            ENGINES.clear();
            REAL_SADDLES.clear();
            HIDDEN_SADDLES.clear();
            lastLevel = level;
        }
        if (level == null) {
            return;
        }

        Iterator<Map.Entry<Integer, Rig>> it = RIGS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Rig> e = it.next();
            Entity horse = level.getEntity(e.getKey());
            if (horse == null || horse.isRemoved() || KartTracker.get(e.getKey()) == null) {
                if (horse != null && !horse.isRemoved()) {
                    horse.setInvisible(false);
                    restoreSaddle(horse);
                }
                Rig old = e.getValue();
                old.body.discard();
                for (Display.ItemDisplay w : old.wheels) {
                    if (w != null) {
                        w.discard();
                    }
                }
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
            diagnose(horse);
            if (KartTracker.modelReady) {
                horse.setInvisible(true); // el caballo no se ve; solo el modelo de kart
                if (HIDE_SADDLE_ITEM && !saddleDisabled) {
                    hideSaddle(horse);
                    guardSaddle(horse);
                }
            }

            if (failed) {
                continue;
            }
            Rig rig = RIGS.get(e.getKey());
            if (rig == null) {
                Display.ItemDisplay body = create(level, FAKE_ID_BASE - e.getKey(), "kart", true);
                if (body == null) {
                    continue;
                }
                rig = new Rig();
                rig.body = body;
                RIGS.put(e.getKey(), rig);
            }
            if (!rig.wheelsTried && !wheelsFailed) {
                rig.wheelsTried = true;
                createWheels(level, e.getKey(), rig);
            }
            animate(horse, e.getValue(), rig);
        }
    }

    private static void createWheels(ClientLevel level, int horseId, Rig rig) {
        for (int i = 0; i < 4; i++) {
            Display.ItemDisplay w = create(level, FAKE_ID_BASE - 100_000_000 * (i + 1) - horseId, W_MODEL[i], false);
            if (w == null) {
                wheelsFailed = true;
                System.err.println("[ctrkart] No pude crear las ruedas animadas; el kart se ve sin ruedas.");
                return;
            }
            rig.wheels[i] = w;
        }
    }

    private static double smooth(double x) {
        double c = Math.max(0.0, Math.min(1.0, x));
        return c * c * (3.0 - 2.0 * c);
    }

    /** Coloca el cuerpo y las ruedas cada tick: giro de ruedas segun la velocidad real y caballito tras un Sacred Fire. */
    private static void animate(Entity horse, KartState st, Rig rig) {
        float yd = horse.getYRot() + (st.flip ? 180f : 0f); // hacia donde mira el modelo
        double rad = Math.toRadians(yd);
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        double rx = -Math.cos(rad);
        double rz = -Math.sin(rad);

        // velocidad real (con signo) medida entre ticks
        double v = 0.0;
        if (rig.hasLast) {
            double dx = horse.getX() - rig.lastX;
            double dz = horse.getZ() - rig.lastZ;
            double hr = Math.toRadians(horse.getYRot());
            double along = dx * -Math.sin(hr) + dz * Math.cos(hr);
            v = Math.hypot(dx, dz);
            if (along < 0) {
                v = -v;
            }
            if (st.flip) {
                v = -v; // con kart_flip el modelo mira al reves de la marcha
            }
        }
        rig.lastX = horse.getX();
        rig.lastZ = horse.getZ();
        rig.hasLast = true;
        rig.spin += Math.max(-WHEEL_SPIN_MAX, Math.min(WHEEL_SPIN_MAX, v * WHEEL_SPIN));

        // caballito: empieza con cada Sacred Fire nuevo y dura 2-4 s segun las reservas
        if (rig.lastSacred < 0) {
            rig.lastSacred = st.sacredEvents;
        }
        if (st.sacredEvents != rig.lastSacred) {
            rig.lastSacred = st.sacredEvents;
            if (rig.wheelieTick < 0 && horse.isVehicle()) {
                float reserves = Math.max(0f, Math.min(100f, st.energy));
                rig.wheelieTick = 0;
                rig.wheelieTotal = WHEELIE_MIN_TICKS + Math.round(WHEELIE_EXTRA_TICKS * reserves / 100f);
            }
        }
        float phi = 0f;
        if (rig.wheelieTick >= 0) {
            rig.wheelieTick++;
            if (!st.sacred && rig.wheelieTotal > rig.wheelieTick + (int) WHEELIE_DOWN_TICKS) {
                rig.wheelieTotal = rig.wheelieTick + (int) WHEELIE_DOWN_TICKS; // se acabo el Sacred Fire: baja ya
            }
            if (rig.wheelieTick >= rig.wheelieTotal || !horse.isVehicle()) {
                rig.wheelieTick = -1;
            } else {
                double t = rig.wheelieTick;
                double e = Math.min(smooth(t / WHEELIE_UP_TICKS), smooth((rig.wheelieTotal - t) / WHEELIE_DOWN_TICKS));
                phi = (float) (WHEELIE_MAX * e + 1.5 * Math.sin(t * 0.35) * e);
            }
        }
        st.wheelie = phi; // KartSeat lo usa para subir y echar atras al jugador

        // direccion: las ruedas delanteras miran hacia donde giras (con kart_flip el modelo va al reves)
        if (!horse.isVehicle()) {
            st.steerAnim *= 0.6f;
        }
        float steerYaw = st.steerAnim * STEER_MAX_DEG * (st.flip ? -1f : 1f);

        // el cuerpo gira alrededor del eje trasero; las ruedas delanteras lo acompanan
        double[] bp = KartSeat.pitch(0.0, 0.0, phi);
        place(rig, 0, rig.body, horse, rx, rz, fx, fz, 0.0, bp[1], bp[0], yd, -phi, false);
        for (int i = 0; i < 4; i++) {
            Display.ItemDisplay w = rig.wheels[i];
            if (w == null) {
                continue;
            }
            double[] p = KartSeat.pitch(W_FWD[i], W_UP[i], phi);
            place(rig, i + 1, w, horse, rx, rz, fx, fz, W_LAT[i], p[1], p[0], i < 2 ? yd + steerYaw : yd, rig.spin - phi, true);
        }
    }

    /**
     * Coloca una pieza. "pitch" es el angulo REAL (sin limite). El juego guarda el angulo de una entidad
     * recortado/envuelto, y al pasar el limite la interpolacion daba la vuelta al reves ("telefono de rueda").
     * Por eso: (1) las ruedas se ven igual cada media vuelta (dos marcas opuestas), asi que el angulo guardado
     * se reduce a [-90, 90); y (2) el angulo del tick anterior se fija a mano como "guardado - giro real",
     * de modo que la interpolacion siempre avanza en el sentido correcto.
     */
    private static void place(Rig rig, int idx, Display.ItemDisplay d, Entity horse, double rx, double rz,
                              double fx, double fz, double lat, double up, double fwd, float yaw, double pitch,
                              boolean halfTurnSymmetric) {
        d.setPos(horse.getX() + rx * lat + fx * fwd, horse.getY() + up, horse.getZ() + rz * lat + fz * fwd);
        d.setYRot(yaw);
        double delta = rig.hasPitch[idx] ? pitch - rig.lastPitch[idx] : 0.0;
        rig.lastPitch[idx] = pitch;
        rig.hasPitch[idx] = true;
        double stored = halfTurnSymmetric ? pitch - 180.0 * Math.floor((pitch + 90.0) / 180.0) : pitch;
        d.setXRot((float) stored);
        d.xRotO = (float) (stored - delta);
    }

    /** Cada 10 s mientras alguien va montado, escribe en el log quien controla el kart (para detectar problemas). */
    private static void diagnose(Entity horse) {
        if (!horse.isVehicle()) {
            return;
        }
        if (diagCooldown-- > 0) {
            return;
        }
        diagCooldown = 200;
        System.out.println("[ctrkart] jinete: controlador=" + horse.getControllingPassenger()
                + " silla-oculta=" + REAL_SADDLES.containsKey(horse.getId()) + (saddleDisabled ? " (desactivada)" : ""));
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

    /** Copia de la silla con el "aspecto" vacio: sigue siendo una silla valida, pero la capa no se dibuja. */
    private static ItemStack withoutSaddleAsset(ItemStack cur) {
        Object eq = cur.get(equippableType);
        if (eq == null) {
            return null;
        }
        try {
            RecordComponent[] comps = eq.getClass().getRecordComponents();
            if (comps == null) {
                return null;
            }
            Object[] vals = new Object[comps.length];
            Class<?>[] types = new Class<?>[comps.length];
            boolean changed = false;
            for (int i = 0; i < comps.length; i++) {
                types[i] = comps[i].getType();
                java.lang.reflect.Method acc = comps[i].getAccessor();
                acc.setAccessible(true);
                vals[i] = acc.invoke(eq);
                if (comps[i].getName().equals("assetId")) {
                    if (vals[i] instanceof Optional<?> o && o.isEmpty()) {
                        return null; // ya estaba vacio
                    }
                    vals[i] = Optional.empty();
                    changed = true;
                }
            }
            if (!changed) {
                return null;
            }
            Constructor<?> k = eq.getClass().getDeclaredConstructor(types);
            k.setAccessible(true);
            Object neu = k.newInstance(vals);
            ItemStack copy = cur.copy();
            @SuppressWarnings("unchecked")
            DataComponentType<Object> t = (DataComponentType<Object>) equippableType;
            copy.set(t, neu);
            return copy;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            if (!saddleWarned) {
                saddleWarned = true;
                System.err.println("[ctrkart] No pude vaciar el aspecto de la silla: " + ex);
            }
            return null;
        }
    }

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
        if (cur.isEmpty() || cur == HIDDEN_SADDLES.get(horse.getId())) {
            return; // vacia o ya oculta
        }
        ItemStack hidden = withoutSaddleAsset(cur);
        if (hidden == null) {
            return;
        }
        REAL_SADDLES.put(horse.getId(), cur);
        HIDDEN_SADDLES.put(horse.getId(), hidden);
        horse.setItemSlot(saddleSlot, hidden);
    }

    /** Si con la silla oculta el kart se queda sin conductor, se desactiva el truco y se devuelve la silla. */
    private static void guardSaddle(Entity horse) {
        if (!REAL_SADDLES.containsKey(horse.getId())) {
            return;
        }
        boolean bad = horse.getFirstPassenger() instanceof Player && horse.getControllingPassenger() == null;
        saddleBadTicks = bad ? saddleBadTicks + 1 : 0;
        if (saddleBadTicks >= 10) {
            saddleDisabled = true;
            System.err.println("[ctrkart] Con la silla oculta el kart se quedo sin conductor: desactivo ocultar la silla.");
            for (Map.Entry<Integer, ItemStack> en : new HashMap<>(REAL_SADDLES).entrySet()) {
                Entity h = Minecraft.getInstance().level == null ? null : Minecraft.getInstance().level.getEntity(en.getKey());
                if (h != null) {
                    restoreSaddle(h);
                }
            }
        }
    }

    /** Si el caballo deja de ser kart, vuelve a tener su silla normal. */
    private static void restoreSaddle(Entity e) {
        HIDDEN_SADDLES.remove(e.getId());
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

    private static Display.ItemDisplay create(ClientLevel level, int fakeId, String model, boolean critical) {
        try {
            EntityType<?> type = itemDisplayType();
            if (type == null) {
                return critical ? failNull("no encontre el tipo de entidad item_display") : null;
            }
            Display.ItemDisplay d = new Display.ItemDisplay(type, level);
            d.setId(fakeId);
            if (!setItem(d, stackFor(model))) {
                return critical ? failNull("no encontre el metodo para asignar el objeto al item display; candidatos: " + names(Display.ItemDisplay.class.getDeclaredMethods(), ItemStack.class)) : null;
            }
            if (!addToLevel(level, d)) {
                return critical ? failNull("no encontre el metodo para anadir la entidad al mundo del cliente; candidatos: " + names(level.getClass().getMethods(), Entity.class)) : null;
            }
            if (critical) {
                KartTracker.modelReady = true; // a partir de ahora se oculta el caballo
                System.out.println("[ctrkart] Modelo de kart creado (" + model + ")");
            }
            return d;
        } catch (Exception ex) {
            if (critical) {
                return failNull("error creando el modelo: " + ex);
            }
            System.err.println("[ctrkart] error creando " + model + ": " + ex);
            return null;
        }
    }

    private static Display.ItemDisplay failNull(String why) {
        fail(why);
        return null;
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

    private static ItemStack stackFor(String model) {
        ItemStack st = STACKS.get(model);
        if (st == null) {
            st = new ItemStack(Items.STICK);
            st.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(KartMod.MOD_ID, model));
            STACKS.put(model, st);
        }
        return st;
    }

    private static boolean setItem(Display.ItemDisplay d, ItemStack item) throws ReflectiveOperationException {
        for (Method m : Display.ItemDisplay.class.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == ItemStack.class && m.getName().toLowerCase().contains("itemstack")) {
                m.setAccessible(true);
                m.invoke(d, item);
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
