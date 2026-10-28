package com.faz.hubdimension;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Everything configurable. File: <world>/serverconfig/hubdimension-server.toml (generated when the world is first loaded). */
public final class HubConfig {
    private HubConfig() {}

    public enum TimeMode { STATIC, FOLLOW_OVERWORLD }
    public enum WeatherMode { CLEAR, RAIN, THUNDER, FOLLOW_OVERWORLD }

    // --- hub placement ---
    public static final ModConfigSpec.ConfigValue<String> SCHEMATIC_FILE;
    public static final ModConfigSpec.IntValue PASTE_X, PASTE_Y, PASTE_Z;
    public static final ModConfigSpec.BooleanValue AUTO_PASTE;
    public static final ModConfigSpec.BooleanValue PASTE_IN_ONE_TICK;
    public static final ModConfigSpec.IntValue BLOCKS_PER_TICK;
    public static final ModConfigSpec.DoubleValue SPAWN_X, SPAWN_Y, SPAWN_Z, SPAWN_YAW;

    // --- sky ---
    public static final ModConfigSpec.EnumValue<TimeMode> TIME_MODE;
    public static final ModConfigSpec.IntValue STATIC_TIME;
    public static final ModConfigSpec.EnumValue<WeatherMode> WEATHER_MODE;

    // --- rules ---
    public static final ModConfigSpec.BooleanValue PROTECT_BLOCKS;
    public static final ModConfigSpec.BooleanValue BLOCK_ITEM_USE;
    public static final ModConfigSpec.BooleanValue LOCK_CONTAINERS;
    public static final ModConfigSpec.BooleanValue NO_DAMAGE;
    public static final ModConfigSpec.BooleanValue NO_HUNGER;
    public static final ModConfigSpec.BooleanValue PROTECT_ENTITIES;
    public static final ModConfigSpec.BooleanValue NO_MOB_SPAWNING;
    public static final ModConfigSpec.BooleanValue NO_EXPLOSIONS;
    public static final ModConfigSpec.BooleanValue ALLOW_LIGHTNING;
    public static final ModConfigSpec.BooleanValue FREEZE_PASTED_MOBS;
    public static final ModConfigSpec.BooleanValue OPS_BYPASS;
    public static final ModConfigSpec.IntValue FALL_RESCUE_Y;

    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Hub placement").push("hub");
        SCHEMATIC_FILE = b.comment("File name inside config/hubdimension/ (Sponge schematic: sponge.1, sponge.2 or sponge.3, usually .schem)")
                .define("schematicFile", "hub.schem");
        PASTE_X = b.comment("World position of the schematic's lowest corner")
                .defineInRange("pasteX", 0, -29999999, 29999999);
        PASTE_Y = b.defineInRange("pasteY", 0, -64, 319);
        PASTE_Z = b.defineInRange("pasteZ", 0, -29999999, 29999999);
        AUTO_PASTE = b.comment("Paste the hub automatically the first time someone uses /hub (if it was never pasted in this world)")
                .define("autoPaste", true);
        PASTE_IN_ONE_TICK = b.comment("Recommended: build the whole hub inside ONE server tick (the server freezes for a few seconds, once).",
                        "While the hub is half built nothing can tick, so Create pipes, clutches and belts all see the finished hub.",
                        "If false, blocksPerTick is used and the server keeps running while the hub is built.")
                .define("pasteInOneTick", true);
        BLOCKS_PER_TICK = b.comment("How many blocks are placed per server tick while pasting (lower = less lag, slower paste)")
                .defineInRange("blocksPerTick", 10000, 100, 2000000);
        SPAWN_X = b.comment("Where /hub teleports players to").defineInRange("spawnX", 41.5, -3.0E7, 3.0E7);
        SPAWN_Y = b.defineInRange("spawnY", 15.25, -64.0, 320.0);
        SPAWN_Z = b.defineInRange("spawnZ", 36.5, -3.0E7, 3.0E7);
        SPAWN_YAW = b.comment("Direction players look when arriving (0 = south, 90 = west, 180 = north, -90 = east)")
                .defineInRange("spawnYaw", 0.0, -360.0, 360.0);
        b.pop();

        b.comment("Sky of the hub dimension").push("sky");
        TIME_MODE = b.comment("STATIC = always the same time, FOLLOW_OVERWORLD = same day/night cycle as the overworld")
                .defineEnum("timeMode", TimeMode.STATIC);
        STATIC_TIME = b.comment("Time of day when timeMode = STATIC (0 sunrise, 6000 noon, 12000 sunset, 18000 midnight)")
                .defineInRange("staticTime", 12500, 6000, 24000);
        WEATHER_MODE = b.comment("CLEAR / RAIN / THUNDER = fixed, FOLLOW_OVERWORLD = same weather as the overworld")
                .defineEnum("weatherMode", WeatherMode.CLEAR);
        b.pop();

        b.comment("Rules inside the hub").push("rules");
        PROTECT_BLOCKS = b.comment("Nobody can break or place blocks").define("protectBlocks", true);
        BLOCK_ITEM_USE = b.comment("Items can not be used ON blocks (stops wrenches, flint and steel, placing...). Levers, buttons, Create controls etc. still work with an empty hand.")
                .define("blockItemUseOnBlocks", true);
        LOCK_CONTAINERS = b.comment("Players can not open chests, barrels, furnaces, hoppers, lecterns etc.")
                .define("lockContainers", false);
        NO_DAMAGE = b.comment("Players take no damage").define("noDamage", true);
        NO_HUNGER = b.comment("Players never get hungry").define("noHunger", true);
        PROTECT_ENTITIES = b.comment("Players can not hit entities (item frames, paintings, armor stands, other players)")
                .define("protectEntities", true);
        NO_MOB_SPAWNING = b.comment("No mob can enter the dimension (natural spawns, spawn eggs, /summon, command blocks)")
                .define("noMobSpawning", true);
        NO_EXPLOSIONS = b.comment("Explosions do nothing").define("noExplosions", true);
        ALLOW_LIGHTNING = b.comment("Allow lightning bolts (they can start fires)").define("allowLightning", false);
        FREEZE_PASTED_MOBS = b.comment("Mobs that come with the schematic (parrots...) get no AI, so they stay where you put them")
                .define("freezePastedMobs", true);
        OPS_BYPASS = b.comment("Operators in creative mode ignore the protection rules (so you can edit the hub)")
                .define("opsBypass", true);
        FALL_RESCUE_Y = b.comment("Players who fall below this Y level are teleported back to the hub spawn")
                .defineInRange("fallRescueY", -20, -64, 319);
        b.pop();

        SPEC = b.build();
    }
}
