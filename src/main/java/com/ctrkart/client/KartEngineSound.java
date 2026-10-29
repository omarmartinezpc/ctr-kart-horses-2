package com.ctrkart.client;

import com.ctrkart.KartSounds;
import com.ctrkart.KartState;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

/**
 * Motor del kart: un bucle continuo (reconstruido a partir del audio de referencia de CTR Nitro-Fueled)
 * cuyo tono y volumen se actualizan cada tick.
 *
 * Referencia medida: a maxima velocidad el motor es una nota estable de ~172 Hz (= tono 1.0 del bucle);
 * en marcha normal ronda el 82-87 % de eso. Alrededor de un Sacred Fire el motor baja ~8-13 % justo antes
 * (derrape) y sube ~+16 % en el momento del estallido, volviendo en ~0,5 s a un tono alto sostenido.
 */
public final class KartEngineSound extends AbstractTickableSoundInstance {
    private static final float KICK = 0.16f; // salto de tono al asestar un Sacred Fire

    private final Entity horse;
    private boolean stopRequested;
    private float kick;
    private float dip = 1.0f;
    private float curPitch = 0.50f;
    private float curVolume = 0.01f;
    private int lastEvents = -1;

    public KartEngineSound(Entity horse) {
        super(KartSounds.ENGINE, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
        this.horse = horse;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.01f;
        this.pitch = 0.50f;
        this.x = horse.getX();
        this.y = horse.getY();
        this.z = horse.getZ();
    }

    /** Se llama una vez por tick desde KartVisuals con la velocidad horizontal real (bloques/tick). */
    public void update(double speed, KartState st) {
        double r = Math.max(0.0, speed / st.kartClass.top); // 1.0 = velocidad maxima base

        // tono segun la velocidad (mas grave que antes): reposo 0.50 (el minimo del juego), marcha ~0.62,
        // maximo 0.78, con turbos hasta ~0.95
        float base;
        if (r <= 0.6) {
            base = (float) (0.50 + 0.20 * r);
        } else if (r <= 1.0) {
            base = (float) (0.62 + 0.40 * (r - 0.6));
        } else {
            base = (float) (0.78 + 0.17 * Math.min(r - 1.0, 0.55) / 0.55);
        }

        // interaccion con el Sacred Fire: salto al asestarlo, que decae; baja un poco mientras cargas el derrape
        if (lastEvents < 0) {
            lastEvents = st.sacredEvents;
        } else if (st.sacredEvents != lastEvents) {
            lastEvents = st.sacredEvents;
            kick = KICK;
        }
        kick *= 0.90f; // ~0,5 s para volver
        dip += ((st.drifting ? 0.93f : 1.0f) - dip) * 0.25f;

        float targetPitch = Math.max(0.5f, Math.min(1.9f, base * dip * (1.0f + kick)));
        float targetVolume = 0.14f + 0.18f * (float) Math.min(r, 1.0);
        if (st.sacred) {
            targetVolume *= 1.25f;
        }
        targetVolume = Math.min(0.5f, targetVolume + 0.1f * (kick / KICK));

        curPitch += (targetPitch - curPitch) * 0.4f;
        curVolume += (targetVolume - curVolume) * 0.3f;
        this.pitch = curPitch;
        this.volume = curVolume;
    }

    public void requestStop() {
        stopRequested = true;
    }

    @Override
    public void tick() {
        if (stopRequested || horse.isRemoved()) {
            this.stop();
            return;
        }
        this.x = horse.getX();
        this.y = horse.getY();
        this.z = horse.getZ();
    }
}
