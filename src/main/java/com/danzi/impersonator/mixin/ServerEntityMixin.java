package com.danzi.impersonator.mixin;

import com.danzi.impersonator.ImpersonatorMod;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerEntity.class)
abstract class ServerEntityMixin {
    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "addPairing", at = @At("HEAD"))
    private void impersonator$spoofPlayerInfo(ServerPlayer viewer, CallbackInfo ci) {
        if (this.entity instanceof ServerPlayer target) {
            ImpersonatorMod.beforePairing(viewer, target);
        }
    }
}
