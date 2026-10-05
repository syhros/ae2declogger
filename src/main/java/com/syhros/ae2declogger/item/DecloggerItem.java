package com.syhros.ae2declogger.item;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.server.DecloggerServer;
import com.syhros.ae2declogger.server.GridSource;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import appeng.api.networking.GridHelper;

/**
 * The AE2 Declogger.
 * <ul>
 * <li>Right-click an ME block: link to that network and open the monitor.</li>
 * <li>Right-click air: open the monitor for the linked network.</li>
 * <li>Sneak + right-click a block: read-only machine viewer for that block.</li>
 * <li>Sneak + right-click air: switch stall alerts on or off.</li>
 * </ul>
 */
public class DecloggerItem extends Item {

    private static final String TAG_LINK = "Link";
    private static final String TAG_DIM = "Dim";
    private static final String TAG_POS = "Pos";
    private static final String TAG_SIDE = "Side";
    private static final String TAG_ALERTS = "Alerts";

    public DecloggerItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (player == null) {
            return InteractionResult.PASS;
        }
        BlockPos pos = context.getClickedPos();
        if (player.isShiftKeyDown()) {
            if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel) {
                DecloggerServer.requestMachine(serverPlayer, level.dimension(), pos, true, true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (GridHelper.getNodeHost(level, pos) == null) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
            Direction side = context.getClickedFace();
            GridSource.Resolved resolved = GridSource.resolveAt(serverLevel, pos, side);
            if (resolved.error() != null) {
                serverPlayer.displayClientMessage(resolved.error().copy().withStyle(ChatFormatting.RED), true);
            } else {
                setLink(stack, GlobalPos.of(level.dimension(), pos), side);
                serverPlayer.displayClientMessage(Component.translatable("ae2declogger.item.linked",
                        level.getBlockState(pos).getBlock().getName(), pos.getX(), pos.getY(), pos.getZ())
                        .withStyle(ChatFormatting.GREEN), true);
                DecloggerServer.openMonitor(serverPlayer, sourceOf(stack, level.getBlockState(pos).getBlock().getName()));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            if (player.isShiftKeyDown()) {
                boolean enabled = !alertsEnabled(stack);
                setAlerts(stack, enabled);
                serverPlayer.displayClientMessage(Component.translatable(
                        enabled ? "ae2declogger.item.alerts_on" : "ae2declogger.item.alerts_off")
                        .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
            } else if (getLink(stack) == null) {
                serverPlayer.displayClientMessage(
                        Component.translatable("ae2declogger.error.not_linked").withStyle(ChatFormatting.RED), true);
            } else {
                DecloggerServer.openMonitor(serverPlayer, sourceOf(stack, null));
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        GlobalPos link = getLink(stack);
        if (link == null) {
            tooltip.add(Component.translatable("ae2declogger.item.tooltip.unlinked").withStyle(ChatFormatting.GRAY));
        } else {
            BlockPos p = link.pos();
            tooltip.add(Component.translatable("ae2declogger.item.tooltip.linked", p.getX(), p.getY(), p.getZ(),
                    link.dimension().location().toString()).withStyle(ChatFormatting.AQUA));
        }
        tooltip.add(Component.translatable(alertsEnabled(stack) ? "ae2declogger.item.tooltip.alerts_on"
                : "ae2declogger.item.tooltip.alerts_off").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("ae2declogger.item.tooltip.use1").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("ae2declogger.item.tooltip.use2").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("ae2declogger.item.tooltip.use3").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return getLink(stack) != null;
    }

    // ---- NBT ----

    @Nullable
    public static GlobalPos getLink(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_LINK)) {
            return null;
        }
        CompoundTag link = tag.getCompound(TAG_LINK);
        ResourceLocation dim = ResourceLocation.tryParse(link.getString(TAG_DIM));
        if (dim == null || !link.contains(TAG_POS)) {
            return null;
        }
        return GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(link.getLong(TAG_POS)));
    }

    @Nullable
    public static Direction getLinkSide(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_LINK)) {
            return null;
        }
        CompoundTag link = tag.getCompound(TAG_LINK);
        if (!link.contains(TAG_SIDE)) {
            return null;
        }
        int side = link.getByte(TAG_SIDE);
        return side >= 0 && side < Direction.values().length ? Direction.from3DDataValue(side) : null;
    }

    public static void setLink(ItemStack stack, GlobalPos link, @Nullable Direction side) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_DIM, link.dimension().location().toString());
        tag.putLong(TAG_POS, link.pos().asLong());
        if (side != null) {
            tag.putByte(TAG_SIDE, (byte) side.get3DDataValue());
        }
        stack.getOrCreateTag().put(TAG_LINK, tag);
    }

    /** Alerts are on until switched off. */
    public static boolean alertsEnabled(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null || !tag.contains(TAG_ALERTS) || tag.getBoolean(TAG_ALERTS);
    }

    public static void setAlerts(ItemStack stack, boolean enabled) {
        stack.getOrCreateTag().putBoolean(TAG_ALERTS, enabled);
    }

    public static GridSource sourceOf(ItemStack stack, @Nullable Component blockName) {
        GlobalPos link = getLink(stack);
        BlockPos p = link == null ? BlockPos.ZERO : link.pos();
        Component title = Component.translatable("ae2declogger.monitor.title_linked",
                blockName != null ? blockName : Component.translatable("ae2declogger.monitor.network"), p.getX(),
                p.getY(), p.getZ());
        return GridSource.ofLink(link, getLinkSide(stack), title);
    }

    /** First linked Declogger in the player's hands or inventory, or EMPTY. */
    public static ItemStack findLinked(Player player, boolean requireAlerts) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            if (isUsable(held, requireAlerts)) {
                return held;
            }
        }
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isUsable(stack, requireAlerts)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean isUsable(ItemStack stack, boolean requireAlerts) {
        return stack.getItem() instanceof DecloggerItem && getLink(stack) != null
                && (!requireAlerts || alertsEnabled(stack));
    }
}
