package com.faz.hubdimension;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 *  /hub         - go to the hub (everyone)
 *  /hub back    - go back to where you were before (everyone)
 *  /hub paste   - (re)paste the schematic (op level 2)
 */
public final class HubCommands {
    private HubCommands() {}

    private static final String RETURN_KEY = "hubdimension_return";

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("hub")
                        .executes(HubCommands::goToHub)
                        .then(Commands.literal("back").executes(HubCommands::goBack))
                        .then(Commands.literal("paste")
                                .requires(src -> src.hasPermission(2))
                                .executes(HubCommands::paste)));
    }

    private static int goToHub(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        MinecraftServer server = src.getServer();

        ServerLevel hub = server.getLevel(HubDimension.HUB);
        if (hub == null) {
            src.sendFailure(Component.literal("The hub dimension does not exist (mod data not loaded?)."));
            return 0;
        }
        if (HubPaster.isRunning()) {
            src.sendFailure(Component.literal("The hub is being built right now - try again in a moment."));
            return 0;
        }
        if (!HubState.isPasted(server)) {
            if (!HubConfig.AUTO_PASTE.get()) {
                src.sendFailure(Component.literal("The hub has not been built yet. Ask an operator to run /hub paste."));
                return 0;
            }
            String error = HubPaster.start(hub);
            if (error != null) {
                src.sendFailure(Component.literal(error));
            } else {
                src.sendFailure(Component.literal("The hub is being built for the first time - try /hub again in a moment."));
            }
            return 0;
        }

        // remember where the player came from
        if (!HubEvents.isHub(player.level())) {
            CompoundTag saved = new CompoundTag();
            saved.putString("dim", player.level().dimension().location().toString());
            saved.putDouble("x", player.getX());
            saved.putDouble("y", player.getY());
            saved.putDouble("z", player.getZ());
            saved.putFloat("yaw", player.getYRot());
            player.getPersistentData().put(RETURN_KEY, saved);
        }

        player.teleportTo(hub,
                HubConfig.SPAWN_X.get(), HubConfig.SPAWN_Y.get(), HubConfig.SPAWN_Z.get(),
                HubConfig.SPAWN_YAW.get().floatValue(), 0.0F);
        HubEvents.syncSky(player);
        return 1;
    }

    private static int goBack(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        MinecraftServer server = src.getServer();

        if (!HubEvents.isHub(player.level())) {
            src.sendFailure(Component.literal("You are not in the hub."));
            return 0;
        }

        // default: world spawn in the overworld
        ServerLevel target = server.overworld();
        BlockPos spawn = target.getSharedSpawnPos();
        double x = spawn.getX() + 0.5, y = spawn.getY(), z = spawn.getZ() + 0.5;
        float yaw = 0.0F;

        CompoundTag saved = player.getPersistentData().getCompound(RETURN_KEY);
        if (saved.contains("dim")) {
            ResourceLocation id = ResourceLocation.tryParse(saved.getString("dim"));
            ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            if (level != null) {
                target = level;
                x = saved.getDouble("x");
                y = saved.getDouble("y");
                z = saved.getDouble("z");
                yaw = saved.getFloat("yaw");
            }
        }

        player.teleportTo(target, x, y, z, yaw, 0.0F);
        return 1;
    }

    private static int paste(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel hub = src.getServer().getLevel(HubDimension.HUB);
        if (hub == null) {
            src.sendFailure(Component.literal("The hub dimension does not exist."));
            return 0;
        }
        String error = HubPaster.start(hub);
        if (error != null) {
            src.sendFailure(Component.literal(error));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Pasting the hub... (see the server log for progress)"), true);
        return 1;
    }
}
