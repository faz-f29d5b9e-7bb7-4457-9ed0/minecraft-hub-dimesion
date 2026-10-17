package com.faz.hubdimension;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Remembers (per world) whether the hub was already pasted - a tiny flag file in the world folder. */
public final class HubState {
    private HubState() {}

    private static Path flag(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("hubdimension_pasted.flag");
    }

    public static boolean isPasted(MinecraftServer server) {
        return Files.exists(flag(server));
    }

    public static void markPasted(MinecraftServer server) {
        try {
            Files.writeString(flag(server), "pasted");
        } catch (IOException e) {
            HubDimension.LOGGER.error("Could not write hub flag file", e);
        }
    }
}
