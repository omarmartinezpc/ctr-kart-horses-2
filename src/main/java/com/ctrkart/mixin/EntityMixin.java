package com.ctrkart.mixin;

import com.ctrkart.KartSeat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.animal.equine.AbstractHorse; // si no existe: ...animal.horse.AbstractHorse
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1) Coloca al jugador sentado en el asiento del kart (en vez de a la altura de la silla de un caballo).
 * 2) Quita los sonidos de pasos / galope / respiracion del caballo cuando es un kart.
 * Todo con require = 0: si algun metodo cambia de nombre en una version nueva, el mod arranca igual.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    @Inject(method = "getPassengerAttachmentPoint", at = @At("RETURN"), cancellable = true, require = 0)
    private void ctrkart$seatPoint(Entity passenger, EntityDimensions dimensions, float scale,
                                   CallbackInfoReturnable<Vec3> cir) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof AbstractHorse)) {
            return;
        }
        Vec3 off = KartSeat.seatOffset(self);
        if (off != null) {
            cir.setReturnValue(off);
        }
    }

    @Inject(method = "getPassengerRidingPosition", at = @At("RETURN"), cancellable = true, require = 0)
    private void ctrkart$seatPosition(Entity passenger, CallbackInfoReturnable<Vec3> cir) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof AbstractHorse)) {
            return;
        }
        Vec3 off = KartSeat.seatOffset(self);
        if (off != null) {
            cir.setReturnValue(self.position().add(off));
        }
    }

    @Inject(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void ctrkart$noHoofSounds(SoundEvent sound, float volume, float pitch, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof AbstractHorse) || !KartSeat.isKart(self)) {
            return;
        }
        Identifier id = BuiltInRegistries.SOUND_EVENT.getKey(sound);
        if (id == null) {
            return;
        }
        String p = id.getPath();
        if (p.startsWith("entity.horse.step") || p.equals("entity.horse.gallop") || p.equals("entity.horse.breathe")) {
            ci.cancel();
        }
    }
}
