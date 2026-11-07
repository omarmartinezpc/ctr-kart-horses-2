package com.ctrkart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse; // si no existe: ...animal.horse.AbstractHorse
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Logica de los bloques de teletransporte (servidor).
 *  - Cada tick mira por donde pasa el kart de cada jugador (incluido el camino entre el tick anterior y este, para que
 *    no se "salte" el bloque a mucha velocidad). Si atraviesa un TP de Entrada con numero N, lo lleva al TP de Salida
 *    con el mismo numero N mas cercano (en la misma dimension).
 *  - El estado del kart (reservas, nivel de turbo, velocidad, direccion) vive en el cliente y no se toca: el kart no se
 *    baja ni se reinicia, solo cambia de sitio.
 *  - Los TP de Salida se apuntan en un registro que se guarda en el mundo (ctrkart_tp.txt), asi se encuentran aunque la
 *    zona de destino no este cargada. Un TP de Salida roto se borra del registro cuando se intenta usar.
 *  - Numero: click derecho con la mano vacia sobre un TP = +1 (agachado = -1). El ultimo numero que pones es el que
 *    llevan los TP que colocas despues.
 */
public final class KartTeleport {
    private static final int COOLDOWN_TICKS = 20;   // tras teletransportarte, 1 s sin poder activar otro TP
    private static final double STEP = 0.4;         // separacion de las comprobaciones a lo largo del camino

    private static final Map<UUID, Vec3> LAST_POS = new HashMap<>();
    private static final Map<UUID, Integer> COOLDOWN = new HashMap<>();
    private static final Map<UUID, Integer> LAST_CHANNEL = new HashMap<>();
    /** "dimension \t posicion empaquetada" -> numero. Solo TP de Salida. */
    private static final Map<String, Integer> EXITS = new LinkedHashMap<>();
    private static Path file;

