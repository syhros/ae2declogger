package com.syhros.ae2declogger;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
//#if MC >= 26.1
//$$ import net.minecraft.resources.Identifier;
//$$ import net.minecraft.server.permissions.Permissions;
//#else
import net.minecraft.resources.ResourceLocation;
//#endif
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

    /** "minecraft:overworld" for a dimension key. */
    public static String dimensionId(ResourceKey<Level> dimension) {
        //#if MC >= 26.1
        //$$ return dimension.identifier().toString();
        //#else
        return dimension.location().toString();
        //#endif
    }

    /** The dimension key for an id like "minecraft:the_nether", or null when the id is malformed. */
    @Nullable
    public static ResourceKey<Level> dimension(@Nullable String id) {
        //#if MC >= 26.1
        //$$ Identifier parsed = id == null ? null : Identifier.tryParse(id);
        //#else
        ResourceLocation parsed = id == null ? null : ResourceLocation.tryParse(id);
        //#endif
        return parsed == null ? null : ResourceKey.create(DIMENSIONS, parsed);
    }

    public static Level level(Entity entity) {
        //#if MC >= 1.20
        return entity.level();
        //#else
        //$$ return entity.getLevel();
        //#endif
    }

    /** A chat line, or the line above the hotbar when {@code overlay}. */
    public static void message(Player player, Component text, boolean overlay) {
        //#if MC >= 26.1
        //$$ if (overlay) {
        //$$     player.sendOverlayMessage(text);
        //$$ } else {
        //$$     player.sendSystemMessage(text);
        //$$ }
        //#else
        player.displayClientMessage(text, overlay);
        //#endif
    }

    public static String name(Player player) {
        //#if MC >= 26.1
        //$$ return player.getGameProfile().name();
        //#else
        return player.getGameProfile().getName();
        //#endif
    }

    public static MinecraftServer server(ServerPlayer player) {
        //#if MC >= 26.1
        //$$ return player.level().getServer();
        //#elseif MC >= 1.20
        return player.server;
        //#else
        //$$ return player.server;
        //#endif
    }

    /** Operators (permission level 2, like /gamemode). */
    public static boolean isOperator(ServerPlayer player) {
        //#if MC >= 26.1
        //$$ return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        //#else
        return player.hasPermissions(2);
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
