package com.ctrkart;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Item de CREACION (checkpoint_wand) e item de BORRADO (checkpoint_eraser) de checkpoints.
 *
 * CREACION (click derecho):
 *  - Marca una esquina en el bloque de la mira (a 4 bloques); con la 2a esquina se crea el checkpoint.
 *  - Si miras un checkpoint ya creado (se ve amarillo) y no tienes una esquina marcada, el click lo ESTIRA: la cara hacia
 *    la que miras se mueve hasta donde esta el bloque de la mira.
 *  - Agachado: con una esquina marcada la cancela; si no, cambia el numero (+1; de 100 pasa a "libre", y de libre a 0).
 *  - El numero tambien se puede poner con  /kartcp <0-100>  o  /kartcp libre.
 *  - Agachado + click sobre un checkpoint ya creado: le pone el numero elegido (con aviso).
 * BORRADO (click derecho): elimina el checkpoint (se ve rojo) o el bloque de Rescate que miras.
 * RESCATE (item rescue_point, click derecho): coloca un bloque de Rescate en el bloque de la mira, dentro de un checkpoint.
 *  Click en una de sus caras laterales = lo orienta hacia esa cara: asi mira el kart al ser rescatado.
 */
public final class KartCpItems {
    public static final Item WAND = register("checkpoint_wand");
    public static final Item ERASER = register("checkpoint_eraser");
    public static final Item RESCUE = register("rescue_point");

    private static final double PICK_DIST = 80.0; // hasta donde se "selecciona" un checkpoint con la mira
    private static final Map<UUID, Integer> NUMBER = new HashMap<>();
    private static final Map<UUID, BlockPos> CORNER = new HashMap<>();

    private KartCpItems() {}

