package com.kyou.gtnhmelogisticsenhancement.transfer;

import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.me.storage.CellInventory;

/** Native non-void cell capability, including ThE's CellInventory subclass; never trust a display/class name. */
final class SourceCellAccess {

    private SourceCellAccess() {}

    static boolean canTransferFrom(final IMEInventory<?> inventory) {
        if (!(inventory instanceof ICellInventoryHandler)) {
            return false;
        }
        final ICellInventoryHandler<?> cell = (ICellInventoryHandler<?>) inventory;
        return cell.getCellInv() instanceof CellInventory && !cell.getCellInv()
            .isOverflow()
            && cell.getCellInv()
                .getStoredItemTypes() >= 0
            && cell.getCellInv()
                .getStoredItemTypes() <= 63
            && cell.getCellInv()
                .getStoredItemCount() >= 0;
    }

    static void validateSnapshot(final IAEStackType<?> type, final Iterable<?> stacks) {
        int count = 0;
        long total = 0;
        for (final Object entry : stacks) {
            if (++count > 63 || !(entry instanceof IAEStack)) {
                throw new IllegalStateException("Invalid native cell enumeration");
            }
            final IAEStack<?> stack = (IAEStack<?>) entry;
            if (stack.getStackType() != type || stack.getStackSize() < 0) {
                throw new IllegalStateException("Invalid native cell enumeration");
            }
            total = Math.addExact(total, stack.getStackSize());
        }
    }
}
