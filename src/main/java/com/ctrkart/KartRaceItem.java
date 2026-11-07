package com.ctrkart;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
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

/**
 * RUEDA DE CARRERA. Click derecho con ella en la mano:
 *  - Rueda quieta (race_wheel): ACTIVA la carrera. Se convierte en la rueda que gira (race_wheel_on), te lleva al
 *    checkpoint 0 y desde ese momento cuentan los checkpoints, las vueltas y los rescates.
 *  - Rueda girando (race_wheel_on): DESACTIVA la carrera. Vuelve a ser la rueda quieta y los checkpoints dejan de funcionar.
 * Sin la rueda activa no hay carrera: puedes conducir libremente por las zonas con checkpoints. Si pierdes la rueda
 * girando (la tiras, etc.), la carrera se desactiva sola.
 */
public final class KartRaceItem {
    public static final Item WHEEL = register("race_wheel");
    public static final Item WHEEL_ON = register("race_wheel_on");

    private KartRaceItem() {}

    private static Item register(String path) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(KartMod.MOD_ID, path));
        return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(1)));
    }

    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> click(player, world, hand));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> click(player, world, hand));
        if (!KartItem.registerCreativeEntry(CreativeModeTabs.TOOLS_AND_UTILITIES, () -> new ItemStack(WHEEL), "Rueda de Carrera")) {
            System.err.println("[ctrkart] No pude anadir la Rueda de Carrera al creativo. Alternativa: /give @s ctrkart:race_wheel");
        }
    }

    private static InteractionResult click(Player player, Level world, InteractionHand hand) {
        Item item = player.getItemInHand(hand).getItem();
        if (item != WHEEL && item != WHEEL_ON) {
            return InteractionResult.PASS;
        }
        if (!(world instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.SUCCESS;
        }
        if (item == WHEEL) {
            if (KartRace.activate(level, sp)) {
                player.setItemInHand(hand, new ItemStack(WHEEL_ON));
            }
        } else {
            KartRace.deactivate(sp, true);
            player.setItemInHand(hand, new ItemStack(WHEEL));
        }
        return InteractionResult.SUCCESS;
    }
}
