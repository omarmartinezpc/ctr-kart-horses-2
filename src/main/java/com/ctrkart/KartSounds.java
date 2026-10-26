package com.ctrkart;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * Sonidos propios. Los audios estan en assets/ctrkart/sounds/ y se declaran en assets/ctrkart/sounds.json.
 * Para usar otro audio, reemplaza el .ogg (mono, Vorbis) por el tuyo con el mismo nombre.
 */
public final class KartSounds {
    public static final SoundEvent ENGINE = event("kart.engine");

    /** Los tres sonidos del Sacred Fire: se intercalan al azar, sin repetir el mismo dos veces seguidas. */
    private static final SoundEvent[] SACRED = {event("sacred.fire.1"), event("sacred.fire.2"), event("sacred.fire.3")};
    private static int lastSacred = -1;

    private KartSounds() {}

    private static SoundEvent event(String name) {
        return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(KartMod.MOD_ID, name));
    }

    public static SoundEvent nextSacred() {
        int i;
        do {
            i = ThreadLocalRandom.current().nextInt(SACRED.length);
        } while (i == lastSacred);
        lastSacred = i;
        return SACRED[i];
    }
}
