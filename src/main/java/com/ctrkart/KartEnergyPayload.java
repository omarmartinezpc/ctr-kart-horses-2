package com.ctrkart;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Servidor -> clientes: "este kart recibe N de energia" (al alimentarlo). */
public record KartEnergyPayload(int entityId, int amount) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<KartEnergyPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(KartMod.MOD_ID, "energy"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KartEnergyPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, KartEnergyPayload::entityId,
            ByteBufCodecs.VAR_INT, KartEnergyPayload::amount,
            KartEnergyPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
