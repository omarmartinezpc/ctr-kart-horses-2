package com.ctrkart;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Servidor -> cliente, para checkpoints y rescate. "data" son enteros (las coordenadas decimales van multiplicadas por 1000).
 *  SYNC        : lista de checkpoints de la dimension del jugador, 7 enteros por caja (ver CpBox.writeTo)
 *  UI          : [numero elegido (-1 = libre), esquina marcada (0/1), x, y, z de la esquina]
 *  RACE        : [vueltas, checkpoint actual (indice), total de checkpoints, carrera iniciada (0/1)]
 *  RESCUE_START: [id del kart, x*1000, y*1000, z*1000 del destino, giro*10]
 *  RESCUE_WARP : [x*1000, y*1000, z*1000] (el kart aparece en el cielo sobre el destino)
 *  RESCUE_END  : []
 */
public record KartCpPayload(int kind, List<Integer> data) implements CustomPacketPayload {
    public static final int SYNC = 0;
    public static final int UI = 1;
    public static final int RACE = 2;
    public static final int RESCUE_START = 3;
    public static final int RESCUE_WARP = 4;
    public static final int RESCUE_END = 5;

    public static final CustomPacketPayload.Type<KartCpPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(KartMod.MOD_ID, "cp"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KartCpPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, KartCpPayload::kind,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), KartCpPayload::data,
            KartCpPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
