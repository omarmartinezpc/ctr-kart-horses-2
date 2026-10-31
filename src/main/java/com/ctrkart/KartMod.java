package com.ctrkart;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.AbstractHorse; // si no existe: ...animal.horse.AbstractHorse
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.EntityHitResult;
import org.jetbrains.annotations.Nullable;

public class KartMod implements ModInitializer {
    public static final String MOD_ID = "ctrkart";

    private static final int SUPER_MAX = 20 * 45;
    /** Los karts son mas bajos que un caballo: asi el jugador va sentado a la altura del modelo. */
    private static final double KART_SCALE = 0.6;
    /** Comida -> ticks de Super Motor (la manzana hace de Wumpa Fruit). */
    private static final Map<Item, Integer> FOOD = Map.of(
            Items.APPLE, 20 * 8,
            Items.GOLDEN_CARROT, 20 * 12,
            Items.GOLDEN_APPLE, 20 * 20,
            Items.ENCHANTED_GOLDEN_APPLE, 20 * 40,
            Items.CARROT, 20 * 6,
            Items.SUGAR, 20 * 5,
            Items.WHEAT, 20 * 4);

    /** Comida -> energia que recibe el kart (0..100). */
    private static final Map<Item, Integer> FOOD_ENERGY = Map.of(
            Items.APPLE, 15,
            Items.GOLDEN_CARROT, 25,
            Items.GOLDEN_APPLE, 40,
            Items.ENCHANTED_GOLDEN_APPLE, 100,
            Items.CARROT, 12,
            Items.SUGAR, 10,
            Items.WHEAT, 8);

    /** Modificador temporal (no se guarda con el jugador) que lo encoge mientras conduce un kart. */
    private static final Identifier RIDER_SCALE_ID = Identifier.fromNamespaceAndPath(MOD_ID, "rider_scale");

    private static final Map<UUID, Integer> SUPER = new ConcurrentHashMap<>(); // ticks restantes (servidor)
    private static final Map<UUID, Integer> LAST_CLASS = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(KartSyncPayload.TYPE, KartSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(KartEnergyPayload.TYPE, KartEnergyPayload.CODEC);

        UseEntityCallback.EVENT.register(KartMod::onUseEntity);

        EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
            if (entity instanceof AbstractHorse horse) {
                int cls = KartClass.idOf(horse);
                if (cls >= 0) {
                    ServerPlayNetworking.send(player,
                            new KartSyncPayload(horse.getId(), cls, SUPER.getOrDefault(horse.getUUID(), 0)));
                }
            }
        });

        ServerTickEvents.END_LEVEL_TICK.register(KartMod::tickLevel);
    }

    /** Cada 10 ticks: detecta /tag add|remove kart y descuenta el Super Motor. */
    private static void tickLevel(ServerLevel level) {
        updateRiders(level);
        if (level.getGameTime() % 10L != 0L) {
            return;
        }
        for (AbstractHorse horse : level.getEntities(EntityTypeTest.forClass(AbstractHorse.class), e -> true)) {
            UUID id = horse.getUUID();
            int cls = KartClass.idOf(horse);

            int sup = SUPER.getOrDefault(id, 0);
            if (sup > 0) {
                sup -= 10;
                if (sup <= 0) {
                    SUPER.remove(id);
                } else {
                    SUPER.put(id, sup);
                }
            }

            int prev = LAST_CLASS.getOrDefault(id, -1);
            if (prev != cls) {
                setScale(horse, cls >= 0 ? KART_SCALE : 1.0);
                if (cls < 0) {
                    LAST_CLASS.remove(id);
                    SUPER.remove(id);
                } else {
                    LAST_CLASS.put(id, cls);
                }
                broadcast(horse, cls);
            }
        }
    }

    /** Clase del kart en el servidor (-1 si el caballo no es un kart; >= 100 = modelo girado). */
    public static int serverClass(Entity e) {
        return LAST_CLASS.getOrDefault(e.getUUID(), -1);
    }

    /** El jugador que conduce un kart va mas pequeno (KartSeat.RIDER_SCALE) para encajar en el asiento. */
    private static void updateRiders(ServerLevel level) {
        for (ServerPlayer p : level.players()) {
            AttributeInstance a = p.getAttribute(Attributes.SCALE);
            if (a == null) {
                continue;
            }
            Entity v = p.getVehicle();
            boolean onKart = v instanceof AbstractHorse && LAST_CLASS.containsKey(v.getUUID());
            boolean has = a.getModifier(RIDER_SCALE_ID) != null;
            if (onKart && !has) {
                a.addTransientModifier(new AttributeModifier(RIDER_SCALE_ID, KartSeat.RIDER_SCALE - 1.0,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            } else if (!onKart && has) {
                a.removeModifier(RIDER_SCALE_ID);
            }
        }
    }

    /** Alimentar a un kart = Super Motor. */
    private static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity,
                                                 @Nullable EntityHitResult hit) {
        if (!(entity instanceof AbstractHorse horse) || level.isClientSide()) {
            return InteractionResult.PASS;
        }
        int cls = KartClass.idOf(horse);
        if (cls < 0) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);
        Item item = stack.getItem(); // antes de gastar el objeto
        Integer ticks = FOOD.get(item);
        if (ticks == null) {
            return InteractionResult.PASS;
        }
        SUPER.merge(horse.getUUID(), ticks, (a, b) -> Math.min(a + b, SUPER_MAX));
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        horse.playSound(SoundEvents.HORSE_EAT, 1.0f, 1.0f);
        broadcast(horse, cls);
        Integer energy = FOOD_ENERGY.get(item);
        if (energy != null) {
            KartEnergyPayload ep = new KartEnergyPayload(horse.getId(), energy);
            for (ServerPlayer p : PlayerLookup.tracking(horse)) {
                ServerPlayNetworking.send(p, ep);
            }
        }
        return InteractionResult.SUCCESS;
    }

    private static void setScale(AbstractHorse horse, double value) {
        AttributeInstance a = horse.getAttribute(Attributes.SCALE);
        if (a != null && a.getBaseValue() != value) {
            a.setBaseValue(value);
        }
    }

    private static void broadcast(AbstractHorse horse, int cls) {
        KartSyncPayload payload = new KartSyncPayload(horse.getId(), cls, SUPER.getOrDefault(horse.getUUID(), 0));
        for (ServerPlayer p : PlayerLookup.tracking(horse)) {
            ServerPlayNetworking.send(p, payload);
        }
    }
}
