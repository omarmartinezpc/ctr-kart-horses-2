package com.ctrkart;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * Sacred Pad orientable (como un piston): las flechas y las lineas que suben apuntan hacia "facing".
 *  - Al ponerlo en el suelo o en el techo: apunta hacia donde miras (agachado = al reves).
 *  - Al ponerlo en una pared: las flechas suben (agachado = bajan).
 * Tambien gira con las herramientas de estructuras (rotar / espejo).
 */
public class SacredPadBlock extends Block {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    public SacredPadBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace().getAxis().isVertical()
                ? context.getHorizontalDirection() // suelo / techo: hacia donde miras
                : Direction.UP;                      // pared: las flechas suben
        if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
            facing = facing.getOpposite();
        }
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
