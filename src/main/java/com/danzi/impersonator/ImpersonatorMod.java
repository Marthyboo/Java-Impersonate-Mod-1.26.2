package com.danzi.impersonator;

import com.google.common.collect.HashMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.danzi.impersonator.mixin.ClientboundPlayerInfoUpdatePacketAccessor;
import com.danzi.impersonator.mixin.ChunkMapAccessor;
import com.danzi.impersonator.mixin.ChunkMapTrackedEntityAccessor;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ImpersonatorMod implements ModInitializer {
    public static final String MOD_ID = "impersonator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Map<UUID, GameProfile> ACTIVE_IMPERSONATIONS = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> dispatcher.register(
            Commands.literal("impersonate")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes(context -> impersonate(context, StringArgumentType.getString(context, "player"))))
        ));

        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> dispatcher.register(
            Commands.literal("unimpersonate")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .executes(ImpersonatorMod::unimpersonate)
        ));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> syncViewer(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ACTIVE_IMPERSONATIONS.remove(handler.player.getUUID()));
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    private static int impersonate(CommandContext<CommandSourceStack> context, String requestedName) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("Resolving " + requestedName + "..."), false);

        CompletableFuture
            .supplyAsync(() -> resolveProfile(server, requestedName), Util.ioPool())
            .whenComplete((resolvedProfile, throwable) -> server.execute(() -> {
                ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(player.getUUID());

                if (onlinePlayer == null) {
                    return;
                }

                if (throwable != null) {
                    LOGGER.error("Failed to resolve profile for {}", requestedName, throwable);
                    source.sendFailure(Component.literal("Could not resolve " + requestedName + ". Check the spelling or your network."));
                    return;
                }

                if (resolvedProfile.isEmpty()) {
                    source.sendFailure(Component.literal("No Java profile found for " + requestedName + "."));
                    return;
                }

                GameProfile alias = resolvedProfile.get();
                ACTIVE_IMPERSONATIONS.put(player.getUUID(), alias);
                refreshViewers(player);

                source.sendSuccess(() -> Component.literal(
                    "You are now impersonating " + alias.name() + "."
                ), true);
            }));

        return 1;
    }

    private static int unimpersonate(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        boolean removed = clear(player);

        if (!removed) {
            context.getSource().sendFailure(Component.literal("You are not impersonating anyone."));
            return 0;
        }

        context.getSource().sendSuccess(() -> Component.literal("Impersonation cleared."), true);
        return 1;
    }

    private static Optional<GameProfile> resolveProfile(MinecraftServer server, String requestedName) {
        try {
            ServerPlayer onlineTarget = server.getPlayerList().getPlayerByName(requestedName);

            if (onlineTarget != null) {
                return Optional.of(enrichProfile(server, onlineTarget.getGameProfile()));
            }

            Optional<GameProfile> resolved = server.services().profileResolver().fetchByName(requestedName);
            return resolved.map(profile -> enrichProfile(server, profile));
        } catch (Exception exception) {
            LOGGER.error("Profile lookup failed for {}", requestedName, exception);
            return Optional.empty();
        }
    }

    private static GameProfile enrichProfile(MinecraftServer server, GameProfile profile) {
        if (profile.id() == null || profile.properties().containsKey("textures")) {
            return profile;
        }

        try {
            ProfileResult result = server.services().sessionService().fetchProfile(profile.id(), true);
            if (result != null && result.profile() != null) {
                return result.profile();
            }
        } catch (Exception exception) {
            LOGGER.warn("Could not fetch textures for {}", profile.name(), exception);
        }

        return profile;
    }

    public static void beforePairing(ServerPlayer viewer, ServerPlayer target) {
        if (viewer.getUUID().equals(target.getUUID()) || !ACTIVE_IMPERSONATIONS.containsKey(target.getUUID())) {
            return;
        }

        sendVisiblePlayerInfo(viewer, target);
    }

    private static void syncViewer(ServerPlayer viewer) {
        MinecraftServer server = viewer.level().getServer();
        if (server == null) {
            return;
        }

        for (UUID disguisedPlayerId : ACTIVE_IMPERSONATIONS.keySet()) {
            ServerPlayer target = server.getPlayerList().getPlayer(disguisedPlayerId);
            if (target != null && !target.getUUID().equals(viewer.getUUID())) {
                sendVisiblePlayerInfo(viewer, target);
            }
        }
    }

    private static boolean clear(ServerPlayer player) {
        GameProfile removed = ACTIVE_IMPERSONATIONS.remove(player.getUUID());

        if (removed != null) {
            refreshViewers(player);
        }

        return removed != null;
    }

    private static void refreshViewers(ServerPlayer target) {
        MinecraftServer server = target.level().getServer();
        if (server == null) {
            return;
        }

        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (!viewer.getUUID().equals(target.getUUID())) {
                sendVisiblePlayerInfo(viewer, target);
            }
        }

        ServerEntity tracker = getTracker(target);
        if (tracker == null) {
            return;
        }

        for (ServerPlayer viewer : getTrackedViewers(target)) {
            tracker.removePairing(viewer);
            tracker.addPairing(viewer);
        }
    }

    private static void sendVisiblePlayerInfo(ServerPlayer viewer, ServerPlayer target) {
        viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(target.getUUID())));
        viewer.connection.send(createPlayerInfoPacket(target, currentVisibleProfile(target)));
    }

    private static GameProfile currentVisibleProfile(ServerPlayer target) {
        GameProfile aliasProfile = ACTIVE_IMPERSONATIONS.get(target.getUUID());
        if (aliasProfile == null) {
            return target.getGameProfile();
        }

        return new GameProfile(target.getUUID(), aliasProfile.name(), copyProperties(aliasProfile.properties()));
    }

    private static PropertyMap copyProperties(PropertyMap properties) {
        return new PropertyMap(HashMultimap.create(properties));
    }

    private static ClientboundPlayerInfoUpdatePacket createPlayerInfoPacket(ServerPlayer target, GameProfile visibleProfile) {
        ClientboundPlayerInfoUpdatePacket packet = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(target));
        ClientboundPlayerInfoUpdatePacket.Entry entry = packet.entries().get(0);
        ClientboundPlayerInfoUpdatePacket.Entry updatedEntry = new ClientboundPlayerInfoUpdatePacket.Entry(
            entry.profileId(),
            visibleProfile,
            entry.listed(),
            entry.latency(),
            entry.gameMode(),
            entry.displayName(),
            entry.showHat(),
            entry.listOrder(),
            entry.chatSession()
        );

        ((ClientboundPlayerInfoUpdatePacketAccessor) packet).impersonator$setEntries(List.of(updatedEntry));
        return packet;
    }

    private static ServerEntity getTracker(ServerPlayer player) {
        Object trackedEntity = ((ChunkMapAccessor) player.level().getChunkSource().chunkMap).impersonator$getEntityMap().get(player.getId());
        if (trackedEntity == null) {
            return null;
        }

        return ((ChunkMapTrackedEntityAccessor) trackedEntity).impersonator$getServerEntity();
    }

    private static Set<ServerPlayer> getTrackedViewers(ServerPlayer player) {
        Set<ServerPlayer> viewers = new LinkedHashSet<>();
        Object trackedEntity = ((ChunkMapAccessor) player.level().getChunkSource().chunkMap).impersonator$getEntityMap().get(player.getId());

        if (trackedEntity == null) {
            return viewers;
        }

        for (ServerPlayerConnection connection : ((ChunkMapTrackedEntityAccessor) trackedEntity).impersonator$getSeenBy()) {
            viewers.add(connection.getPlayer());
        }

        return viewers;
    }
}
