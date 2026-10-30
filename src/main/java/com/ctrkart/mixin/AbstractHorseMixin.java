package com.ctrkart.mixin;

import com.ctrkart.KartPhysics;
import com.ctrkart.KartSeat;
import com.ctrkart.KartState;
import com.ctrkart.KartTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.animal.equine.AbstractHorse; // si no existe: net.minecraft.world.entity.animal.horse.AbstractHorse
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
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

    /** Sin ruido de cascos: un kart no tiene patas. (Se cancela en servidor, que es quien lo reproduce.) */
    @Inject(method = "playStepSound", at = @At("HEAD"), cancellable = true, require = 0)
    private void ctrkart$noHoofSteps(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (KartSeat.isKart((AbstractHorse) (Object) this)) {
            ci.cancel();
        }
    }

    /**
     * AbstractHorse suma su propio desplazamiento al punto del jinete cuando el caballo se levanta
     * ("relincha"). Lo recolocamos aqui, al final, para que el jugador no se vaya hacia atras.
     */
    @Inject(method = "getPassengerAttachmentPoint", at = @At("RETURN"), cancellable = true, require = 0)
    private void ctrkart$seatPointFinal(Entity passenger, EntityDimensions dimensions, float scale,
                                        CallbackInfoReturnable<Vec3> cir) {
        Vec3 off = KartSeat.seatOffset((AbstractHorse) (Object) this);
        if (off != null) {
            cir.setReturnValue(off);
        }
    }

    /** Un kart nunca se pone de manos. */
    @Inject(method = "isStanding", at = @At("HEAD"), cancellable = true, require = 0)
    private void ctrkart$neverRear(CallbackInfoReturnable<Boolean> cir) {
        if (KartSeat.isKart((AbstractHorse) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
