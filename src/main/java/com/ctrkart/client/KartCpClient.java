package com.ctrkart.client;

import com.ctrkart.CpBox;
import com.ctrkart.KartCpItems;
import com.ctrkart.KartCpPayload;
import com.ctrkart.KartModClient;
import com.ctrkart.KartPhysics;
import com.ctrkart.KartRescue;
import com.ctrkart.KartState;
import com.ctrkart.KartTracker;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.equine.AbstractHorse; // si no existe: ...animal.horse.AbstractHorse
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Cliente de los checkpoints:
 *  - Guarda la lista que manda el servidor y el estado de los items (numero elegido, esquina marcada).
 *  - Solo con el item de creacion o de borrado en la mano se ven los checkpoints (cajas de cristal de colores, hechas con
 *    "block display" locales, sin hitbox): numerados = morado, meta (0) = blanco, zona libre = verde; el que apuntas = amarillo
 *    (creacion) o rojo (borrado). Ademas se ve el bloque de la mira (a 4 bloques) y, con una esquina marcada, la vista previa.
 *  - Recibe las ordenes del rescate (ver KartRescue).
 */
public final class KartCpClient {
    private static volatile List<CpBox> boxes = List.of();
    private static int uiNumber;
    private static boolean uiCornerSet;
    private static BlockPos uiCorner = BlockPos.ZERO;

    private static final class Disp {
        final Display.BlockDisplay entity;
        BlockState state;
        double x = Double.NaN;
        double y;
        double z;
        float sx;
        float sy;
        float sz;

        Disp(Display.BlockDisplay entity) {
            this.entity = entity;
        }
    }

    private static final Map<String, Disp> DISPLAYS = new HashMap<>();
    private static int nextId = -900_000_000;
    private static EntityType<?> blockDisplayType;
    private static boolean warned;
    private static int overlayTimer;

    private KartCpClient() {}

    // ------------------------------------------------------------------ paquetes

    public static void handle(KartCpPayload p) {
        Minecraft mc = Minecraft.getInstance();
        List<Integer> d = p.data();
        switch (p.kind()) {
            case KartCpPayload.SYNC: {
                List<CpBox> l = new ArrayList<>();
                for (int i = 0; i + 6 < d.size(); i += 7) {
                    l.add(CpBox.read(d, i));
                }
                boxes = l;
                break;
            }
            case KartCpPayload.UI:
                if (d.size() >= 5) {
                    uiNumber = d.get(0);
                    uiCornerSet = d.get(1) != 0;
                    uiCorner = new BlockPos(d.get(2), d.get(3), d.get(4));
                }
                break;
            case KartCpPayload.RACE:
                if (d.size() >= 4) {
                    KartRescue.raceLap = d.get(0);
                    KartRescue.raceIdx = d.get(1);
                    KartRescue.raceTotal = d.get(2);
                    KartRescue.raceStarted = d.get(3) != 0;
                }
                break;
            case KartCpPayload.RESCUE_START: {
                if (mc.level == null || d.size() < 5) {
                    break;
                }
                Entity e = mc.level.getEntity(d.get(0));
                if (e instanceof AbstractHorse horse) {
                    KartRescue.start(horse, d.get(1) / 1000.0, d.get(2) / 1000.0, d.get(3) / 1000.0, d.get(4) / 10.0f);
                }
                break;
            }
            case KartCpPayload.RESCUE_WARP: {
                if (mc.level == null || d.size() < 3 || !KartRescue.active) {
                    break;
                }
                Entity e = mc.level.getEntity(KartRescue.horseId);
                if (e instanceof AbstractHorse horse) {
                    KartModClient.moveKart(horse, d.get(0) / 1000.0, d.get(1) / 1000.0, d.get(2) / 1000.0);
                    KartState st = KartTracker.get(horse.getId());
                    if (st != null) {
                        KartPhysics.onTeleported(st, d.get(1) / 1000.0);
                    }
                    KartRescue.warped(horse);
                }
                break;
            }
            case KartCpPayload.RESCUE_END: {
                if (mc.level == null || !KartRescue.active) {
                    break;
                }
                Entity e = mc.level.getEntity(KartRescue.horseId);
                if (e instanceof AbstractHorse horse) {
                    KartRescue.end(horse, KartTracker.get(horse.getId()));
                } else {
                    KartRescue.abort(null);
                }
                break;
            }
            default:
                break;
        }
    }

    public static void clear() {
        boxes = List.of();
        KartRescue.active = false;
        KartRescue.raceStarted = false;
        clearDisplays();
    }

    // ------------------------------------------------------------------ cada tick

