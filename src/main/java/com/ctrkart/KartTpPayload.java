package com.ctrkart;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Servidor -> cliente, para los bloques de teletransporte.
 *  kind 0 = TELEPORT: mueve el kart (entityId) a x, y, z (conservando su estado: reservas, turbo...)
 *  kind 1 = el jugador acaba de poner el numero {channel} a un TP de Entrada (mensaje en la barra de accion)
 *  kind 2 = lo mismo para un TP de Salida
 */
public record KartTpPayload(int kind, int entityId, int channel, double x, double y, double z) implements CustomPacketPayload {
    public static final int TELEPORT = 0;
    public static final int SET_ENTRY = 1;
    public static final int SET_EXIT = 2;

    public static final CustomPacketPayload.Type<KartTpPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(KartMod.MOD_ID, "tp"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KartTpPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, KartTpPayload::kind,
            ByteBufCodecs.VAR_INT, KartTpPayload::entityId,
            ByteBufCodecs.VAR_INT, KartTpPayload::channel,
            ByteBufCodecs.DOUBLE, KartTpPayload::x,
            ByteBufCodecs.DOUBLE, KartTpPayload::y,
            ByteBufCodecs.DOUBLE, KartTpPayload::z,
            KartTpPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
