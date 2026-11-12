package com.ctrkart;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
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
 * Item "Nodo de Ruta" (route_node): marca por donde pasan los bots. Funciona como el bloque de Rescate: es un marcador
 * (dato guardado, sin hitbox) que se coloca en la celda de la mira DENTRO de un checkpoint numerado y nace mirando como
 * el jugador; click en una de sus caras laterales lo orienta hacia esa cara; agachado + click lo borra (el borrador de
 * checkpoints tambien). Un checkpoint puede tener varios nodos: se recorren en el orden en que los colocas; entre
 * checkpoints, en el orden de la carrera. Con el item en la mano se ven los nodos y las lineas del recorrido.
 */
public final class KartRouteItems {
    public static final Item ITEM = register("route_node");
    private static final double PICK_DIST = 80.0;

    private KartRouteItems() {}

    private static Item register(String path) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(KartMod.MOD_ID, path));
        return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(1)));
    }

    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> use(player, world, hand));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> use(player, world, hand));
        if (!KartItem.registerCreativeEntry(CreativeModeTabs.TOOLS_AND_UTILITIES, () -> new ItemStack(ITEM), "Nodo de Ruta")) {
            System.err.println("[ctrkart] No pude anadir Nodo de Ruta al creativo. Alternativa: /give @s ctrkart:route_node");
        }
    }

    /** El nodo (celda) que apunta la mira, o null. Lo usa tambien el borrador de checkpoints. */
    static CpBox.Hit pickNode(List<RouteNode> nodes, Vec3 eye, Vec3 look) {
        List<CpBox> boxes = new ArrayList<>();
        for (RouteNode r : nodes) {
            boxes.add(r.box());
        }
        return CpBox.pick(boxes, eye, look, PICK_DIST);
    }

    static RouteNode nodeAt(List<RouteNode> nodes, CpBox cell) {
        for (RouteNode r : nodes) {
            if (r.x() == cell.x1() && r.y() == cell.y1() && r.z() == cell.z1()) {
                return r;
            }
        }
        return null;
    }

    private static InteractionResult use(Player player, Level world, InteractionHand hand) {
        if (player.getItemInHand(hand).getItem() != ITEM) {
            return InteractionResult.PASS;
        }
        if (!(world instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.SUCCESS; // en el cliente solo mueve el brazo
        }
        Vec3 eye = sp.getEyePosition();
        Vec3 look = sp.getLookAngle();
        BlockPos ghost = CpBox.ghost(eye, look);
        KartTrack track = KartCheckpoints.track(level);
        List<RouteNode> nodes = track.nodes();
        CpBox.Hit hit = pickNode(nodes, eye, look);

        if (sp.isShiftKeyDown()) { // agachado: borrar el nodo que apuntas
            RouteNode old = hit == null ? null : nodeAt(nodes, hit.box());
            if (old == null) {
                sp.sendSystemMessage(Component.literal("§e[Ruta] Mira un nodo para borrarlo (o usa el borrador de checkpoints)."));
            } else {
                KartCheckpoints.removeNode(level, old);
                sp.sendSystemMessage(Component.literal("§c[Ruta] Nodo eliminado."));
            }
            return InteractionResult.SUCCESS;
        }
        if (hit != null) { // click en un nodo: se orienta hacia la cara lateral que clicas
            if (hit.axis() == 1) {
                sp.sendSystemMessage(Component.literal("§e[Ruta] Haz click en una cara lateral para orientarlo."));
            } else {
                RouteNode old = nodeAt(nodes, hit.box());
                if (old != null) {
                    int yaw = RescuePoint.yawFor(hit.axis(), hit.side());
                    KartCheckpoints.replaceNode(level, old, new RouteNode(old.x(), old.y(), old.z(), yaw));
                    sp.sendSystemMessage(Component.literal("§b[Ruta] Nodo orientado hacia el §l" + RescuePoint.yawName(yaw) + "§r§b."));
                }
            }
            return InteractionResult.SUCCESS;
        }
        // colocar un nodo nuevo en la celda de la mira, dentro de un checkpoint numerado
        for (RouteNode r : nodes) {
            if (r.x() == ghost.getX() && r.y() == ghost.getY() && r.z() == ghost.getZ()) {
                sp.sendSystemMessage(Component.literal("§e[Ruta] Ahi ya hay un nodo."));
                return InteractionResult.SUCCESS;
            }
        }
        RouteNode node = new RouteNode(ghost.getX(), ghost.getY(), ghost.getZ(), RescuePoint.snap(sp.getYRot()));
        int owner = track.ownerOf(node);
        if (owner < 0) {
            sp.sendSystemMessage(Component.literal("§c[Ruta] El nodo tiene que ir dentro de un checkpoint numerado."));
            return InteractionResult.SUCCESS;
        }
        KartCheckpoints.addNode(level, node);
        long inCp = KartCheckpoints.nodes(level).stream().filter(r -> KartCheckpoints.track(level).ownerOf(r) == owner).count();
        sp.sendSystemMessage(Component.literal("§b[Ruta] Nodo colocado en el checkpoint " + (owner == 0 ? "0 (META)" : owner)
                + " (nodo " + inCp + " de ese checkpoint), mirando al §l" + RescuePoint.yawName(node.yaw())
                + "§r§b. Click en una de sus caras laterales para orientarlo."));
        return InteractionResult.SUCCESS;
    }
}
