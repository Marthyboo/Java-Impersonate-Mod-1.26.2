package com.danzi.impersonator.mixin;

import java.util.Set;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface ChunkMapTrackedEntityAccessor {
    @Accessor("serverEntity")
    ServerEntity impersonator$getServerEntity();

    @Accessor("seenBy")
    Set<ServerPlayerConnection> impersonator$getSeenBy();
}

