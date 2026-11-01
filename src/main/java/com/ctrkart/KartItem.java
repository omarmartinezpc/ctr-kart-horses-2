package com.ctrkart;

import java.util.Locale;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Objeto "Kart" de la pestana de huevos de aparicion del inventario creativo: al hacer click derecho sobre un
 * bloque aparece un caballo ensillado, domado y con la etiqueta "kart". Es un palo con nombre y modelo propios
 * (items/kart_spawn.json); asi no hace falta registrar un objeto nuevo.
 */
public final class KartItem {
    public static final Identifier MODEL = Identifier.fromNamespaceAndPath(KartMod.MOD_ID, "kart_spawn");

    private KartItem() {}

    public static ItemStack stack() {
        ItemStack st = new ItemStack(Items.STICK);
        st.set(DataComponents.ITEM_MODEL, MODEL);
        st.set(DataComponents.CUSTOM_NAME, Component.literal("Kart"));
        return st;
    }

    private static boolean isKartItem(ItemStack st) {
        return st.is(Items.STICK) && MODEL.equals(st.get(DataComponents.ITEM_MODEL));
    }

    public static void register() {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS).register(entries -> entries.accept(stack()));
        UseBlockCallback.EVENT.register(KartItem::onUseBlock);
    }

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!isKartItem(player.getItemInHand(hand))) {
            return InteractionResult.PASS;
        }
        if (level instanceof ServerLevel serverLevel) {
            spawn(serverLevel, hit.getBlockPos().relative(hit.getDirection()), player.getYRot());
        }
        return InteractionResult.SUCCESS;
    }

    /** Aparece con /summon (asi no depende de detalles internos del juego). La silla se pone con los dos formatos conocidos. */
    private static void spawn(ServerLevel level, BlockPos pos, float yaw) {
        MinecraftServer server = level.getServer();
        Vec3 at = Vec3.atBottomCenterOf(pos);
        CommandSourceStack src = server.createCommandSourceStack().withLevel(level).withPosition(at).withSuppressedOutput();
        String saddle = "{id:\"minecraft:saddle\",count:1}";
        String cmd = String.format(Locale.ROOT,
                "summon minecraft:horse %.2f %.2f %.2f {Tags:[\"kart\"],Tame:1b,PersistenceRequired:1b,"
                        + "Rotation:[%.1ff,0.0f],SaddleItem:%s,equipment:{saddle:%s}}",
                at.x, at.y, at.z, yaw, saddle, saddle);
        server.getCommands().performPrefixedCommand(src, cmd);
    }
}
