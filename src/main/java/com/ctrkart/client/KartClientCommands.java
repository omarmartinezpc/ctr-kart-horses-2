package com.ctrkart.client;

import com.ctrkart.KartConfig;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * /ctrkart manejoclasico [true|false]  -> MANEJO CLASICO (derrape recto + Sacred Fire manual)
 * /ctrkart camara [true|false]         -> CAMARA CTR (tercera persona fija detras del kart)
 * Sin argumento alterna; se guardan en el archivo de config. Se usa Brigadier directamente (la clase
 * ClientCommandManager cambio de nombre en las versiones nuevas de Fabric API).
 */
public final class KartClientCommands {
    private KartClientCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("ctrkart")
                        .then(toggle("manejoclasico", () -> KartConfig.classicHandling, v -> KartConfig.classicHandling = v,
                                "[CTR Kart] Manejo clasico ACTIVADO: derrape recto y Sacred Fire manual (click izquierdo).",
                                "[CTR Kart] Manejo clasico DESACTIVADO: derrape y Sacred Fire automaticos."))
                        .then(toggle("camara", () -> KartConfig.ctrCamera, v -> KartConfig.ctrCamera = v,
                                "[CTR Kart] Camara CTR ACTIVADA: camara fija detras del kart al montarlo.",
                                "[CTR Kart] Camara CTR DESACTIVADA."))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> toggle(String name, BooleanSupplier get,
                                                                            Consumer<Boolean> set, String onMsg, String offMsg) {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal(name)
                .executes(ctx -> {
                    apply(ctx.getSource(), !get.getAsBoolean(), set, onMsg, offMsg);
                    return 1;
                })
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Boolean>argument("activar", BoolArgumentType.bool())
                        .executes(ctx -> {
                            apply(ctx.getSource(), BoolArgumentType.getBool(ctx, "activar"), set, onMsg, offMsg);
                            return 1;
                        }));
    }

    private static void apply(FabricClientCommandSource source, boolean on, Consumer<Boolean> set, String onMsg, String offMsg) {
        set.accept(on);
        KartConfig.save();
        source.sendFeedback(Component.literal(on ? onMsg : offMsg));
    }
}
