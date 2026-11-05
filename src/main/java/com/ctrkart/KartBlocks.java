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
 * Bloques del mod: los SACRED PAD (turbo pads). Todos tienen luz 15, se pueden orientar y dan las mismas reservas.
 * Lo unico que cambia es la velocidad del Sacred Fire que dan. Los 3 primeros comparten la textura verde y negra;
 * los Super tienen su propia textura azul con degradado a morado azulado:
 *   ctrkart:sacred_pad          -> Blue Fire nivel 1 (el azul de siempre)
 *   ctrkart:sacred_pad_2        -> Blue Fire nivel 2 (12% mas veloz que el nivel 1)
 *   ctrkart:sacred_pad_3        -> Blue Fire nivel 3 (22% mas veloz que el nivel 1)
 *   ctrkart:super_sacred_pad_1  -> Super Blue Fire nivel 1 (13% mas veloz que el Blue Fire nivel 3)
 *   ctrkart:super_sacred_pad_2  -> Super Blue Fire nivel 2 (25% mas veloz que el Blue Fire nivel 3)
 * Al pisarlos con el kart se activa el Sacred Fire (ver KartPhysics.padCheck).
 * Para obtenerlos: pestana "Bloques de construccion" del creativo, o  /give @s ctrkart:sacred_pad_2
 */
public final class KartBlocks {
    public static final Block SACRED_PAD = registerPad("sacred_pad", MapColor.COLOR_GREEN);
    public static final Block SACRED_PAD_2 = registerPad("sacred_pad_2", MapColor.COLOR_GREEN);
    public static final Block SACRED_PAD_3 = registerPad("sacred_pad_3", MapColor.COLOR_GREEN);
    public static final Block SUPER_PAD_1 = registerPad("super_sacred_pad_1", MapColor.COLOR_BLUE);
    public static final Block SUPER_PAD_2 = registerPad("super_sacred_pad_2", MapColor.COLOR_BLUE);

    public static final Item SACRED_PAD_ITEM = registerPadItem(SACRED_PAD, "sacred_pad");
    public static final Item SACRED_PAD_2_ITEM = registerPadItem(SACRED_PAD_2, "sacred_pad_2");
    public static final Item SACRED_PAD_3_ITEM = registerPadItem(SACRED_PAD_3, "sacred_pad_3");
    public static final Item SUPER_PAD_1_ITEM = registerPadItem(SUPER_PAD_1, "super_sacred_pad_1");
    public static final Item SUPER_PAD_2_ITEM = registerPadItem(SUPER_PAD_2, "super_sacred_pad_2");

    private KartBlocks() {}

    private static Block registerPad(String path, MapColor color) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(KartMod.MOD_ID, path));
        return Registry.register(BuiltInRegistries.BLOCK, key,
                new SacredPadBlock(BlockBehaviour.Properties.of()
                        .setId(key)
                        .mapColor(color)
                        .sound(SoundType.METAL)
                        .strength(1.5f, 6.0f)
                        .lightLevel(state -> 15)));
    }

    private static Item registerPadItem(Block block, String path) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(KartMod.MOD_ID, path));
        return Registry.register(BuiltInRegistries.ITEM, key,
                new BlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix()));
    }

    /** Nivel de Sacred Fire que da el bloque (4-6 = Blue Fire 1-3, 7-8 = Super Blue Fire 1-2), o 0 si no es un pad. */
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
        if (block == SUPER_PAD_1) {
            return KartPhysics.TIER_SUPER1;
        }
        if (block == SUPER_PAD_2) {
            return KartPhysics.TIER_SUPER2;
        }
        return 0;
    }

    public static void register() {
        // Tocar las constantes de arriba es lo que registra los bloques y los objetos (se ejecuta al cargar la clase).
        addToCreative(SACRED_PAD_ITEM, "Sacred Pad 1", "sacred_pad");
        addToCreative(SACRED_PAD_2_ITEM, "Sacred Pad 2", "sacred_pad_2");
        addToCreative(SACRED_PAD_3_ITEM, "Sacred Pad 3", "sacred_pad_3");
        addToCreative(SUPER_PAD_1_ITEM, "Super Sacred Pad 1", "super_sacred_pad_1");
        addToCreative(SUPER_PAD_2_ITEM, "Super Sacred Pad 2", "super_sacred_pad_2");
    }

    private static void addToCreative(Item item, String label, String id) {
        if (!KartItem.registerCreativeEntry(CreativeModeTabs.BUILDING_BLOCKS, () -> new ItemStack(item), label)) {
            System.err.println("[ctrkart] No pude anadir " + label + " al creativo. Alternativa: /give @s ctrkart:" + id);
        }
    }
}
