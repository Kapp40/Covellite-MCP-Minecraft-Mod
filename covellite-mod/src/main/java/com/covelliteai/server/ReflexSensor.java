package com.covelliteai.server;

import covellite.agent.reflex.ReflexInputs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Reads the world into {@link ReflexInputs}. Does not decide anything: the
 * reflex rules stay pure and testable in the agent module.
 */
public final class ReflexSensor {

    private static final int WATER_SCAN_DEPTH = 6;
    private static final int TNT_SCAN_RADIUS = 10;

    private ReflexSensor() {
    }

    public static ReflexInputs sense(ServerPlayer player) {
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        ReflexInputs.Builder inputs = ReflexInputs.builder()
                .health(player.getHealth())
                .food(player.getFoodData().getFoodLevel())
                .air(player.getAirSupply());

        inputs.lavaAdjacent(anyAdjacentLava(level, feet));
        Direction facing = Direction.getNearest(player.getLookAngle().x, 0.0D,
                player.getLookAngle().z);
        inputs.lavaAhead(lavaAt(level, feet.relative(facing))
                || lavaAt(level, feet.relative(facing, 2)));

        HitResult hit = player.pick(5.0D, 0.0F, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos target = ((BlockHitResult) hit).getBlockPos();
            inputs.lavaBelowTarget(lavaAt(level, target.below()));
            inputs.lavaAboveTarget(lavaAt(level, target.above()));
        }

        // Only a moving companion can walk off a ledge; standing still is safe.
        boolean moving = player.getDeltaMovement().horizontalDistanceSqr() > 0.01D;
        inputs.solidAhead(!moving || solidGround(level, feet.relative(facing).below()));

        inputs.fall(player.fallDistance, hasWaterBucket(player), waterBelow(level, feet));

        inputs.levitating(player.hasEffect(MobEffects.LEVITATION));
        inputs.freezing(player.isInPowderSnow || player.getTicksFrozen() > 0);
        inputs.inCobweb(level.getBlockState(feet).is(Blocks.COBWEB)
                || level.getBlockState(feet.above()).is(Blocks.COBWEB));
        inputs.fallingBlockAbove(fallingBlockAbove(level, feet));
        inputs.primedTnt(nearestTnt(player));
        return inputs.build();
    }

    private static boolean lavaAt(Level level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.LAVA)
                || level.getFluidState(pos.above()).is(FluidTags.LAVA);
    }

    private static boolean anyAdjacentLava(Level level, BlockPos feet) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (lavaAt(level, feet.relative(direction))) {
                return true;
            }
        }
        return false;
    }

    private static boolean solidGround(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.isSolid();
    }

    private static boolean fallingBlockAbove(Level level, BlockPos feet) {
        for (int dy = 1; dy <= 4; dy++) {
            BlockState state = level.getBlockState(feet.above(dy));
            if (state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWaterBucket(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Items.WATER_BUCKET)) {
                return true;
            }
        }
        return false;
    }

    /** Blocks to fall before hitting water, or 99 when there is none below. */
    private static int waterBelow(Level level, BlockPos feet) {
        for (int dy = 1; dy <= WATER_SCAN_DEPTH; dy++) {
            BlockPos pos = feet.below(dy);
            if (level.getFluidState(pos).is(FluidTags.WATER)) {
                return dy;
            }
            if (solidGround(level, pos)) {
                return 99;
            }
        }
        return 99;
    }

    private static int nearestTnt(ServerPlayer player) {
        int best = 999;
        for (PrimedTnt tnt : player.level().getEntitiesOfClass(PrimedTnt.class,
                player.getBoundingBox().inflate(TNT_SCAN_RADIUS))) {
            int distance = (int) Math.round(player.distanceTo(tnt));
            if (distance < best) {
                best = distance;
            }
        }
        return best;
    }
}
