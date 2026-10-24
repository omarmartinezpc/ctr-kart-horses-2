package com.ctrkart.mixin;

import com.ctrkart.KartPhysics;
import com.ctrkart.KartState;
import com.ctrkart.KartTracker;
import net.minecraft.world.entity.animal.equine.AbstractHorse; // si no existe: net.minecraft.world.entity.animal.horse.AbstractHorse
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Solo actua en el cliente que controla al caballo (igual que vanilla) y solo si el servidor
 * le ha dicho que ese caballo es un kart.
 */
@Mixin(AbstractHorse.class)
public abstract class AbstractHorseMixin {

    @Inject(method = "getRiddenInput", at = @At("HEAD"), cancellable = true)
    private void ctrkart$noVanillaInput(Player player, Vec3 travel, CallbackInfoReturnable<Vec3> cir) {
        AbstractHorse self = (AbstractHorse) (Object) this;
        if (self.level().isClientSide() && KartTracker.get(self.getId()) != null) {
            cir.setReturnValue(Vec3.ZERO); // la velocidad la fijamos nosotros en tickRidden
        }
    }

    @Inject(method = "tickRidden", at = @At("HEAD"), cancellable = true)
    private void ctrkart$kartTick(Player player, Vec3 travel, CallbackInfo ci) {
        AbstractHorse self = (AbstractHorse) (Object) this;
        if (!self.level().isClientSide()) {
            return;
        }
        KartState state = KartTracker.get(self.getId());
        if (state == null) {
            return;
        }
        KartPhysics.tick(self, player, state);
        ci.cancel(); // anula rotacion/salto de vanilla
    }
}
