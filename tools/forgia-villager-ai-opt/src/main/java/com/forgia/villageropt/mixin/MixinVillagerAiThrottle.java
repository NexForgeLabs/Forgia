package com.forgia.villageropt.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Villager.class, remap = false)
public abstract class MixinVillagerAiThrottle {
    @Shadow
    private int updateMerchantTimer;

    @Unique
    private int forgia$trappedCheckTick = -40;
    @Unique
    private boolean forgia$trappedCached;
    @Unique
    private int forgia$playerCheckTick = -20;
    @Unique
    private boolean forgia$playerNearbyCached = true;

    @Inject(method = "customServerAiStep", at = @At("HEAD"), cancellable = true)
    private void forgia$throttleVillagerAi(CallbackInfo ci) {
        Villager villager = (Villager) (Object) this;
        if (villager.isTrading() || villager.hurtTime > 0 || this.updateMerchantTimer > 0) {
            return;
        }
        int tick = villager.tickCount;
        boolean boxedOrMounted = villager.isPassenger();
        if (!boxedOrMounted) {
            if (tick - this.forgia$trappedCheckTick >= 40) {
                this.forgia$trappedCheckTick = tick;
                this.forgia$trappedCached = forgia$isBoxedIn(villager);
            }
            boxedOrMounted = this.forgia$trappedCached;
        }
        if (boxedOrMounted) {
            if (((tick + villager.getId()) & 7) != 0) {
                ci.cancel();
            }
            return;
        }
        if (tick - this.forgia$playerCheckTick >= 20) {
            this.forgia$playerCheckTick = tick;
            this.forgia$playerNearbyCached = villager.level().getNearestPlayer(villager, 12.0) != null;
        }
        if (!this.forgia$playerNearbyCached && ((tick + villager.getId()) & 3) != 0) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean forgia$isBoxedIn(Villager villager) {
        Level level = villager.level();
        BlockPos pos = villager.blockPosition();
        BlockPos above = pos.above();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos sideLow = pos.relative(dir);
            BlockPos sideHigh = above.relative(dir);
            boolean lowBlocked = !level.getBlockState(sideLow).getCollisionShape(level, sideLow).isEmpty();
            boolean highBlocked = !level.getBlockState(sideHigh).getCollisionShape(level, sideHigh).isEmpty();
            if (!lowBlocked && !highBlocked) {
                return false;
            }
        }
        return true;
    }
}
