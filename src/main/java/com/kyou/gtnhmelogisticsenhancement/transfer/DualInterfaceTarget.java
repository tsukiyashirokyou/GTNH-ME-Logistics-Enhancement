package com.kyou.gtnhmelogisticsenhancement.transfer;

import net.minecraft.tileentity.TileEntity;

import appeng.tile.misc.TileInterface;

/** Shared, fail-closed target check without linking the optional AE2 Fluid Crafting implementation. */
public final class DualInterfaceTarget {

    private static final String TILE_CLASS = "com.glodblock.github.common.tile.TileFluidInterface";

    private DualInterfaceTarget() {}

    /** Accept only the native block ME Dual Interface, excluding ordinary interfaces and cable parts. */
    public static TileInterface resolve(final TileEntity tile) {
        return tile instanceof TileInterface && TILE_CLASS.equals(
            tile.getClass()
                .getName())
            && !tile.isInvalid() ? (TileInterface) tile : null;
    }
}
