package com.faz.hubdimension;

import com.faz.hubdimension.HubConfig.TimeMode;
import com.faz.hubdimension.HubConfig.WeatherMode;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** All the "rules" of the hub. Every handler first checks that we are really inside the hub dimension. */
public final class HubEvents {
    private HubEvents() {}

    // ------------------------------------------------------------------ helpers

    public static boolean isHub(LevelAccessor level) {
        return level instanceof Level l && l.dimension().equals(HubDimension.HUB);
    }

    /** Operators in creative mode may ignore the rules (if enabled in the config). */
    private static boolean bypass(Player player) {
        return HubConfig.OPS_BYPASS.get() && player.isCreative() && player.hasPermissions(2);
    }

    // ------------------------------------------------------------------ blocks

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (HubConfig.PROTECT_BLOCKS.get() && isHub(event.getLevel()) && !bypass(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!HubConfig.PROTECT_BLOCKS.get() || !isHub(event.getLevel())) return;
        if (event.getEntity() instanceof Player p && bypass(p)) return;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (HubConfig.PROTECT_BLOCKS.get() && isHub(event.getLevel())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBucket(PlayerInteractEvent.RightClickItem event) {
        if (HubConfig.PROTECT_BLOCKS.get()
                && isHub(event.getLevel())
                && event.getItemStack().getItem() instanceof net.minecraft.world.item.BucketItem
                && !bypass(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /**
     * Right click on a block: we only forbid the ITEM part (placing, wrench, flint and steel...),
     * the block itself (lever, button, Create train controls...) still gets its normal interaction.
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!isHub(event.getLevel()) || bypass(event.getEntity())) return;

        if (HubConfig.BLOCK_ITEM_USE.get()) {
            event.setUseItem(TriState.FALSE);
        }
        if (HubConfig.LOCK_CONTAINERS.get()) {
            net.minecraft.core.BlockPos pos = event.getPos();
            if (event.getLevel().getBlockEntity(pos) instanceof net.minecraft.world.Container
                    || event.getLevel().getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.LecternBlock) {
                event.setUseBlock(TriState.FALSE);
            }
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Start event) {
        if (HubConfig.NO_EXPLOSIONS.get() && isHub(event.getLevel())) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ entities / players

    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (HubConfig.NO_DAMAGE.get() && event.getEntity() instanceof Player p && isHub(p.level())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (HubConfig.PROTECT_ENTITIES.get() && isHub(event.getEntity().level()) && !bypass(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private static final java.util.Set<String> BLOCKED_ENTITIES = java.util.Set.of("create:super_glue");

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        // This event fires on the SERVER and on every CLIENT. Cancelling it on a client means the client never
        // creates the entity: it exists on the server (so /data finds it and it blocks placing blocks), but you
        // can not see it and it has no hitbox. The rules below are server decisions only.
        if (event.getLevel().isClientSide()) return;
        if (!isHub(event.getLevel())) return;
        if (HubPaster.isSpawningEntities()) return; // entities being pasted are allowed
        Entity e = event.getEntity();
        if (e instanceof Mob) {
            // Mobs that are LOADED from the chunk files (the pasted parrots etc.) stay.
            // New ones (natural spawns, spawn eggs, /summon, command blocks) are blocked.
            if (HubConfig.NO_MOB_SPAWNING.get() && !event.loadedFromDisk()) {
                event.setCanceled(true);
            }
        } else if (e instanceof LightningBolt) {
            if (!HubConfig.ALLOW_LIGHTNING.get()) {
                event.setCanceled(true);
            }
        } else if (!event.loadedFromDisk() && HubConfig.PROTECT_BLOCKS.get()
                && BLOCKED_ENTITIES.contains(
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString())) {
            // who placed it? Best guess: the closest player (creative players included)
            Player placer = event.getLevel().getNearestPlayer(e.getX(), e.getY(), e.getZ(), 16.0, false);
            if (placer == null || !bypass(placer)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !isHub(player.level())) return;

        // fell out of the hub -> back to spawn
        if (player.getY() < HubConfig.FALL_RESCUE_Y.get()) {
            player.teleportTo(player.serverLevel(),
                    HubConfig.SPAWN_X.get(), HubConfig.SPAWN_Y.get(), HubConfig.SPAWN_Z.get(),
                    HubConfig.SPAWN_YAW.get().floatValue(), 0.0F);
            player.fallDistance = 0.0F;
            player.setDeltaMovement(Vec3.ZERO);
            player.hurtMarked = true;
        }

        if (HubConfig.NO_HUNGER.get() && player.tickCount % 20 == 0) {
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(20.0F);
        }
    }

    // ------------------------------------------------------------------ time and weather

    /**
     * Time of day: the server itself already reports the static time (see LevelMixin), so vanilla's
     * own time packets are correct. This only freezes the client clock between those packets.
     */
    private static void syncTime(ServerPlayer player) {
        if (HubConfig.TIME_MODE.get() == TimeMode.STATIC) {
            ServerLevel hub = player.serverLevel();
            player.connection.send(new ClientboundSetTimePacket(hub.getGameTime(), HubConfig.STATIC_TIME.get(), false));
        }
    }

    /**
     * Weather is sent ONCE when a player arrives. The hub's own weather cycle is switched off
     * (see ServerLevelMixin), so nothing overrides it afterwards. In FOLLOW_OVERWORLD mode vanilla does everything.
     */
    private static void syncWeather(ServerPlayer player) {
        WeatherMode mode = HubConfig.WEATHER_MODE.get();
        if (mode == WeatherMode.FOLLOW_OVERWORLD) return;

        float rain = mode == WeatherMode.CLEAR ? 0.0F : 1.0F;
        float thunder = mode == WeatherMode.THUNDER ? 1.0F : 0.0F;
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, rain));
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, thunder));
    }

    /** Sends the configured sky to one player in the hub (login, arrival). */
    public static void syncSky(ServerPlayer player) {
        if (!isHub(player.level())) return;
        syncTime(player);
        syncWeather(player);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        HubPaster.tick();

        MinecraftServer server = event.getServer();
        // vanilla sends its own time packet every 20 ticks - we send ours right after it, same tick
        if (server.getTickCount() % 20 != 0) return;
        if (HubConfig.TIME_MODE.get() != TimeMode.STATIC) return;

        ServerLevel hub = server.getLevel(HubDimension.HUB);
        if (hub == null) return;
        for (ServerPlayer player : hub.players()) {
            syncTime(player);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) syncSky(player);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) syncSky(player);
    }

    // ------------------------------------------------------------------ housekeeping

    /** If the server stopped or crashed in the middle of a paste, its forced chunks would stay forced forever. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel hub = event.getServer().getLevel(HubDimension.HUB);
        if (hub == null) return;
        for (long chunk : hub.getForcedChunks().toLongArray()) {
            hub.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false);
        }
    }
}
