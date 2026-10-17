package com.faz.hubdimension;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pastes the schematic into the hub dimension, spread over several ticks so the server does not freeze. */
public final class HubPaster {
    private HubPaster() {}

    // 2 = send to clients, 16 = no neighbour shape updates, 32 = no drops.
    // Flag 16 protects blocks that would pop off while their neighbours are not placed yet (torches, signs...).
    // The price: blocks that react to "I was placed" (Create's kinetic blocks) are told separately, see placeKineticBlocks().
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private static Job current;
    private static boolean spawningEntities;

    public static boolean isRunning() {
        return current != null;
    }

    /** True only while the paste itself is adding entities (lets pasted mobs past the "no mobs" rule). */
    public static boolean isSpawningEntities() {
        return spawningEntities;
    }

    /** @return null if started, otherwise an error message for the player */
    public static String start(ServerLevel hub) {
        if (current != null) return "The hub is already being pasted.";

        Path file = HubDimension.schematicFolder().resolve(HubConfig.SCHEMATIC_FILE.get());
        if (!Files.exists(file)) HubDimension.copyDefaultSchematic();
        if (!Files.exists(file)) return "Schematic not found: " + file;

        Schematic schematic;
        try {
            schematic = Schematic.load(file);
        } catch (Exception e) {
            HubDimension.LOGGER.error("Could not read schematic", e);
            return "Could not read schematic: " + e.getMessage();
        }

        BlockPos origin = new BlockPos(HubConfig.PASTE_X.get(), HubConfig.PASTE_Y.get(), HubConfig.PASTE_Z.get());
        current = new Job(hub, schematic, origin);
        HubDimension.LOGGER.info("Pasting hub: {}x{}x{} blocks, {} entities at {}",
                schematic.width, schematic.height, schematic.length, schematic.entities.size(), origin);
        return null;
    }

    /** Called every server tick. */
    public static void tick() {
        if (current == null) return;
        try {
            if (current.step()) current = null;
        } catch (Exception e) {
            HubDimension.LOGGER.error("Hub paste failed", e);
            current.forceChunks(false);
            current = null;
        }
    }

    private static final class Job {
        private final ServerLevel level;
        private final Schematic schem;
        private final BlockPos origin;
        private final int total;
        private int cursor = 0;
        private int changed = 0;
        private int removed = 0;

        // 0 = place everything EXCEPT Create-style kinetic blocks, then (in the SAME tick) the kinetic blocks
        //     and the "refresh" of modded blocks. With pasteInOneTick the whole hub appears inside one server tick.
        // 2 = pause, then remove the old entities
        // 3 = pause (clients process the removals), then add the saved entities
        // 4 = pause, check that the pasted entities still exist, then finish
        private int phase = 0;
        private int waitTicks = 0;

        /** relative positions of block entities that save a "Speed" tag = Create's kinetic blocks (and addons) */
        private final List<Long> kinetic = new ArrayList<>();
        private final List<Entity> pasted = new ArrayList<>();
        private int failed = 0;

        Job(ServerLevel level, Schematic schem, BlockPos origin) {
            this.level = level;
            this.schem = schem;
            this.origin = origin;
            this.total = schem.volume();
            for (Map.Entry<Long, CompoundTag> entry : schem.blockEntities.entrySet()) {
                if (entry.getValue().contains("Speed")) kinetic.add(entry.getKey());
            }
            forceChunks(true); // keep the chunks loaded while we work
        }