    private static Item register(String path) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(KartMod.MOD_ID, path));
        return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(1)));
    }

    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> click(player, world, hand));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> click(player, world, hand));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("kartcp")
                        .then(Commands.argument("numero", IntegerArgumentType.integer(0, CpBox.MAX_NUMBER)).executes(ctx -> {
                            setNumber(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "numero"));
                            return 1;
                        }))
                        .then(Commands.literal("libre").executes(ctx -> {
                            setNumber(ctx.getSource().getPlayerOrException(), CpBox.FREE);
                            return 1;
                        }))));
        add(WAND, "Checkpoint (creacion)", "checkpoint_wand");
        add(ERASER, "Checkpoint (borrado)", "checkpoint_eraser");
        add(RESCUE, "Rescate", "rescue_point");
    }

    private static void add(Item item, String label, String id) {
        if (!KartItem.registerCreativeEntry(CreativeModeTabs.TOOLS_AND_UTILITIES, () -> new ItemStack(item), label)) {
            System.err.println("[ctrkart] No pude anadir " + label + " al creativo. Alternativa: /give @s ctrkart:" + id);
        }
    }

    public static int numberOf(Player p) {
        return NUMBER.getOrDefault(p.getUUID(), 0);
    }

    private static String label(int n) {
        return n == CpBox.FREE ? "ZONA SIN RESTRICCIONES" : n == 0 ? "0 (META)" : String.valueOf(n);
    }

    private static void setNumber(ServerPlayer p, int n) {
        NUMBER.put(p.getUUID(), n);
        p.sendSystemMessage(Component.literal("§b[CP] §fNumero elegido: §l" + label(n)));
        sendUi(p);
    }

    /** Estado del item (numero y esquina) para el cliente: se dibuja la vista previa y el mensaje de la barra de accion. */
    private static void sendUi(ServerPlayer p) {
        BlockPos c = CORNER.get(p.getUUID());
        List<Integer> d = new ArrayList<>();
        d.add(numberOf(p));
        d.add(c == null ? 0 : 1);
        d.add(c == null ? 0 : c.getX());
        d.add(c == null ? 0 : c.getY());
        d.add(c == null ? 0 : c.getZ());
        ServerPlayNetworking.send(p, new KartCpPayload(KartCpPayload.UI, d));
    }

    /** El bloque de Rescate (celda) que apunta la mira, o null. */
    private static CpBox.Hit pickPoint(List<RescuePoint> pts, Vec3 eye, Vec3 look) {
        List<CpBox> boxes = new ArrayList<>();
        for (RescuePoint r : pts) {
            boxes.add(r.box());
        }
        return CpBox.pick(boxes, eye, look, PICK_DIST);
    }

    private static RescuePoint pointAt(List<RescuePoint> pts, CpBox cell) {
        for (RescuePoint r : pts) {
            if (r.x() == cell.x1() && r.y() == cell.y1() && r.z() == cell.z1()) {
                return r;
            }
        }
        return null;
    }

    private static InteractionResult click(Player player, Level world, InteractionHand hand) {
        Item item = player.getItemInHand(hand).getItem();
        if (item != WAND && item != ERASER && item != RESCUE) {
            return InteractionResult.PASS;
        }
        if (!(world instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.SUCCESS; // en el cliente: solo mueve el brazo; lo demas lo hace el servidor
        }
        Vec3 eye = sp.getEyePosition();
        Vec3 look = sp.getLookAngle();
        BlockPos ghost = CpBox.ghost(eye, look);
        List<CpBox> boxes = KartCheckpoints.list(level);
        List<RescuePoint> pts = KartCheckpoints.points(level);
        CpBox.Hit sel = CpBox.pick(boxes, eye, look, PICK_DIST);
        CpBox.Hit rp = pickPoint(pts, eye, look);
        UUID id = sp.getUUID();

        // ---------------------------------------------------------------- Rescate
        if (item == RESCUE) {
            if (rp != null) { // click en un bloque de Rescate: se orienta hacia la cara que clicas
                if (rp.axis() == 1) {
                    sp.sendSystemMessage(Component.literal("§e[CP] Haz click en una cara lateral para orientarlo."));
                } else {
                    RescuePoint old = pointAt(pts, rp.box());
                    if (old != null) {
                        int yaw = RescuePoint.yawFor(rp.axis(), rp.side());
                        KartCheckpoints.replacePoint(level, old, new RescuePoint(old.x(), old.y(), old.z(), yaw));
                        sp.sendSystemMessage(Component.literal("§d[CP] Rescate orientado hacia el §l" + RescuePoint.yawName(yaw) + "§r§d."));
                    }
                }
                return InteractionResult.SUCCESS;
            }
            CpBox owner = null;
            Vec3 cell = new Vec3(ghost.getX() + 0.5, ghost.getY() + 0.5, ghost.getZ() + 0.5);
            for (CpBox b : boxes) {
                if (!b.isFree() && b.contains(cell)) {
                    owner = b;
                }
            }
            if (owner == null) {
                sp.sendSystemMessage(Component.literal("§c[CP] El bloque de Rescate tiene que ir dentro de un checkpoint."));
            } else {
                int yaw = RescuePoint.snap(sp.getYRot());
                KartCheckpoints.addPoint(level, new RescuePoint(ghost.getX(), ghost.getY(), ghost.getZ(), yaw));
                sp.sendSystemMessage(Component.literal("§d[CP] Rescate colocado en el checkpoint " + label(owner.number())
                        + ", mirando al §l" + RescuePoint.yawName(yaw) + "§r§d. Click en una de sus caras laterales para orientarlo."));
            }
            return InteractionResult.SUCCESS;
        }

        // ---------------------------------------------------------------- Borrado
        if (item == ERASER) {
            boolean point = rp != null && (sel == null || rp.t() <= sel.t());
            if (point) {
                RescuePoint old = pointAt(pts, rp.box());
                if (old != null) {
                    KartCheckpoints.removePoint(level, old);
                    sp.sendSystemMessage(Component.literal("§c[CP] Bloque de Rescate eliminado."));
                }
            } else if (sel == null) {
                sp.sendSystemMessage(Component.literal("§c[CP] Mira un checkpoint (o un bloque de Rescate) para borrarlo."));
            } else {
                KartCheckpoints.remove(level, sel.box());
                sp.sendSystemMessage(Component.literal("§c[CP] Checkpoint " + label(sel.box().number()) + " eliminado."));
            }
            return InteractionResult.SUCCESS;
        }

        // ---------------------------------------------------------------- Creacion / edicion
        BlockPos corner = CORNER.get(id);
        if (sp.isShiftKeyDown()) {
            if (corner != null) {
                CORNER.remove(id);
                sp.sendSystemMessage(Component.literal("§e[CP] Esquina cancelada."));
            } else if (sel != null) { // shift + click en un checkpoint: recibe el numero elegido
                int n = numberOf(sp);
                if (sel.box().number() == n) {
                    sp.sendSystemMessage(Component.literal("§e[CP] Ese checkpoint ya tiene el numero " + label(n) + "."));
                } else {
                    KartCheckpoints.replace(level, sel.box(), sel.box().withNumber(n));
                    sp.sendSystemMessage(Component.literal("§a[CP] Numero cambiado: " + label(sel.box().number()) + " §f-> §a§l" + label(n)));
                }
                return InteractionResult.SUCCESS;
            } else {
                int n = numberOf(sp);
                setNumber(sp, n == CpBox.MAX_NUMBER ? CpBox.FREE : n == CpBox.FREE ? 0 : n + 1);
                return InteractionResult.SUCCESS;
            }
        } else if (corner == null && sel != null) {
            int axis = sel.axis();
            int target = axis == 0 ? ghost.getX() : axis == 1 ? ghost.getY() : ghost.getZ();
            CpBox stretched = sel.box().withBound(axis, sel.side(), target);
            if (stretched == null) {
                sp.sendSystemMessage(Component.literal("§c[CP] No se puede estirar hasta ahi."));
            } else {
                KartCheckpoints.replace(level, sel.box(), stretched);
                sp.sendSystemMessage(Component.literal("§e[CP] Checkpoint " + label(stretched.number()) + " estirado ("
                        + stretched.size(0) + "x" + stretched.size(1) + "x" + stretched.size(2) + ")."));
            }
        } else if (corner == null) {
            CORNER.put(id, ghost);
            sp.sendSystemMessage(Component.literal("§a[CP] Esquina 1 marcada. Marca la esquina 2 para crear el checkpoint "
                    + label(numberOf(sp)) + "."));
        } else {
            CpBox box = CpBox.of(numberOf(sp), corner, ghost);
            CORNER.remove(id);
            KartCheckpoints.add(level, box);
            sp.sendSystemMessage(Component.literal("§a[CP] Checkpoint " + label(box.number()) + " creado ("
                    + box.size(0) + "x" + box.size(1) + "x" + box.size(2) + ")."));
        }
        sendUi(sp);
        return InteractionResult.SUCCESS;
    }
}
