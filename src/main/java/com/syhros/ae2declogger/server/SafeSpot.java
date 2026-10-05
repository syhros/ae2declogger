package com.syhros.ae2declogger.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** Finds a place next to a machine where a player can stand without suffocating. */
final class SafeSpot {

    private SafeSpot() {
    }

    static BlockPos find(ServerLevel level, BlockPos machine) {
        // Make sure the chunk is there before looking at blocks.
        level.getChunkAt(machine);
        for (int radius = 1; radius <= 4; radius++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        BlockPos feet = machine.offset(dx, dy, dz);
                        if (canStand(level, feet)) {
                            return feet;
                        }
                    }
                }
            }
        }
        BlockPos up = machine.above();
        while (up.getY() < level.getMaxBuildHeight() - 2 && !canStandIgnoringFloor(level, up)) {
            up = up.above();
        }
        return up;
    }

    private static boolean canStand(ServerLevel level, BlockPos feet) {
        if (!level.isInWorldBounds(feet) || !canStandIgnoringFloor(level, feet)) {
            return false;
        }
        BlockPos floor = feet.below();
        return level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
    }

    private static boolean canStandIgnoringFloor(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getFluidState(feet).isEmpty();
    }
}