    private KartTeleport() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(KartTeleport::load);
        UseBlockCallback.EVENT.register(KartTeleport::onUseBlock);
    }

    // ---------------------------------------------------------------- numero

    public static int lastChannel(Player player) {
        if (player == null) {
            return 1;
        }
        return LAST_CHANNEL.getOrDefault(player.getUUID(), 1);
    }

    /** Click derecho con la mano vacia sobre un TP: cambia su numero. */
    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND || player.isSpectator() || !player.getMainHandItem().isEmpty()) {
            return InteractionResult.PASS;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof TpBlock tp)) {
            return InteractionResult.PASS;
        }
        if (world.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        int cur = state.getValue(TpBlock.CHANNEL);
        int max = TpBlock.MAX_CHANNEL;
        int next = player.isShiftKeyDown() ? (cur - 2 + max) % max + 1 : cur % max + 1;
        world.setBlock(pos, state.setValue(TpBlock.CHANNEL, next), 3);
        LAST_CHANNEL.put(player.getUUID(), next);
        if (player instanceof ServerPlayer sp) {
            ServerPlayNetworking.send(sp, new KartTpPayload(
                    tp.isEntry() ? KartTpPayload.SET_ENTRY : KartTpPayload.SET_EXIT, 0, next, 0, 0, 0));
        }
        return InteractionResult.SUCCESS;
    }

    // ---------------------------------------------------------------- registro de salidas

    public static void registerExit(ServerLevel level, BlockPos pos, int channel) {
        EXITS.put(key(level, pos), channel);
        save();
    }

    private static String key(ServerLevel level, BlockPos pos) {
        return level.dimension() + "\t" + pos.asLong();
    }

    private static void load(MinecraftServer server) {
        EXITS.clear();
        LAST_POS.clear();
        COOLDOWN.clear();
        try {
            file = server.getWorldPath(LevelResource.ROOT).resolve("ctrkart_tp.txt");
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    String[] p = line.split("\t");
                    if (p.length == 3) {
                        EXITS.put(p[0] + "\t" + p[1], Integer.parseInt(p[2].trim()));
                    }
                }
            }
            System.out.println("[ctrkart] TP: " + EXITS.size() + " TP de Salida en el registro del mundo.");
        } catch (IOException | RuntimeException ex) {
            System.err.println("[ctrkart] TP: no pude leer el registro: " + ex);
        }
    }

    private static void save() {
        if (file == null) {
            return;
        }
        try {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, Integer> e : EXITS.entrySet()) {
                lines.add(e.getKey() + "\t" + e.getValue());
            }
            Files.write(file, lines);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[ctrkart] TP: no pude guardar el registro: " + ex);
        }
    }

    /** El TP de Salida valido mas cercano con ese numero, o null. Borra del registro los que ya no existen. */
    private static BlockPos findExit(ServerLevel level, int channel, Vec3 from) {
        String prefix = level.dimension() + "\t";
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        boolean changed = false;
        Iterator<Map.Entry<String, Integer>> it = EXITS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Integer> e = it.next();
            if (!e.getKey().startsWith(prefix) || e.getValue() != channel) {
                continue;
            }
            BlockPos pos = BlockPos.of(Long.parseLong(e.getKey().substring(prefix.length())));
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() != KartBlocks.TP_EXIT || state.getValue(TpBlock.CHANNEL) != channel) {
                it.remove(); // ya no existe o cambio de numero
                changed = true;
                continue;
            }
            double dx = pos.getX() + 0.5 - from.x;
            double dy = pos.getY() - from.y;
            double dz = pos.getZ() + 0.5 - from.z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        if (changed) {
            save();
        }
        return best;
    }

    // ---------------------------------------------------------------- teletransporte

    /** Se llama cada tick de cada mundo (desde KartMod). */
    public static void tick(ServerLevel level) {
        for (ServerPlayer p : level.players()) {
            UUID id = p.getUUID();
            Entity v = p.getVehicle();
            if (!(v instanceof AbstractHorse horse) || KartMod.serverClass(horse) < 0) {
                LAST_POS.remove(id);
                COOLDOWN.remove(id);
                continue;
            }
            Vec3 now = horse.position();
            Vec3 prev = LAST_POS.put(id, now);
            Integer cd = COOLDOWN.get(id);
            if (cd != null) {
                if (cd <= 1) {
                    COOLDOWN.remove(id);
                } else {
                    COOLDOWN.put(id, cd - 1);
                }
                continue;
            }
            int channel = entryChannel(level, prev == null ? now : prev, now);
            if (channel > 0) {
                teleport(level, p, horse, channel, now);
            }
        }
    }

    /** Numero del primer TP de Entrada que toca el kart entre las dos posiciones, o 0. */
    private static int entryChannel(ServerLevel level, Vec3 from, Vec3 to) {
        double dist = from.distanceTo(to);
        int steps = Math.max(1, (int) Math.ceil(dist / STEP));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = Mth.lerp(t, from.x, to.x);
            double y = Mth.lerp(t, from.y, to.y);
            double z = Mth.lerp(t, from.z, to.z);
            // el caballo mide ~1 bloque de alto: se miran dos alturas
            for (double dy : new double[] {0.1, 0.7}) {
                BlockState state = level.getBlockState(new BlockPos(Mth.floor(x), Mth.floor(y + dy), Mth.floor(z)));
                if (state.getBlock() == KartBlocks.TP_ENTRY) {
                    return state.getValue(TpBlock.CHANNEL);
                }
            }
        }
        return 0;
    }

    private static void teleport(ServerLevel level, ServerPlayer p, AbstractHorse horse, int channel, Vec3 from) {
        BlockPos exit = findExit(level, channel, from);
        if (exit == null) {
            COOLDOWN.put(p.getUUID(), 40);
            p.sendSystemMessage(Component.literal("§b[TP] §fNo hay ningun TP de Salida con el numero " + channel + "."));
            return;
        }
        double x = exit.getX() + 0.5;
        double y = exit.getY();
        double z = exit.getZ() + 0.5;
        horse.setPos(x, y, z); // el jugador va montado: lo sigue solo
        LAST_POS.put(p.getUUID(), new Vec3(x, y, z));
        COOLDOWN.put(p.getUUID(), COOLDOWN_TICKS);
        // El cliente es quien conduce el kart: se le dice el sitio nuevo (su estado no se toca)
        ServerPlayNetworking.send(p, new KartTpPayload(KartTpPayload.TELEPORT, horse.getId(), channel, x, y, z));
    }
}
