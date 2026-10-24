package com.ctrkart;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public class KartModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KartPhysics.HUD = c -> Minecraft.getInstance().gui.setOverlayMessage(c, false);
        ClientPlayNetworking.registerGlobalReceiver(KartSyncPayload.TYPE,
                (payload, context) -> KartTracker.apply(payload)); // Fabric ya lo ejecuta en el hilo principal
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> KartTracker.clear());
    }
}
