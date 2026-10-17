package com.faz.hubdimension;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import net.neoforged.fml.ModList;
import java.io.InputStream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point. The dimension itself is defined by the JSON files in
 * src/main/resources/data/hubdimension/ - this class only wires up config, events and commands.
 */
@Mod(HubDimension.MOD_ID)
public class HubDimension {
    public static final String MOD_ID = "hubdimension";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Key of the hub dimension = data/hubdimension/dimension/hub.json */
    public static final ResourceKey<Level> HUB =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(MOD_ID, "hub"));

    public HubDimension(IEventBus modEventBus, ModContainer modContainer) {
        // creates <world>/serverconfig/hubdimension-server.toml (per world; defaults for new worlds: defaultconfigs/hubdimension-server.toml)
        modContainer.registerConfig(ModConfig.Type.SERVER, HubConfig.SPEC);

        // creates config/hubdimension/ - put your schematic costum schematic in here, if you dont, the default will be loaded
        try {
            Files.createDirectories(schematicFolder());
            if (hasSchematic()) {
                LOGGER.info("Schematic folder already contains a .schem file - not copying the default hub.");
            } else {
                copyDefaultSchematic();
            }
        } catch (IOException e) {
            LOGGER.error("Could not prepare schematic folder", e);
        }

        // register all static @SubscribeEvent methods of these classes
        NeoForge.EVENT_BUS.register(HubEvents.class);
        NeoForge.EVENT_BUS.register(HubCommands.class);
    }

    public static Path schematicFolder() {
        return FMLPaths.CONFIGDIR.get().resolve(MOD_ID);
    }

    private static boolean hasSchematic() throws IOException {
        try (var files = Files.list(schematicFolder())) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".schem"));
        }
    }

    /** Copies the hub that ships inside the mod jar to config/hubdimension/hub.schem. */
    public static boolean copyDefaultSchematic() {
        try {
            Path folder = schematicFolder();
            Files.createDirectories(folder);
            Path target = folder.resolve("hub.schem");
            if (Files.exists(target)) {
                LOGGER.info("{} already exists - not overwriting it.", target);
                return false;
            }

            InputStream stream = HubDimension.class.getResourceAsStream("/defaults/hub.schem");
            if (stream == null) {
                // fallback: ask the mod loader for the file inside our own jar
                var modFile = ModList.get().getModFileById(MOD_ID);
                if (modFile != null) {
                    Path inJar = modFile.getFile().findResource("defaults", "hub.schem");
                    if (Files.exists(inJar)) stream = Files.newInputStream(inJar);
                }
            }
            if (stream == null) {
                LOGGER.warn("The default hub was not found inside the mod jar (expected defaults/hub.schem).");
                return false;
            }
            try (InputStream in = stream) {
                Files.copy(in, target);
            }
            LOGGER.info("Copied the default hub schematic to {}", target);
            return true;
        } catch (Exception e) {
            LOGGER.error("Could not copy the default hub schematic", e);
            return false;
        }
    }

}
