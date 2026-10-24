package com.ctrkart;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Servidor -> clientes: "este caballo es un kart de la clase X y le quedan N ticks de Super Motor". */
public record KartSyncPayload(int entityId, int kartClass, int superTicks) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<KartSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(KartMod.MOD_ID, "sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KartSyncPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, KartSyncPayload::entityId,
            ByteBufCodecs.VAR_INT, KartSyncPayload::kartClass,
            ByteBufCodecs.VAR_INT, KartSyncPayload::superTicks,
            KartSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
