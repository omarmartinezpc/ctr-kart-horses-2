package com.ctrkart;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Bloque de teletransporte (sin colision, translucido). Tiene un numero (propiedad "channel", de 1 a MAX_CHANNEL),
 * como el nivel del bloque de luz:
 *  - TP de Entrada (azul celeste): al atravesarlo con el kart, te lleva a ti y al kart al TP de Salida del mismo numero.
 *  - TP de Salida (azul marino): el destino.
 * El numero se cambia con click derecho con la mano vacia (agachado = baja el numero). Ver KartTeleport.
 */
public class TpBlock extends Block {
    public static final int MAX_CHANNEL = 32;
    public static final IntegerProperty CHANNEL = IntegerProperty.create("channel", 1, MAX_CHANNEL);

    private final boolean entry;

    public TpBlock(Properties properties, boolean entry) {
        super(properties);
        this.entry = entry;
        registerDefaultState(stateDefinition.any().setValue(CHANNEL, 1));
    }

    public boolean isEntry() {
        return entry;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CHANNEL);
    }

    /** Al colocarlo tiene el ultimo numero que pusiste tu (asi un par sale con el mismo numero sin tocar nada). */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(CHANNEL, KartTeleport.lastChannel(context.getPlayer()));
    }

    /** Los TP de Salida se apuntan en el registro del servidor para poder encontrarlos aunque su zona no este cargada. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!entry && level instanceof ServerLevel serverLevel) {
            KartTeleport.registerExit(serverLevel, pos, state.getValue(CHANNEL));
        }
    }
}
