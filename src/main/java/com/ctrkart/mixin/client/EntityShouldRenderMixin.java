package com.ctrkart.mixin.client;

import com.ctrkart.KartTracker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse; // si no existe: ...animal.horse.AbstractHorse
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Segunda via para que el caballo-kart (con su silla) no se dibuje: Entity.shouldRender(x, y, z) es la
 * comprobacion de distancia de dibujado, que existe desde siempre y casi nunca cambia de firma.
 */
@Mixin(Entity.class)
public abstract class EntityShouldRenderMixin {
    @Inject(method = "shouldRender(DDD)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void ctrkart$hideKartHorse(double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (KartTracker.modelReady && self instanceof AbstractHorse && KartTracker.get(self.getId()) != null) {
            cir.setReturnValue(false);
        }
    }
}
