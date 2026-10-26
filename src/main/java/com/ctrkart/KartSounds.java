package com.ctrkart;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * Sonidos propios. El audio esta en assets/ctrkart/sounds/ y se declara en assets/ctrkart/sounds.json.
 * Para usar otro audio, reemplaza sounds/kart_engine.ogg (mono, .ogg Vorbis, corto y que se pueda repetir).
 */
public final class KartSounds {
    public static final SoundEvent ENGINE =
            SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(KartMod.MOD_ID, "kart.engine"));

    private KartSounds() {}
}