        void forceChunks(boolean force) {
            int minX = origin.getX() >> 4;
            int maxX = (origin.getX() + schem.width - 1) >> 4;
            int minZ = origin.getZ() >> 4;
            int maxZ = (origin.getZ() + schem.length - 1) >> 4;
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    level.setChunkForced(cx, cz, force);
                }
            }
        }

        /** @return true when finished */
        boolean step() {
            switch (phase) {
                case 0 -> {
                    placeBlocks();
                    if (cursor >= total) {
                        placeKineticBlocks();   // same tick: nothing can tick between these steps
                        refreshModdedBlocks();
                        phase = 2;
                        waitTicks = 20; // wait until the chunks have loaded their existing entities
                    }
                }
                case 2 -> {
                    if (--waitTicks <= 0) {
                        removeOldEntities();
                        phase = 3;
                        waitTicks = 20; // give clients a second to process the removals before new entities appear
                    }
                }
                case 3 -> {
                    if (--waitTicks <= 0) {
                        spawnEntities();
                        phase = 4;
                        waitTicks = 40;
                    }
                }
                default -> {
                    if (--waitTicks <= 0) {
                        finish();
                        return true;
                    }
                }
            }
            return false;
        }

        private void placeBlocks() {
            int budget = HubConfig.PASTE_IN_ONE_TICK.get() ? Integer.MAX_VALUE : HubConfig.BLOCKS_PER_TICK.get();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            while (budget-- > 0 && cursor < total) {
                int i = cursor++;
                int x = i % schem.width;
                int z = (i / schem.width) % schem.length;
                int y = i / (schem.width * schem.length);

                CompoundTag beTag = schem.blockEntities.get(BlockPos.asLong(x, y, z));
                if (beTag != null && beTag.contains("Speed")) continue; // kinetic blocks: phase 1

                pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                BlockState state = schem.stateAt(i);
                if (level.getBlockState(pos) != state) {
                    level.setBlock(pos, state, FLAGS);
                    changed++;
                }

                if (beTag != null) {
                    loadBlockEntity(pos, beTag);
                }
            }
        }

        private void loadBlockEntity(BlockPos pos, CompoundTag saved) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) return;
            try {
                CompoundTag tag = saved.copy();
                tag.putInt("x", pos.getX());
                tag.putInt("y", pos.getY());
                tag.putInt("z", pos.getZ());
                be.loadWithComponents(tag, level.registryAccess());
                be.setChanged();
            } catch (Exception e) {
                HubDimension.LOGGER.warn("Could not load block entity data at {}", pos, e);
            }
        }

        /**
         * Create's kinetic blocks (belts, cogs, motors, fans, bearings...) need the same order as when a player
         * places them: block placed -> "updateIndirectNeighbourShapes" (they forget the saved speed, network and
         * belt controller, which point to where the hub was BUILT) -> first tick (motors start generating, belts
         * build their chain, cogs attach to the network).
         *
         * Two things can go wrong if this is spread over several ticks: a belt may tick before its neighbours
         * exist and destroy itself, and a motor may run its first tick with the OLD speed and then never restart.
         * So all kinetic blocks are placed and notified inside ONE tick, before any of them can tick.
         * Existing ones are removed first so that a re-paste gives fresh block entities too.
         */
        private void placeKineticBlocks() {
            int count = 0;
            for (long rel : kinetic) {
                BlockPos r = BlockPos.of(rel);
                int index = r.getX() + r.getZ() * schem.width + r.getY() * schem.width * schem.length;
                if (index < 0 || index >= total) continue;
                BlockPos pos = origin.offset(r);
                BlockState state = schem.stateAt(index);
                if (state.isAir()) continue;
                if (!level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                }
                level.setBlock(pos, state, FLAGS);
                changed++;
                loadBlockEntity(pos, schem.blockEntities.get(rel));
                count++;
            }
            for (long rel : kinetic) {
                BlockPos pos = origin.offset(BlockPos.of(rel));
                try {
                    level.getBlockState(pos).updateIndirectNeighbourShapes(level, pos, Block.UPDATE_CLIENTS);
                } catch (Exception e) {
                    HubDimension.LOGGER.warn("Could not notify block at {}", pos, e);
                }
            }

            // Give command blocks a first scheduled tick so "always active" ones start running.
            // (Chain command blocks must NOT be ticked on their own.)
            for (long rel : schem.blockEntities.keySet()) {
                BlockPos pos = origin.offset(BlockPos.of(rel));
                if (level.getBlockEntity(pos) instanceof CommandBlockEntity cbe
                        && cbe.getMode() != CommandBlockEntity.Mode.SEQUENCE) {
                    level.scheduleTick(pos, level.getBlockState(pos).getBlock(), 1);
                }
            }
            HubDimension.LOGGER.info("Placed {} kinetic blocks in one tick.", count);
        }

        /**
         * Many modded blocks only look at their surroundings when they get a (scheduled) tick: Create's pipes
         * work out their connections, clutches and gearshifts compare their state with the redstone signal.
         * Give every modded block that has a block entity one tick now that the whole hub exists.
         * (Vanilla blocks are left alone: a tick would make observers pulse, for example.)
         */
        private void refreshModdedBlocks() {
            int count = 0;
            for (long rel : schem.blockEntities.keySet()) {
                BlockPos pos = origin.offset(BlockPos.of(rel));
                BlockState state = level.getBlockState(pos);
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                if (!"minecraft".equals(id.getNamespace())) {
                    level.scheduleTick(pos, state.getBlock(), 1);
                    count++;
                }
            }
            HubDimension.LOGGER.info("Scheduled a refresh tick for {} modded blocks.", count);
        }

        private void removeOldEntities() {
            AABB box = new AABB(
                    origin.getX(), origin.getY(), origin.getZ(),
                    origin.getX() + schem.width, origin.getY() + schem.height, origin.getZ() + schem.length);
            for (Entity e : level.getEntitiesOfClass(Entity.class, box, en -> !(en instanceof Player))) {
                e.discard();
                removed++;
            }
        }

        private void spawnEntities() {
            spawningEntities = true;
            try {
                for (CompoundTag saved : schem.entities) {
                    if (!spawnEntity(saved)) failed++;
                }
            } finally {
                spawningEntities = false;
            }
        }

        private void finish() {
            // evidence for "my parrots are gone": which pasted entities were removed again by something else?
            int missing = 0;
            for (Entity e : pasted) {
                if (e.isRemoved() || level.getEntity(e.getUUID()) != e) {
                    missing++;
                    HubDimension.LOGGER.warn("Pasted entity {} at {} no longer exists (removed={}, reason={})",
                            EntityType.getKey(e.getType()), e.blockPosition(), e.isRemoved(), e.getRemovalReason());
                }
            }

            forceChunks(false);
            HubState.markPasted(level.getServer());
            HubDimension.LOGGER.info("Hub paste finished. Changed {} blocks, removed {} old entities, pasted {} entities ({} failed, {} gone again after 2 s).",
                    changed, removed, pasted.size(), failed, missing);
        }

        /**
         * Entities that ride something (a parrot on a Create seat) store their own position in the schematic,
         * and that is NOT converted like the top entity's. Without this the parrot was added far outside the hub,
         * in a chunk that is not loaded, and simply never existed. Put every passenger on its vehicle first.
         */
        private static void placePassengers(Entity vehicle) {
            for (Entity passenger : vehicle.getPassengers()) {
                passenger.setPos(vehicle.getX(), vehicle.getY(), vehicle.getZ());
                placePassengers(passenger);
            }
        }

        private void applyMobSettings(Entity entity) {
            if (entity instanceof Mob mob) {
                mob.setPersistenceRequired(); // do not despawn when nobody is near
                if (HubConfig.FREEZE_PASTED_MOBS.get()) {
                    mob.setNoAi(true); // decoration: parrots and co stay where you put them
                }
            }
        }

        private boolean spawnEntity(CompoundTag saved) {
            try {
                ListTag rel = saved.getList("Pos", Tag.TAG_DOUBLE);
                if (rel.size() != 3) return false;
                double x = origin.getX() + rel.getDouble(0);
                double y = origin.getY() + rel.getDouble(1);
                double z = origin.getZ() + rel.getDouble(2);

                CompoundTag tag = saved.copy();
                tag.remove("UUID"); // a fresh UUID is generated -> no conflicts when pasting twice
                ListTag absPos = new ListTag();
                absPos.add(DoubleTag.valueOf(x));
                absPos.add(DoubleTag.valueOf(y));
                absPos.add(DoubleTag.valueOf(z));
                tag.put("Pos", absPos);

                // item frames / paintings are positioned by their block position, not by Pos
                boolean hanging = tag.contains("TileX");
                if (hanging) {
                    tag.putInt("TileX", Mth.floor(x));
                    tag.putInt("TileY", Mth.floor(y));
                    tag.putInt("TileZ", Mth.floor(z));
                }

                Entity entity = EntityType.loadEntityRecursive(tag, level, e -> e);
                if (entity == null) return false;

                if (hanging && entity instanceof HangingEntity) {
                    // paintings with an even size end up one block off - correct that and load again
                    Vec3 diff = new Vec3(x, y, z).subtract(entity.position());
                    int dx = (int) Math.round(diff.x);
                    int dy = (int) Math.round(diff.y);
                    int dz = (int) Math.round(diff.z);
                    if (dx != 0 || dy != 0 || dz != 0) {
                        tag.putInt("TileX", tag.getInt("TileX") + dx);
                        tag.putInt("TileY", tag.getInt("TileY") + dy);
                        tag.putInt("TileZ", tag.getInt("TileZ") + dz);
                        entity = EntityType.loadEntityRecursive(tag, level, e -> e);
                        if (entity == null) return false;
                    }
                }

                placePassengers(entity); // passengers keep their OLD world coordinates in the schematic
                applyMobSettings(entity);
                for (Entity passenger : entity.getIndirectPassengers()) {
                    applyMobSettings(passenger);
                }
                level.addFreshEntityWithPassengers(entity);
                pasted.add(entity);
                for (Entity passenger : entity.getIndirectPassengers()) {
                    pasted.add(passenger);
                }
                return true;
            } catch (Exception e) {
                HubDimension.LOGGER.warn("Could not paste entity {}", saved.getString("id"), e);
                return false;
            }
        }
    }
}
