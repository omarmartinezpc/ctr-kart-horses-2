package com.ctrkart.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Da acceso a la tecla de salto del jugador (campo "jumping"), que usamos como "hop" del kart. */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("jumping")
    boolean ctrkart$isJumping();
}