    private static boolean holds(LocalPlayer p, Item item) {
        return p.getMainHandItem().getItem() == item || p.getOffhandItem().getItem() == item;
    }

    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            clearDisplays();
            return;
        }
        // te bajaste del kart en pleno rescate
        if (KartRescue.active && (p.getVehicle() == null || p.getVehicle().getId() != KartRescue.horseId)) {
            Entity e = mc.level.getEntity(KartRescue.horseId);
            KartRescue.abort(e instanceof AbstractHorse h ? h : null);
        }
        boolean wand = holds(p, KartCpItems.WAND);
        boolean eraser = !wand && holds(p, KartCpItems.ERASER);
        if (!wand && !eraser) {
            clearDisplays();
            return;
        }
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        BlockPos ghost = CpBox.ghost(eye, look);
        List<CpBox> list = boxes;
        CpBox.Hit sel = (wand && uiCornerSet) ? null : CpBox.pick(list, eye, look, 80.0);

        Set<String> keep = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            CpBox b = list.get(i);
            BlockState st = colorOf(b);
            if (sel != null && sel.box().equals(b)) {
                st = eraser ? Blocks.RED_STAINED_GLASS.defaultBlockState() : Blocks.YELLOW_STAINED_GLASS.defaultBlockState();
            }
            show("cp" + i, st, b.x1() - 0.003, b.y1() - 0.003, b.z1() - 0.003, b.size(0) + 0.006f, b.size(1) + 0.006f, b.size(2) + 0.006f, keep);
        }
        // el bloque de la mira (sin hitbox y sin reemplazar nada: es solo una imagen)
        show("ghost", Blocks.LIGHT_GRAY_STAINED_GLASS.defaultBlockState(), ghost.getX() - 0.002, ghost.getY() - 0.002, ghost.getZ() - 0.002, 1.004f, 1.004f, 1.004f, keep);
        if (wand && uiCornerSet) { // vista previa del checkpoint que se va a crear
            CpBox pv = CpBox.of(uiNumber, uiCorner, ghost);
            show("preview", Blocks.LIME_STAINED_GLASS.defaultBlockState(), pv.x1() - 0.004, pv.y1() - 0.004, pv.z1() - 0.004,
                    pv.size(0) + 0.008f, pv.size(1) + 0.008f, pv.size(2) + 0.008f, keep);
        }
        Iterator<Map.Entry<String, Disp>> it = DISPLAYS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Disp> e = it.next();
            if (!keep.contains(e.getKey())) {
                e.getValue().entity.discard();
                it.remove();
            }
        }

        if (overlayTimer-- <= 0) {
            overlayTimer = 10;
            String num = uiNumber == CpBox.FREE ? "ZONA LIBRE" : uiNumber == 0 ? "0 (META)" : String.valueOf(uiNumber);
            String target = sel == null ? "" : " §7| §eApuntando: " + (sel.box().isFree() ? "zona libre" : "#" + sel.box().number());
            String msg;
            if (eraser) {
                msg = "§c§lBORRAR §r§7| " + (sel == null ? "mira un checkpoint" : "click: eliminar" + target);
            } else {
                msg = "§b§lCHECKPOINT §f" + num + " §7| esquina " + (uiCornerSet ? "2/2" : "1/2")
                        + (sel != null ? target + " §7(click: estirar)" : "") + " §7| shift+click: numero";
            }
            KartModClient.showOverlay(Component.literal(msg));
        }
    }

    private static BlockState colorOf(CpBox b) {
        if (b.isFree()) {
            return Blocks.GREEN_STAINED_GLASS.defaultBlockState();
        }
        return (b.number() == 0 ? Blocks.WHITE_STAINED_GLASS : Blocks.PURPLE_STAINED_GLASS).defaultBlockState();
    }

    // ------------------------------------------------------------------ cajas (block displays locales)

    private static void show(String key, BlockState state, double x, double y, double z, float sx, float sy, float sz, Set<String> keep) {
        keep.add(key);
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.level instanceof ClientLevel level)) {
            return;
        }
        Disp d = DISPLAYS.get(key);
        if (d == null) {
            Display.BlockDisplay e = create(level, state);
            if (e == null) {
                return;
            }
            d = new Disp(e);
            d.state = state;
            DISPLAYS.put(key, d);
        } else if (!d.state.equals(state)) {
            if (setBlockState(d.entity, state)) {
                d.state = state;
            }
        }
        if (d.sx != sx || d.sy != sy || d.sz != sz) {
            setScale(d.entity, sx, sy, sz);
            d.sx = sx;
            d.sy = sy;
            d.sz = sz;
        }
        if (d.x != x || d.y != y || d.z != z) {
            d.entity.setPos(x, y, z);
            d.entity.setOldPosAndRot();
            d.x = x;
            d.y = y;
            d.z = z;
        }
    }

    private static void clearDisplays() {
        if (DISPLAYS.isEmpty()) {
            return;
        }
        for (Disp d : DISPLAYS.values()) {
            d.entity.discard();
        }
        DISPLAYS.clear();
    }

    private static Display.BlockDisplay create(ClientLevel level, BlockState state) {
        try {
            EntityType<?> type = blockDisplayType();
            if (type == null) {
                return warn("no encontre el tipo de entidad block_display");
            }
            Display.BlockDisplay d = new Display.BlockDisplay(type, level);
            d.setId(nextId--);
            if (!setBlockState(d, state)) {
                return warn("no encontre el metodo para asignar el bloque al block display");
            }
            if (!addToLevel(level, d)) {
                return warn("no encontre el metodo para anadir la entidad al mundo del cliente");
            }
            setViewRange(d, 16f);
            return d;
        } catch (Exception ex) {
            return warn("error creando la caja: " + ex);
        }
    }

    private static Display.BlockDisplay warn(String why) {
        if (!warned) {
            warned = true;
            System.err.println("[ctrkart] Checkpoints: no se pueden dibujar las cajas: " + why);
        }
        return null;
    }

    private static EntityType<?> blockDisplayType() {
        if (blockDisplayType == null) {
            for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
                Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(t);
                if (id != null && id.getPath().equals("block_display")) {
                    blockDisplayType = t;
                    break;
                }
            }
        }
        return blockDisplayType;
    }

    private static boolean setBlockState(Display.BlockDisplay d, BlockState state) {
        try {
            for (Method m : Display.BlockDisplay.class.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == BlockState.class && m.getName().toLowerCase().contains("blockstate")) {
                    m.setAccessible(true);
                    m.invoke(d, state);
                    return true;
                }
            }
        } catch (ReflectiveOperationException ex) {
            warn("setBlockState: " + ex);
        }
        return false;
    }

    private static boolean addToLevel(ClientLevel level, Display.BlockDisplay d) throws ReflectiveOperationException {
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

    private static Method transformSetter;
    private static Constructor<?> transformCtor;
    private static Constructor<?> vec3fCtor;
    private static Constructor<?> quatCtor;
    private static boolean transformSearched;

    /** Tamano de la caja: el block display dibuja un bloque de 1x1x1 y se estira con su transformacion (escala). */
    private static void setScale(Display d, float sx, float sy, float sz) {
        try {
            if (!transformSearched) {
                transformSearched = true;
                for (Method m : Display.class.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0].getSimpleName().equals("Transformation")) {
                        m.setAccessible(true);
                        transformSetter = m;
                        for (Constructor<?> c : m.getParameterTypes()[0].getConstructors()) {
                            if (c.getParameterCount() == 4) {
                                transformCtor = c;
                            }
                        }
                        break;
                    }
                }
                vec3fCtor = Class.forName("org.joml.Vector3f").getConstructor(float.class, float.class, float.class);
                quatCtor = Class.forName("org.joml.Quaternionf").getConstructor();
                if (transformSetter == null || transformCtor == null) {
                    System.err.println("[ctrkart] Checkpoints: no encontre como cambiar el tamano de las cajas (Display.setTransformation)");
                }
            }
            if (transformSetter == null || transformCtor == null) {
                return;
            }
            Object t = transformCtor.newInstance(vec3fCtor.newInstance(0f, 0f, 0f), quatCtor.newInstance(),
                    vec3fCtor.newInstance(sx, sy, sz), quatCtor.newInstance());
            transformSetter.invoke(d, t);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            if (!warned) {
                warned = true;
                System.err.println("[ctrkart] Checkpoints: no pude cambiar el tamano de una caja: " + ex);
            }
        }
    }

    /** Distancia desde la que se dibuja (por defecto 1 = 64 bloques): las cajas grandes se ven desde mas lejos. */
    private static void setViewRange(Display d, float range) {
        try {
            for (Method m : Display.class.getDeclaredMethods()) {
                if (m.getName().equals("setViewRange") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == float.class) {
                    m.setAccessible(true);
                    m.invoke(d, range);
                    return;
                }
            }
            Field f = Display.class.getDeclaredField("DATA_VIEW_RANGE_ID");
            f.setAccessible(true);
            Object accessor = f.get(null);
            Method set = d.getEntityData().getClass().getMethod("set", Class.forName("net.minecraft.network.syncher.EntityDataAccessor"), Object.class);
            set.invoke(d.getEntityData(), accessor, range);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // sin esto, las cajas solo se ven de cerca
        }
    }
}
