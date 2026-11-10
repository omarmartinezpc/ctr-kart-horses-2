package com.ctrkart;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Herramienta "Dibujar Checkpoint" (item checkpoint_draw).
 *
 * DIBUJAR (click derecho): cada click pone un bloque del contorno (translucido, como los checkpoints). El primer bloque fija
 *  la altura del dibujo; los demas se colocan en ese mismo nivel, donde apunta la mira. Si MANTIENES el click y mueves la
 *  mira, traza una linea continua. Un click suelto sobre un bloque ya dibujado lo quita (y si mantienes, sigue quitando).
 * RELLENAR (click izquierdo, DENTRO del contorno): como el cubo de pintura; si el contorno esta cerrado se rellena en
 *  horizontal, 1 bloque de alto, y queda como un checkpoint mas (con el numero elegido). Si hay un hueco, avisa y no rellena.
 *  Agachado + click izquierdo: cancela el dibujo.
 * ESTIRAR (click derecho en la cara de ARRIBA o de ABAJO de un area ya rellenada, y luego otro click a la altura deseada):
 *  solo en vertical; las caras laterales no se estiran.
 * NUMERO: agachado + click derecho (+1, o se lo pone al area que apuntas), /kartcp, o la varita de creacion.
 * Con la herramienta en la mano no se rompen bloques.
 *
 * Un area rellenada se guarda como varios rectangulos (CpBox con el mismo numero y el mismo "group"), asi que la carrera,
 * el rescate y los bloques de Rescate funcionan igual que con cualquier otro checkpoint.
 */
public final class KartCpDraw {
    public static final Item ITEM = register("checkpoint_draw");

    public static final int MAX_CELLS = 3000;          // bloques dibujados como maximo
    private static final double PICK_DIST = 80.0;      // hasta donde se "selecciona" un area con la mira
    private static final double PLANE_REACH = 24.0;    // hasta donde llega la mira sobre el plano del dibujo
    private static final long HOLD_GAP = 6;            // ticks: menos que esto entre dos clicks = el boton sigue mantenido
    private static final int MAX_GRID = 160_000;       // celdas maximas que se examinan al rellenar
    private static final int MAX_RECTS = 200;          // rectangulos maximos de un area rellenada
    private static final int MAX_LINE = 64;            // si dos clicks seguidos estan mas lejos, no se traza linea entre ellos

    private static final class Draw {
        String dim;
        int y;
        final Set<Long> cells = new LinkedHashSet<>();
        boolean hasLast;
        int lastX;
        int lastZ;
        long lastTick = -1000;
        boolean erasing;
        int stretchGroup;
        int stretchSide;

        void reset() {
            cells.clear();
            hasLast = false;
            erasing = false;
            stretchGroup = 0;
            stretchSide = 0;
        }
    }

    private static final Map<UUID, Draw> DRAWS = new HashMap<>();

    private KartCpDraw() {}

