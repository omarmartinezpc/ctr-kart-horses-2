package com.ctrkart;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * Bloques del mod.
 *
 * SACRED PAD (ctrkart:sacred_pad): bloque con luz 15 y lineas verdes y negras que suben (textura animada).
 * Al pisarlo con el kart da Sacred Fire azul, reservas y la animacion del Sacred Fire (ver KartPhysics.padCheck).
 * Para obtenerlo: pestana "Bloques de construccion" del creativo, o  /give @s ctrkart:sacred_pad
 */
public final class KartBlocks {
    private static final Identifier PAD_ID = Identifier.fromNamespaceAndPath(KartMod.MOD_ID, "sacred_pad");
    private static final ResourceKey<Block> PAD_BLOCK_KEY = ResourceKey.create(Registries.BLOCK, PAD_ID);
    private static final ResourceKey<Item> PAD_ITEM_KEY = ResourceKey.create(Registries.ITEM, PAD_ID);

    public static final Block SACRED_PAD = Registry.register(BuiltInRegistries.BLOCK, PAD_BLOCK_KEY,
            new Block(BlockBehaviour.Properties.of()
                    .setId(PAD_BLOCK_KEY)
                    .mapColor(MapColor.COLOR_GREEN)
                    .sound(SoundType.METAL)
                    .strength(1.5f, 6.0f)
                    .lightLevel(state -> 15)));

    public static final Item SACRED_PAD_ITEM = Registry.register(BuiltInRegistries.ITEM, PAD_ITEM_KEY,
            new BlockItem(SACRED_PAD, new Item.Properties().setId(PAD_ITEM_KEY).useBlockDescriptionPrefix()));

    private KartBlocks() {}

    public static void register() {
        // Tocar las constantes de arriba es lo que registra el bloque y el objeto (se ejecuta al cargar la clase).
        if (!KartItem.registerCreativeEntry(CreativeModeTabs.BUILDING_BLOCKS, () -> new ItemStack(SACRED_PAD_ITEM), "Sacred Pad")) {
            System.err.println("[ctrkart] No pude anadir el Sacred Pad al creativo. Alternativa: /give @s ctrkart:sacred_pad");
        }
    }
}
