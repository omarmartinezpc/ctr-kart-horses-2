package com.ctrkart.client;

import com.ctrkart.CpBox;
import com.ctrkart.KartCpItems;
import com.ctrkart.KartBlocks;
import com.ctrkart.KartCpActionPayload;
import com.ctrkart.KartCpDraw;
import com.ctrkart.KartCpPayload;
import com.ctrkart.KartModClient;
import com.ctrkart.KartPhysics;
import com.ctrkart.KartRescue;
import com.ctrkart.KartState;
import com.ctrkart.RescuePoint;
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
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
import net.minecraft.world.level.block.Block;
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
    private static volatile List<RescuePoint> points = List.of();
    private static int uiNumber;
    private static boolean uiCornerSet;
    private static BlockPos uiCorner = BlockPos.ZERO;
    private static CpBox uiStretchBox;   // checkpoint cuya cara se eligio para estirar (null = nada elegido)
    private static int uiStretchAxis;
    private static int uiStretchSide;
    // "Dibujar Checkpoint": contorno que se esta dibujando (ya unido en rectangulos) y area que se estira
    private static List<int[]> drawRects = List.of();
    private static int drawCount;
    private static int drawLevel;
    private static int drawStretchGroup;
    private static int drawStretchSide;

    private static final class Disp {
        final Display.BlockDisplay entity;
        BlockState state;
        double x = Double.NaN;
        double y;
        double z;
        float sx;
        float sy;
        float sz;
        boolean glow;
        int glowColor = -2;

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
                for (int i = 0; i + 7 < d.size(); i += 8) {
                    l.add(CpBox.read(d, i));
                }
                boxes = l;
                break;
            }
            case KartCpPayload.POINTS: {
                List<RescuePoint> l = new ArrayList<>();
                for (int i = 0; i + 3 < d.size(); i += 4) {
                    l.add(new RescuePoint(d.get(i), d.get(i + 1), d.get(i + 2), d.get(i + 3)));
                }
                points = l;
                break;
            }
            case KartCpPayload.UI:
                if (d.size() >= 5) {
                    uiNumber = d.get(0);
                    uiCornerSet = d.get(1) != 0;
                    uiCorner = new BlockPos(d.get(2), d.get(3), d.get(4));
                    boolean stretching = d.size() >= 14 && d.get(5) != 0;
                    uiStretchBox = stretching ? new CpBox(CpBox.FREE, d.get(6), d.get(7), d.get(8), d.get(9), d.get(10), d.get(11)) : null;
                    uiStretchAxis = stretching ? d.get(12) : 0;
                    uiStretchSide = stretching ? d.get(13) : 0;
                }
                break;
            case KartCpPayload.DRAW:
                if (d.size() >= 4) {
                    drawStretchGroup = d.get(0);
                    drawStretchSide = d.get(1);
                    drawLevel = d.get(2);
                    List<Long> cells = new ArrayList<>();
                    int n = d.get(3);
                    for (int i = 0; i < n && 5 + 2 * i < d.size(); i++) {
                        cells.add(KartCpDraw.key(d.get(4 + 2 * i), d.get(5 + 2 * i)));
                    }
                    drawCount = cells.size();
                    drawRects = KartCpDraw.rects(cells);
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
            case KartCpPayload.PLACE: {
                if (mc.level == null || d.size() < 5) {
                    break;
                }
                Entity e = mc.level.getEntity(d.get(0));
                if (e instanceof AbstractHorse horse) {
                    KartPhysics.tpEffect(horse);
                    KartModClient.moveKart(horse, d.get(1) / 1000.0, d.get(2) / 1000.0, d.get(3) / 1000.0);
                    KartState st = KartTracker.get(horse.getId());
                    if (st != null) {
                        KartPhysics.placeAt(horse, st, d.get(2) / 1000.0, d.get(4) / 10.0f);
                    }
                    KartPhysics.tpEffect(horse);
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
        points = List.of();
        drawRects = List.of();
        drawCount = 0;
        drawStretchGroup = 0;
        KartRescue.active = false;
        KartRescue.raceStarted = false;
        clearDisplays();
    }

    // ------------------------------------------------------------------ cada tick

    private static boolean holds(LocalPlayer p, Item item) {
        return p.getMainHandItem().getItem() == item || p.getOffhandItem().getItem() == item;
    }

    /**
     * Al EMPEZAR cada tick (antes de que el juego lea el raton): con "Dibujar Checkpoint" en la mano, el click izquierdo se
     * gasta aqui y se manda al servidor (rellenar / cancelar); asi el juego no lo usa para atacar ni romper. Montado en un
     * kart no se toca, porque ahi el click izquierdo es del turbo.
     */
    public static void startTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || p.getVehicle() != null || !holds(p, KartCpDraw.ITEM)) {
            return;
        }
        boolean any = false;
        while (mc.options.keyAttack.consumeClick()) {
            any = true;
        }
        if (any) {
            ClientPlayNetworking.send(new KartCpActionPayload(KartCpActionPayload.LEFT_CLICK, 0));
        }
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
        boolean rescue = !wand && !eraser && holds(p, KartCpItems.RESCUE);
        boolean draw = !wand && !eraser && !rescue && holds(p, KartCpDraw.ITEM);
        if (!wand && !eraser && !rescue && !draw) {
            clearDisplays();
            return;
        }
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        BlockPos ghost = CpBox.ghost(eye, look);
        List<CpBox> list = boxes;
        List<RescuePoint> pts = points;

        // lo que apuntas: un checkpoint (creacion / borrado) o un bloque de Rescate (rescate / borrado)
        boolean stretching = wand && uiStretchBox != null;
        boolean dStretch = draw && drawStretchGroup != 0;
        CpBox.Hit sel;
        if (draw) { // con "Dibujar Checkpoint" solo se apuntan las areas dibujadas
            List<CpBox> areas = new ArrayList<>();
            for (CpBox b : list) {
                if (b.group() != 0) {
                    areas.add(b);
                }
            }
            sel = drawCount > 0 || dStretch ? null : CpBox.pick(areas, eye, look, 80.0);
        } else {
            sel = (wand && uiCornerSet) || rescue || stretching ? null : CpBox.pick(list, eye, look, 80.0);
        }
        int wandStretchGroup = stretching ? groupOf(list, uiStretchBox) : 0;
        CpBox.Hit rsel = null;
        if (rescue || eraser) {
            List<CpBox> pb = new ArrayList<>();
            for (RescuePoint r : pts) {
                pb.add(r.box());
            }
            rsel = CpBox.pick(pb, eye, look, 80.0);
            if (rsel != null && sel != null && rsel.t() <= sel.t()) {
                sel = null; // el bloque de Rescate esta mas cerca: el checkpoint de detras no se resalta
            } else if (rsel != null && sel != null) {
                rsel = null;
            }
        }

        Set<String> keep = new HashSet<>();
        // Checkpoints: cristal de color. Cada caja se dibuja dos veces: normal (se ve desde fuera) y con la escala al reves
        // (se ve desde DENTRO, porque un cristal normal no se dibuja visto desde su interior).
        for (int i = 0; i < list.size(); i++) {
            CpBox b = list.get(i);
            if (b.group() != 0) {
                continue; // las areas dibujadas se pintan aparte, como una sola pieza (ver mas abajo)
            }
            BlockState st = colorOf(b);
            if (sel != null && sel.box().group() == 0 && sel.box().equals(b)) {
                st = eraser ? glass("red") : glass("yellow");
            }
            if (stretching && sameBox(b, uiStretchBox)) {
                st = glass("yellow"); // el checkpoint que se esta estirando
            }
            double e = 0.003;
            double sx = b.size(0) + 2 * e;
            double sy = b.size(1) + 2 * e;
            double sz = b.size(2) + 2 * e;
            show("cp" + i, st, b.x1() - e, b.y1() - e, b.z1() - e, (float) sx, (float) sy, (float) sz, keep);
            show("ci" + i, st, b.x1() - e + sx, b.y1() - e + sy, b.z1() - e + sz, (float) -sx, (float) -sy, (float) -sz, keep);
        }
        // Areas dibujadas: UNA sola pieza (solo su cascara exterior), no un cristal por cada rectangulo
        for (Map.Entry<Integer, Foot> g : feet(list).entrySet()) {
            int gid = g.getKey();
            Foot f = g.getValue();
            BlockState st = colorOf(f.sample);
            if (sel != null && sel.box().group() == gid) {
                st = eraser ? glass("red") : glass("yellow"); // se resalta entera
            }
            if ((stretching && wandStretchGroup == gid) || (dStretch && drawStretchGroup == gid)) {
                st = glass("yellow");
            }
            shell("g" + gid + "_", f, f.sample.y1(), f.sample.y2(), st, 0.003, keep);
        }
        // Bloques de Rescate (solidos): cuerpo naranja con una "nariz" blanca en la cara hacia la que mira el kart
        if (rescue || eraser) {
            for (int i = 0; i < pts.size(); i++) {
                RescuePoint r = pts.get(i);
                boolean aimed = rsel != null && rsel.box().x1() == r.x() && rsel.box().y1() == r.y() && rsel.box().z1() == r.z();
                BlockState body = aimed ? (eraser ? solid("red_concrete") : solid("yellow_concrete")) : solid("orange_concrete");
                show("rp" + i, body, r.x() - 0.001, r.y() - 0.001, r.z() - 0.001, 1.002f, 1.002f, 1.002f, keep);
                double rad = Math.toRadians(r.yaw());
                double fx = -Math.sin(rad);
                double fz = Math.cos(rad);
                double cx = r.x() + 0.5 + fx * 0.6;
                double cz = r.z() + 0.5 + fz * 0.6;
                show("rn" + i, solid("white_concrete"), cx - 0.15, r.y() + 0.35, cz - 0.15, 0.3f, 0.3f, 0.3f, keep);
            }
        }
        // el bloque de la mira (sin hitbox y sin reemplazar nada: es solo una imagen)
        // contorno que se esta dibujando (mismo cristal y color que un checkpoint con ese numero)
        if (draw && drawCount > 0) {
            BlockState cs = colorOf(new CpBox(uiNumber, 0, 0, 0, 0, 0, 0));
            for (int i = 0; i < drawRects.size(); i++) {
                int[] r = drawRects.get(i);
                double e = 0.003;
                double sx = r[1] - r[0] + 1 + 2 * e;
                double sy = 1 + 2 * e;
                double sz = r[3] - r[2] + 1 + 2 * e;
                show("dc" + i, cs, r[0] - e, drawLevel - e, r[2] - e, (float) sx, (float) sy, (float) sz, keep);
                glow("dc" + i, eye.y > drawLevel + 1.0, glowColorOf(uiNumber)); // visto desde arriba se ve el contorno a traves del suelo
                show("di" + i, cs, r[0] - e + sx, drawLevel - e + sy, r[2] - e + sz, (float) -sx, (float) -sy, (float) -sz, keep);
            }
        }
        BlockPos gp = draw && drawCount > 0 && !dStretch ? KartCpDraw.target(eye, look, drawLevel) : ghost;
        show("ghost", glass("light_gray"), gp.getX() - 0.002, gp.getY() - 0.002, gp.getZ() - 0.002, 1.004f, 1.004f, 1.004f, keep);
        glow("ghost", draw && eye.y > gp.getY() + 1.0, 0xCCCCCC);
        List<CpBox> pvs = new ArrayList<>();
        if (wand && uiCornerSet) { // vista previa del checkpoint que se va a crear
            pvs.add(CpBox.of(uiNumber, uiCorner, ghost));
        } else if (stretching) {   // vista previa del checkpoint ya estirado hasta el bloque de la mira
            int axis = uiStretchAxis;
            int target = axis == 0 ? ghost.getX() : axis == 1 ? ghost.getY() : ghost.getZ();
            if (wandStretchGroup != 0) {
                shellPreview(list, wandStretchGroup, axis, uiStretchSide, target, keep);
            } else {
                pvs.addAll(stretched(list, 0, uiStretchBox, axis, uiStretchSide, target));
            }
        } else if (dStretch) {     // area dibujada estirada (solo en vertical) hasta el bloque de la mira
            shellPreview(list, drawStretchGroup, 1, drawStretchSide, ghost.getY(), keep);
        }
        for (int i = 0; i < pvs.size(); i++) {
            CpBox pv = pvs.get(i);
            double e = 0.004;
            double sx = pv.size(0) + 2 * e;
            double sy = pv.size(1) + 2 * e;
            double sz = pv.size(2) + 2 * e;
            show("preview" + i, glass("lime"), pv.x1() - e, pv.y1() - e, pv.z1() - e, (float) sx, (float) sy, (float) sz, keep);
            show("previewi" + i, glass("lime"), pv.x1() - e + sx, pv.y1() - e + sy, pv.z1() - e + sz, (float) -sx, (float) -sy, (float) -sz, keep);
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
            if (draw) {
                if (dStretch) {
                    msg = "§e§lESTIRAR AREA §r§7| click a la altura deseada (se ve la vista previa) §7| shift+click: cancelar";
                } else if (drawCount > 0) {
                    msg = "§b§lDIBUJAR §f" + num + " §7| " + drawCount + " bloques | click der: dibujar (manten: trazar; sobre un bloque: quitar)"
                            + " | click izq DENTRO: rellenar | shift+click izq: cancelar";
                } else {
                    msg = "§b§lDIBUJAR CHECKPOINT §f" + num + " §7| click derecho: dibujar el contorno"
                            + (sel != null ? " §7| §eApuntando: area #" + sel.box().number() + " §7(click en cara de arriba/abajo: estirar | shift+click: poner el numero " + num + ")"
                            : " §7| shift+click: cambiar numero");
                }
            } else if (eraser) {
                msg = "§c§lBORRAR §r§7| " + (rsel != null ? "click: eliminar el bloque de Rescate"
                        : sel == null ? "mira un checkpoint o un bloque de Rescate" : "click: eliminar" + target);
            } else if (rescue) {
                msg = "§d§lRESCATE §r§7| " + (rsel != null ? "click en una cara lateral: orientar" : "click: colocar dentro de un checkpoint");
            } else if (stretching) {
                msg = "§e§lESTIRAR §r§7| click a la distancia deseada (se ve la vista previa) §7| shift+click: cancelar";
            } else {
                msg = "§b§lCHECKPOINT §f" + num + " §7| esquina " + (uiCornerSet ? "2/2" : "1/2")
                        + (sel != null ? target + " §7(click: estirar | shift+click: poner el numero " + num + ")" : " §7| shift+click: cambiar numero");
            }
            KartModClient.showOverlay(Component.literal(msg));
        }
    }

    /** Misma caja (el numero no cuenta: el servidor manda el estado de estirar sin el numero). */
    private static boolean sameBox(CpBox a, CpBox b) {
        return a.x1() == b.x1() && a.y1() == b.y1() && a.z1() == b.z1() && a.x2() == b.x2() && a.y2() == b.y2() && a.z2() == b.z2();
    }

    // ------------------------------------------------------------------ areas dibujadas (una sola pieza)

    /** Forma de un area dibujada, vista desde arriba: sus rectangulos grandes (caras de arriba/abajo) y sus paredes exteriores. */
    private static final class Foot {
        CpBox sample;            // una de sus partes (da el numero y la altura: todas comparten y1, y2)
        List<int[]> tops;        // [x1, x2, z1, z2]
        List<int[]> walls;       // [eje, plano, desde, hasta] (ver KartCpDraw.wallRuns)
    }

    private static List<CpBox> feetFor;
    private static Map<Integer, Foot> feet = new HashMap<>();

    /** Formas de todas las areas dibujadas de la lista (se calculan una vez por cada lista nueva que manda el servidor). */
    private static Map<Integer, Foot> feet(List<CpBox> list) {
        if (feetFor == list) {
            return feet;
        }
        Map<Integer, Set<Long>> cells = new HashMap<>();
        Map<Integer, CpBox> sample = new HashMap<>();
        for (CpBox b : list) {
            if (b.group() == 0) {
                continue;
            }
            sample.putIfAbsent(b.group(), b);
            Set<Long> set = cells.computeIfAbsent(b.group(), k -> new HashSet<>());
            for (int x = b.x1(); x <= b.x2(); x++) {
                for (int z = b.z1(); z <= b.z2(); z++) {
                    set.add(KartCpDraw.key(x, z));
                }
            }
        }
        Map<Integer, Foot> out = new HashMap<>();
        for (Map.Entry<Integer, Set<Long>> e : cells.entrySet()) {
            Foot f = new Foot();
            f.sample = sample.get(e.getKey());
            f.tops = KartCpDraw.fatRects(e.getValue());
            f.walls = KartCpDraw.wallRuns(e.getValue());
            out.put(e.getKey(), f);
        }
        feet = out;
        feetFor = list;
        return out;
    }

    /**
     * Dibuja el area como UNA sola pieza: solo las caras exteriores (arriba, abajo y paredes), cada una como una lamina de
     * cristal muy fina. Se ven desde los dos lados, asi que no hace falta la copia "al reves" de las cajas normales.
     */
    private static void shell(String prefix, Foot f, int y1, int y2, BlockState st, double e, Set<String> keep) {
        double t = 0.006; // grosor de las laminas
        double h = y2 - y1 + 1;
        int n = 0;
        for (int[] r : f.tops) {
            double w = r[1] - r[0] + 1 + 2 * e;
            double d = r[3] - r[2] + 1 + 2 * e;
            show(prefix + (n++), st, r[0] - e, y2 + 1 - t / 2, r[2] - e, (float) w, (float) t, (float) d, keep);
            show(prefix + (n++), st, r[0] - e, y1 - t / 2, r[2] - e, (float) w, (float) t, (float) d, keep);
        }
        for (int[] w : f.walls) {
            double len = w[3] - w[2] + 1 + 2 * e;
            if (w[0] == 0) { // pared que recorre X, en el plano z = w[1]
                show(prefix + (n++), st, w[2] - e, y1 - e, w[1] - t / 2, (float) len, (float) (h + 2 * e), (float) t, keep);
            } else {         // pared que recorre Z, en el plano x = w[1]
                show(prefix + (n++), st, w[1] - t / 2, y1 - e, w[2] - e, (float) t, (float) (h + 2 * e), (float) len, keep);
            }
        }
    }

    /** Vista previa (verde) de un area dibujada con su cara de arriba o de abajo movida a "target". */
    private static void shellPreview(List<CpBox> list, int group, int axis, int side, int target, Set<String> keep) {
        Foot f = feet(list).get(group);
        if (f == null || axis != 1) {
            return;
        }
        CpBox nb = f.sample.withBound(1, side, target);
        if (nb != null) {
            shell("pg_", f, nb.y1(), nb.y2(), glass("lime"), 0.004, keep);
        }
    }

    /** Grupo (area dibujada) al que pertenece esa caja de la lista, o 0. */
    private static int groupOf(List<CpBox> list, CpBox like) {
        for (CpBox b : list) {
            if (sameBox(b, like)) {
                return b.group();
            }
        }
        return 0;
    }

    /** La caja (o, si es un area dibujada, todas sus partes) con una cara movida a "target". */
    private static List<CpBox> stretched(List<CpBox> list, int group, CpBox single, int axis, int side, int target) {
        List<CpBox> out = new ArrayList<>();
        if (group != 0) {
            for (CpBox b : list) {
                if (b.group() == group && axis == 1) {
                    CpBox n = b.withBound(axis, side, target);
                    if (n != null) {
                        out.add(n);
                    }
                }
            }
        } else if (single != null) {
            CpBox n = single.withBound(axis, side, target);
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }

    private static BlockState colorOf(CpBox b) {
        if (b.isFree()) {
            return glass("green");
        }
        return glass(b.number() == 0 ? "white" : "purple");
    }

    private static final Map<String, BlockState> GLASS = new HashMap<>();

    /**
     * Cristal tintado de ese color. Las constantes Blocks.RED_STAINED_GLASS... no existen en esta version, asi que se
     * busca en el registro por nombre (red_stained_glass). Si no hay, se usa el cristal normal y, por ultimo, el bloque TP.
     */
    private static BlockState glass(String color) {
        BlockState st = GLASS.get("g:" + color);
        if (st != null) {
            return st;
        }
        Block found = find(color + "_stained_glass");
        if (found == null) {
            System.err.println("[ctrkart] Checkpoints: no hay " + color + "_stained_glass; se usa el cristal normal.");
            found = find("glass");
        }
        st = (found != null ? found : KartBlocks.TP_ENTRY).defaultBlockState();
        GLASS.put("g:" + color, st);
        return st;
    }

    /** Bloque solido por nombre (para los bloques de Rescate); si no existe, piedra. */
    private static BlockState solid(String path) {
        BlockState st = GLASS.get("s:" + path);
        if (st != null) {
            return st;
        }
        Block found = find(path);
        if (found == null) {
            System.err.println("[ctrkart] Checkpoints: no hay el bloque " + path + "; se usa piedra.");
            found = find("stone");
        }
        st = (found != null ? found : KartBlocks.SACRED_PAD).defaultBlockState();
        GLASS.put("s:" + path, st);
        return st;
    }

    private static Block find(String path) {
        for (Block b : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(b);
            if (id != null && id.getPath().equals(path)) {
                return b;
            }
        }
        return null;
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

    private static int glowColorOf(int number) {
        return number == CpBox.FREE ? 0x55FF55 : number == 0 ? 0xFFFFFF : 0xB060FF;
    }

    /**
     * Contorno brillante: el juego dibuja el contorno de las entidades "brillantes" a traves de los bloques. Se usa para que el
     * dibujo se vea a traves del suelo cuando lo miras desde arriba (solo en vertical: a la misma altura o desde abajo no brilla).
     */
    private static void glow(String key, boolean on, int color) {
        Disp d = DISPLAYS.get(key);
        if (d == null) {
            return;
        }
        if (d.glow != on) {
            setGlowing(d.entity, on);
            d.glow = on;
        }
        if (on && d.glowColor != color) {
            setGlowColor(d.entity, color);
            d.glowColor = color;
        }
    }

    private static void setGlowing(Entity e, boolean on) {
        try {
            for (Method m : Entity.class.getMethods()) {
                if (m.getName().equals("setGlowingTag") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class) {
                    m.invoke(e, on);
                    return;
                }
            }
            for (Method m : Entity.class.getDeclaredMethods()) { // alternativa: la bandera compartida 6 (brillo)
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("setSharedFlag") && p.length == 2 && p[0] == int.class && p[1] == boolean.class) {
                    m.setAccessible(true);
                    m.invoke(e, 6, on);
                    return;
                }
            }
            if (!warned) {
                warned = true;
                System.err.println("[ctrkart] Checkpoints: no encontre como hacer brillar las cajas (setGlowingTag); no se veran a traves del suelo");
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            if (!warned) {
                warned = true;
                System.err.println("[ctrkart] Checkpoints: no pude hacer brillar una caja: " + ex);
            }
        }
    }

    private static void setGlowColor(Display d, int color) {
        try {
            for (Method m : Display.class.getDeclaredMethods()) {
                if (m.getName().equals("setGlowColorOverride") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class) {
                    m.setAccessible(true);
                    m.invoke(d, color);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // sin esto el contorno sale blanco
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
