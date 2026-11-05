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
 * Bloques del mod: los SACRED PAD (turbo pads). Los tres tienen la misma textura (lineas verdes y negras que suben),
 * luz 15, se pueden orientar y dan las mismas reservas. Lo unico que cambia es la velocidad del Sacred Fire que dan:
 *   ctrkart:sacred_pad    -> Blue Fire nivel 1 (el azul de siempre)
 *   ctrkart:sacred_pad_2  -> Blue Fire nivel 2 (12% mas veloz que el nivel 1)
 *   ctrkart:sacred_pad_3  -> Blue Fire nivel 3 (22% mas veloz que el nivel 1)
 * Al pisarlos con el kart se activa el Sacred Fire (ver KartPhysics.padCheck).
 * Para obtenerlos: pestana "Bloques de construccion" del creativo, o  /give @s ctrkart:sacred_pad_2
 */
public final class KartBlocks {
    public static final Block SACRED_PAD = registerPad("sacred_pad");
    public static final Block SACRED_PAD_2 = registerPad("sacred_pad_2");
    public static final Block SACRED_PAD_3 = registerPad("sacred_pad_3");

    public static final Item SACRED_PAD_ITEM = registerPadItem(SACRED_PAD, "sacred_pad");
    public static final Item SACRED_PAD_2_ITEM = registerPadItem(SACRED_PAD_2, "sacred_pad_2");
    public static final Item SACRED_PAD_3_ITEM = registerPadItem(SACRED_PAD_3, "sacred_pad_3");

    private KartBlocks() {}

    private static Block registerPad(String path) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(KartMod.MOD_ID, path));
        return Registry.register(BuiltInRegistries.BLOCK, key,
                new SacredPadBlock(BlockBehaviour.Properties.of()
                        .setId(key)
                        .mapColor(MapColor.COLOR_GREEN)
                        .sound(SoundType.METAL)
                        .strength(1.5f, 6.0f)
                        .lightLevel(state -> 15)));
    }

    private static Item registerPadItem(Block block, String path) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(KartMod.MOD_ID, path));
        return Registry.register(BuiltInRegistries.ITEM, key,
                new BlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix()));
    }

    /** Nivel de Sacred Fire que da el bloque (4 = Blue Fire 1, 5 = Blue Fire 2, 6 = Blue Fire 3), o 0 si no es un pad. */
    public static int padTier(Block block) {
        if (block == SACRED_PAD) {
            return KartPhysics.TIER_BLUE1;
        }
        if (block == SACRED_PAD_2) {
            return KartPhysics.TIER_BLUE2;
        }
        if (block == SACRED_PAD_3) {
            return KartPhysics.TIER_BLUE3;
        }
        return 0;
    }

    public static void register() {
        // Tocar las constantes de arriba es lo que registra los bloques y los objetos (se ejecuta al cargar la clase).
        addToCreative(SACRED_PAD_ITEM, "Sacred Pad 1", "sacred_pad");
        addToCreative(SACRED_PAD_2_ITEM, "Sacred Pad 2", "sacred_pad_2");
        addToCreative(SACRED_PAD_3_ITEM, "Sacred Pad 3", "sacred_pad_3");
    }

    private static void addToCreative(Item item, String label, String id) {
        if (!KartItem.registerCreativeEntry(CreativeModeTabs.BUILDING_BLOCKS, () -> new ItemStack(item), label)) {
            System.err.println("[ctrkart] No pude anadir " + label + " al creativo. Alternativa: /give @s ctrkart:" + id);
        }
    }
}
