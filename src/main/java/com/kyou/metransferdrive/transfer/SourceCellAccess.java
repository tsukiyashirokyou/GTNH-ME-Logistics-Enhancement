package com.kyou.metransferdrive.transfer;

import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.IMEInventory;
import appeng.me.storage.CellInventory;

/** Bounds source enumeration and ensures a rollback cannot silently void its remainder. */
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
                .getStoredItemTypes() <= 63;
    }
}