    private static Item register(String path) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(KartMod.MOD_ID, path));
        return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(1)));
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(KartCpActionPayload.TYPE, KartCpActionPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(KartCpActionPayload.TYPE, (payload, context) -> {
            if (payload.kind() == KartCpActionPayload.LEFT_CLICK) {
                leftClick(context.player());
            }
        });
        UseItemCallback.EVENT.register((player, world, hand) -> use(player, world, hand));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> use(player, world, hand));
        // con la herramienta en la mano no se rompen bloques (cliente y servidor)
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
                holds(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> DRAWS.remove(handler.getPlayer().getUUID()));
        if (!KartItem.registerCreativeEntry(CreativeModeTabs.TOOLS_AND_UTILITIES, () -> new ItemStack(ITEM), "Dibujar Checkpoint")) {
            System.err.println("[ctrkart] No pude anadir Dibujar Checkpoint al creativo. Alternativa: /give @s ctrkart:checkpoint_draw");
        }
    }

    public static boolean holds(Player p) {
        return p.getMainHandItem().getItem() == ITEM || p.getOffhandItem().getItem() == ITEM;
    }

    // ------------------------------------------------------------------ utilidades (las usa tambien el cliente)

    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public static int keyX(long k) {
        return (int) (k >> 32);
    }

    public static int keyZ(long k) {
        return (int) k;
    }

    /**
     * Bloque donde se dibuja: el punto en que la mira corta el plano horizontal del dibujo (a "level"); si la mira no llega
     * a ese plano (mirando hacia arriba o muy lejos), el bloque de la mira (a 4 bloques) llevado a ese nivel.
     */
    public static BlockPos target(Vec3 eye, Vec3 look, int level) {
        if (Math.abs(look.y) > 1e-4) {
            double t = (level + 0.5 - eye.y) / look.y;
            if (t > 0.0 && t <= PLANE_REACH) {
                return new BlockPos((int) Math.floor(eye.x + look.x * t), level, (int) Math.floor(eye.z + look.z * t));
            }
        }
        BlockPos g = CpBox.ghost(eye, look);
        return new BlockPos(g.getX(), level, g.getZ());
    }

    /** Junta las celdas (x, z) en pocos rectangulos [x1, x2, z1, z2]: primero por filas y luego se unen las filas iguales seguidas. */
    public static List<int[]> rects(Collection<Long> cells) {
        TreeMap<Integer, TreeSet<Integer>> rows = new TreeMap<>();
        for (long k : cells) {
            rows.computeIfAbsent(keyZ(k), z -> new TreeSet<>()).add(keyX(k));
        }
        List<int[]> done = new ArrayList<>();
        Map<Long, int[]> active = new HashMap<>();
        boolean first = true;
        int prevZ = 0;
        for (Map.Entry<Integer, TreeSet<Integer>> row : rows.entrySet()) {
            int z = row.getKey();
            if (!first && z != prevZ + 1) {
                done.addAll(active.values());
                active.clear();
            }
            Map<Long, int[]> next = new HashMap<>();
            boolean open = false;
            int start = 0;
            int prev = 0;
            for (int x : row.getValue()) {
                if (open && x == prev + 1) {
                    prev = x;
                    continue;
                }
                if (open) {
                    emit(start, prev, z, active, next);
                }
                start = x;
                prev = x;
                open = true;
            }
            if (open) {
                emit(start, prev, z, active, next);
            }
            done.addAll(active.values()); // las que no continuaron en esta fila ya estan completas
            active = next;
            prevZ = z;
            first = false;
        }
        done.addAll(active.values());
        return done;
    }

    private static void emit(int x1, int x2, int z, Map<Long, int[]> active, Map<Long, int[]> next) {
        long k = ((long) x1 << 32) | (x2 & 0xffffffffL);
        int[] r = active.remove(k);
        if (r == null) {
            r = new int[] {x1, x2, z, z};
        } else {
            r[3] = z;
        }
        next.put(k, r);
    }

    // ------------------------------------------------------------------ servidor

    private static String label(int n) {
        return n == CpBox.FREE ? "ZONA SIN RESTRICCIONES" : n == 0 ? "0 (META)" : String.valueOf(n);
    }

    private static Draw state(ServerPlayer sp, ServerLevel level) {
        Draw d = DRAWS.computeIfAbsent(sp.getUUID(), k -> new Draw());
        String dim = level.dimension().toString();
        if (!dim.equals(d.dim)) {
            d.reset();
            d.dim = dim;
        }
        return d;
    }

    private static void sendDraw(ServerPlayer sp, Draw d) {
        List<Integer> out = new ArrayList<>();
        out.add(d.stretchGroup);
        out.add(d.stretchSide);
        out.add(d.y);
        out.add(d.cells.size());
        for (long k : d.cells) {
            out.add(keyX(k));
            out.add(keyZ(k));
        }
        ServerPlayNetworking.send(sp, new KartCpPayload(KartCpPayload.DRAW, out));
    }

    private static void say(ServerPlayer sp, String text) {
        sp.sendSystemMessage(Component.literal(text));
    }

    private static InteractionResult use(Player player, Level world, InteractionHand hand) {
        if (player.getItemInHand(hand).getItem() != ITEM) {
            return InteractionResult.PASS;
        }
        if (!(world instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.SUCCESS; // en el cliente: solo mueve el brazo; lo demas lo hace el servidor
        }
        Draw d = state(sp, level);
        long now = level.getGameTime();
        boolean fresh = now - d.lastTick > HOLD_GAP; // click nuevo (no es el boton mantenido repitiendose)
        d.lastTick = now;
        Vec3 eye = sp.getEyePosition();
        Vec3 look = sp.getLookAngle();
        BlockPos ghost = CpBox.ghost(eye, look);
        List<CpBox> areas = new ArrayList<>();
        for (CpBox b : KartCheckpoints.list(level)) {
            if (b.group() != 0) {
                areas.add(b);
            }
        }
        CpBox.Hit sel = d.cells.isEmpty() && d.stretchGroup == 0 ? CpBox.pick(areas, eye, look, PICK_DIST) : null;

        // ---------------------------------------------------------------- agachado: cancelar estirar / numero
        if (sp.isShiftKeyDown()) {
            if (!fresh) {
                return InteractionResult.SUCCESS;
            }
            if (d.stretchGroup != 0) {
                d.stretchGroup = 0;
                say(sp, "§e[CP] Estirar cancelado.");
            } else if (sel != null) { // shift + click en un area dibujada: recibe el numero elegido
                int n = KartCpItems.numberOf(sp);
                if (sel.box().number() == n) {
                    say(sp, "§e[CP] Esa area ya tiene el numero " + label(n) + ".");
                } else {
                    int old = sel.box().number();
                    KartCheckpoints.renumber(level, sel.box(), n);
                    say(sp, "§a[CP] Numero cambiado: " + label(old) + " §f-> §a§l" + label(n));
                }
            } else {
                int n = KartCpItems.numberOf(sp);
                KartCpItems.setNumber(sp, n == CpBox.MAX_NUMBER ? CpBox.FREE : n == CpBox.FREE ? 0 : n + 1);
            }
            sendDraw(sp, d);
            return InteractionResult.SUCCESS;
        }

        // ---------------------------------------------------------------- 2o click de estirar
        if (d.stretchGroup != 0) {
            if (!fresh) {
                return InteractionResult.SUCCESS;
            }
            int group = d.stretchGroup;
            int side = d.stretchSide;
            d.stretchGroup = 0;
            int h = KartCheckpoints.stretchGroup(level, group, side, ghost.getY());
            say(sp, h < 0 ? "§c[CP] No se puede estirar hasta ahi (la cara se pasaria al otro lado) o el area ya no existe. Estirar cancelado."
                    : "§e[CP] Area estirada (alto " + h + ").");
            sendDraw(sp, d);
            return InteractionResult.SUCCESS;
        }

        // ---------------------------------------------------------------- 1er click de estirar: cara de arriba o de abajo
        if (sel != null && sel.axis() == 1 && fresh) {
            d.stretchGroup = sel.box().group();
            d.stretchSide = sel.side();
            say(sp, "§e[CP] Cara de " + (sel.side() > 0 ? "arriba" : "abajo") + " elegida. Haz click a la altura a la que quieres "
                    + "estirarla (agachado + click cancela).");
            sendDraw(sp, d);
            return InteractionResult.SUCCESS;
        }

        // ---------------------------------------------------------------- dibujar / quitar bloques del contorno
        BlockPos cell;
        if (d.cells.isEmpty()) {
            d.y = ghost.getY(); // el primer bloque fija la altura del dibujo
            cell = ghost;
        } else {
            cell = target(eye, look, d.y);
        }
        long k = key(cell.getX(), cell.getZ());
        if (fresh) {
            d.erasing = d.cells.contains(k); // click suelto sobre un bloque dibujado: se quita (y manteniendo, sigue quitando)
        }
        if (d.erasing) {
            d.cells.remove(k);
            d.hasLast = false;
        } else {
            if (d.cells.size() >= MAX_CELLS && !d.cells.contains(k)) {
                if (fresh) {
                    say(sp, "§c[CP] Maximo de " + MAX_CELLS + " bloques dibujados. Rellena el area o quita bloques.");
                }
                return InteractionResult.SUCCESS;
            }
            if (!fresh && d.hasLast) {
                line(d, d.lastX, d.lastZ, cell.getX(), cell.getZ());
            } else {
                d.cells.add(k);
            }
            d.hasLast = true;
            d.lastX = cell.getX();
            d.lastZ = cell.getZ();
        }
        if (d.cells.isEmpty()) {
            d.hasLast = false;
        }
        sendDraw(sp, d);
        return InteractionResult.SUCCESS;
    }

    /** Linea de bloques (en diagonal tambien cuentan como unidos) entre dos puntos del dibujo. */
    private static void line(Draw d, int x0, int z0, int x1, int z1) {
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        if (dx > MAX_LINE || dz > MAX_LINE) {
            d.cells.add(key(x1, z1));
            return;
        }
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int x = x0;
        int z = z0;
        while (true) {
            if (d.cells.size() < MAX_CELLS) {
                d.cells.add(key(x, z));
            }
            if (x == x1 && z == z1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }
    }

    /** Click izquierdo (lo manda el cliente): rellenar el area; agachado, cancelar el dibujo. */
    private static void leftClick(ServerPlayer sp) {
        if (!holds(sp) || !(sp.level() instanceof ServerLevel level)) {
            return;
        }
        Draw d = state(sp, level);
        if (sp.isShiftKeyDown()) {
            if (!d.cells.isEmpty() || d.stretchGroup != 0) {
                d.reset();
                say(sp, "§e[CP] Dibujo cancelado.");
                sendDraw(sp, d);
            }
            return;
        }
        if (d.cells.isEmpty()) {
            say(sp, "§c[CP] Primero dibuja el contorno del area con click derecho.");
            return;
        }
        fill(sp, level, d);
    }

    private static void fill(ServerPlayer sp, ServerLevel level, Draw d) {
        int sx = (int) Math.floor(sp.getX());
        int sz = (int) Math.floor(sp.getZ());
        if (d.cells.contains(key(sx, sz))) {
            say(sp, "§c[CP] Estas encima de una linea del contorno. Colocate DENTRO del area y vuelve a hacer click izquierdo.");
            return;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long k : d.cells) {
            minX = Math.min(minX, keyX(k));
            maxX = Math.max(maxX, keyX(k));
            minZ = Math.min(minZ, keyZ(k));
            maxZ = Math.max(maxZ, keyZ(k));
        }
        if (sx < minX || sx > maxX || sz < minZ || sz > maxZ) {
            say(sp, "§c[CP] Tienes que estar DENTRO del area dibujada para rellenarla.");
            return;
        }
        int ox = minX - 1;
        int oz = minZ - 1;
        int w = maxX - minX + 3;
        int h = maxZ - minZ + 3;
        if ((long) w * h > MAX_GRID) {
            say(sp, "§c[CP] El dibujo es demasiado grande para rellenarlo.");
            return;
        }
        boolean[] wall = new boolean[w * h];
        for (long k : d.cells) {
            wall[(keyX(k) - ox) * h + (keyZ(k) - oz)] = true;
        }
        boolean[] seen = new boolean[w * h];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int start = (sx - ox) * h + (sz - oz);
        seen[start] = true;
        queue.add(start);
        boolean leak = false;
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        while (!queue.isEmpty()) {
            int c = queue.poll();
            int cx = c / h;
            int cz = c % h;
            if (cx == 0 || cz == 0 || cx == w - 1 || cz == h - 1) { // llego al borde de fuera: el contorno tiene un hueco
                leak = true;
                break;
            }
            for (int i = 0; i < 4; i++) {
                int idx = (cx + dx[i]) * h + (cz + dz[i]);
                if (!wall[idx] && !seen[idx]) {
                    seen[idx] = true;
                    queue.add(idx);
                }
            }
        }
        if (leak) {
            say(sp, "§c[CP] El area NO esta cerrada: el contorno tiene un hueco. Cierra la linea (los bloques en diagonal si cuentan "
                    + "como unidos) y vuelve a intentarlo. No se relleno.");
            return;
        }
        // el area = lo de dentro + los bloques del contorno que tocan lo de dentro (se descartan lineas sueltas que sobran)
        Set<Long> region = new LinkedHashSet<>();
        for (int cx = 0; cx < w; cx++) {
            for (int cz = 0; cz < h; cz++) {
                int idx = cx * h + cz;
                boolean in = seen[idx];
                if (!in && wall[idx]) {
                    for (int ax = -1; ax <= 1 && !in; ax++) {
                        for (int az = -1; az <= 1 && !in; az++) {
                            int nx = cx + ax;
                            int nz = cz + az;
                            if (nx >= 0 && nz >= 0 && nx < w && nz < h && seen[nx * h + nz]) {
                                in = true;
                            }
                        }
                    }
                }
                if (in) {
                    region.add(key(cx + ox, cz + oz));
                }
            }
        }
        List<int[]> rects = rects(region);
        if (rects.size() > MAX_RECTS) {
            say(sp, "§c[CP] El area es demasiado compleja (" + rects.size() + " partes; maximo " + MAX_RECTS
                    + "). Simplifica el contorno o dibuja varias areas.");
            return;
        }
        int number = KartCpItems.numberOf(sp);
        int group = KartCheckpoints.newGroup();
        List<CpBox> boxes = new ArrayList<>();
        for (int[] r : rects) {
            boxes.add(new CpBox(number, r[0], d.y, r[2], r[1], d.y, r[3], group));
        }
        KartCheckpoints.addAll(level, boxes);
        int total = region.size();
        d.reset();
        say(sp, "§a[CP] Checkpoint " + label(number) + " creado: " + total + " bloques, 1 de alto. Para darle altura haz click derecho "
                + "en su cara de arriba o de abajo.");
        sendDraw(sp, d);
    }
}
