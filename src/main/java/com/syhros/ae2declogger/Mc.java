package com.syhros.ae2declogger;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
//#if MC >= 1.19.3
import net.minecraft.core.registries.Registries;
//#endif

/** Small Minecraft calls that are spelled differently between versions. */
public final class Mc {

    /** The dimension registry key, for reading and writing dimension ids. */
    //#if MC >= 1.19.3
    public static final ResourceKey<Registry<Level>> DIMENSIONS = Registries.DIMENSION;
    //#else
    //$$ public static final ResourceKey<Registry<Level>> DIMENSIONS = Registry.DIMENSION_REGISTRY;
    //#endif

    private Mc() {
    }

    public static ResourceKey<Level> dimension(ResourceLocation id) {
        return ResourceKey.create(DIMENSIONS, id);
    }

    public static Level level(Entity entity) {
        //#if MC >= 1.20
        return entity.level();
        //#else
        //$$ return entity.getLevel();
        //#endif
    }

    /** How far the player reaches for blocks (for "the ME block you look at"). */
    public static double reach(ServerPlayer player) {
        //#if MC >= 1.20.5
        //$$ return player.blockInteractionRange();
        //#elseif MC >= 1.20
        return player.getBlockReach();
        //#else
        //$$ return player.isCreative() ? 5.0 : 4.5;
        //#endif
    }
}
