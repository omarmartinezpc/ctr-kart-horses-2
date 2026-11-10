package com.ctrkart;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Cliente -> servidor: acciones de "Dibujar Checkpoint".
 *  LEFT_CLICK: el jugador hizo click izquierdo con la herramienta en la mano (rellenar el area; con shift, cancelar el dibujo).
 *  (El click izquierdo en el aire no le llega al servidor por si solo, por eso lo manda el cliente.)
 */
public record KartCpActionPayload(int kind, int arg) implements CustomPacketPayload {
    public static final int LEFT_CLICK = 0;

    public static final CustomPacketPayload.Type<KartCpActionPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(KartMod.MOD_ID, "cp_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KartCpActionPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, KartCpActionPayload::kind,
            ByteBufCodecs.VAR_INT, KartCpActionPayload::arg,
            KartCpActionPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
