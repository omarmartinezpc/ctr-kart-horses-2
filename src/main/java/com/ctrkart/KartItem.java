package com.ctrkart;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Locale;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
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
        if (!registerCreativeEntry(CreativeModeTabs.SPAWN_EGGS, KartItem::stack, "Kart")) {
            System.err.println("[ctrkart] No pude anadir el objeto Kart a la pestana de creativo. Alternativa: "
                    + "/give @s stick[item_model=\"ctrkart:kart_spawn\",custom_name='\"Kart\"']");
        }
        UseBlockCallback.EVENT.register(KartItem::onUseBlock);
    }

    /**
     * Anade el objeto a la pestana de huevos de aparicion. Fabric API cambio el nombre de este evento
     * (ItemGroupEvents -> CreativeModeTabEvents), asi que se busca por reflexion con los dos nombres.
     */
    static boolean registerCreativeEntry(Object tabKey, java.util.function.Supplier<ItemStack> stackSupplier, String label) {
        String[] candidates = {
            "net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents",
            "net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents"
        };
        for (String className : candidates) {
            try {
                Class<?> c = Class.forName(className);
                Method factory = null;
                for (Method m : c.getMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                            && m.getName().startsWith("modify") && m.getName().endsWith("Event")) {
                        factory = m;
                        break;
                    }
                }
                Class<?> listener = null;
                for (Class<?> inner : c.getDeclaredClasses()) {
                    if (inner.isInterface() && inner.getSimpleName().startsWith("Modify")) {
                        listener = inner;
                        break;
                    }
                }
                if (factory == null || listener == null) {
                    continue;
                }
                Object event = factory.invoke(null, tabKey);
                Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] {listener}, (self, m, args) -> {
                    switch (m.getName()) {
                        case "toString":
                            return "ctrkart-creative-entry-" + label;
                        case "hashCode":
                            return System.identityHashCode(self);
                        case "equals":
                            return args != null && args.length == 1 && self == args[0];
                        default:
                            if (args != null && args.length == 1 && args[0] != null) {
                                addToTab(args[0], stackSupplier.get());
                            }
                            return null;
                    }
                });
                Class.forName("net.fabricmc.fabric.api.event.Event").getMethod("register", Object.class).invoke(event, proxy);
                System.out.println("[ctrkart] " + label + " anadido al creativo (" + className + ")");
                return true;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                System.err.println("[ctrkart] Creativo: " + className + " no sirvio: " + ex);
            }
        }
        return false;
    }

    /** "output" es el objeto de Fabric/Minecraft que recibe los objetos de la pestana; tiene un metodo accept(ItemStack). */
    private static void addToTab(Object output, ItemStack stack) {
        try {
            for (Method m : output.getClass().getMethods()) {
                if (m.getName().equals("accept") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == ItemStack.class) {
                    m.setAccessible(true);
                    m.invoke(output, stack);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            System.err.println("[ctrkart] Creativo: no pude anadir el objeto: " + ex);
        }
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
