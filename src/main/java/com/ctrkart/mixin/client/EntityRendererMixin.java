package com.ctrkart.mixin.client;

import com.ctrkart.KartTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Oculta el modelo del caballo cuando es un kart (se ve el modelo de kart en su lugar). */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
    private void ctrkart$hideKartHorse(Entity entity, Frustum frustum, double x, double y, double z, float partialTick,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof AbstractHorse && KartTracker.get(entity.getId()) != null) {
            cir.setReturnValue(false);
        }
    }
}
