package com.ctrkart;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
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
import net.minecraft.world.entity.animal.equine.AbstractHorse; // si no existe: ...animal.horse.AbstractHorse
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;

/**
 * Item "Eliminar Karts" (kart_remover): click derecho = elimina TODOS los karts (caballos con la etiqueta "kart") que esten
 * en chunks cargados de la dimension en la que estas. Los jugadores que iban montados se bajan.
 */
public final class KartRemover {
    public static final Item ITEM = register("kart_remover");

    private KartRemover() {}

    private static Item register(String path) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(KartMod.MOD_ID, path));
        return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(1)));
    }

    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> use(player, world, hand));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> use(player, world, hand));
        if (!KartItem.registerCreativeEntry(CreativeModeTabs.TOOLS_AND_UTILITIES, () -> new ItemStack(ITEM), "Eliminar Karts")) {
            System.err.println("[ctrkart] No pude anadir Eliminar Karts al creativo. Alternativa: /give @s ctrkart:kart_remover");
        }
    }

    private static InteractionResult use(Player player, Level world, InteractionHand hand) {
        if (player.getItemInHand(hand).getItem() != ITEM) {
            return InteractionResult.PASS;
        }
        if (!(world instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.SUCCESS; // en el cliente solo mueve el brazo
        }
        int count = 0;
        for (AbstractHorse horse : level.getEntities(EntityTypeTest.forClass(AbstractHorse.class), e -> true)) {
            if (KartClass.idOf(horse) < 0) {
                continue; // un caballo normal, sin la etiqueta "kart"
            }
            for (net.minecraft.world.entity.Entity passenger : horse.getPassengers()) {
                if (!(passenger instanceof Player)) {
                    passenger.discard(); // el personaje de un bot
                }
            }
            horse.ejectPassengers();
            KartMod.clearSuper(horse.getUUID());
            horse.discard();
            count++;
        }
        sp.sendSystemMessage(Component.literal(count == 0
                ? "§e[KART] No hay karts en los chunks cargados."
                : "§c[KART] " + count + (count == 1 ? " kart eliminado." : " karts eliminados.")));
        return InteractionResult.SUCCESS;
    }
}
