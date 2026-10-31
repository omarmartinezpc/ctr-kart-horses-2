package com.ctrkart.client;

import com.ctrkart.KartConfig;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * /ctrkart manejoclasico [true|false]  -> activa o desactiva el MANEJO CLASICO (se guarda en el archivo de config).
 * Se usa Brigadier directamente (no la clase ClientCommandManager, que cambio de nombre en las versiones nuevas de Fabric API).
 */
public final class KartClientCommands {
    private KartClientCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("ctrkart")
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("manejoclasico")
                                .executes(ctx -> {
                                    set(ctx.getSource(), !KartConfig.classicHandling);
                                    return 1;
                                })
                                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Boolean>argument(
                                                "activar", BoolArgumentType.bool())
                                        .executes(ctx -> {
                                            set(ctx.getSource(), BoolArgumentType.getBool(ctx, "activar"));
                                            return 1;
                                        })))));
    }

    private static void set(FabricClientCommandSource source, boolean on) {
        KartConfig.classicHandling = on;
        KartConfig.save();
        source.sendFeedback(Component.literal(on
                ? "[CTR Kart] Manejo clasico ACTIVADO: derrape recto y Sacred Fire manual (click izquierdo)."
                : "[CTR Kart] Manejo clasico DESACTIVADO: derrape y Sacred Fire automaticos."));
    }
}
