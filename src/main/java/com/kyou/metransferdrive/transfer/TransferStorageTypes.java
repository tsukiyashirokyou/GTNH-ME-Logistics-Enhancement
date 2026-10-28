package com.kyou.metransferdrive.transfer;

import java.util.function.Function;

import appeng.api.storage.data.AEStackTypeRegistry;
import appeng.api.storage.data.IAEStackType;

/** Optional registered types only: no Thaumic Energistics classes are linked on either side. */
public final class TransferStorageTypes {

    private TransferStorageTypes() {}

    public static long batchLimit(final IAEStackType<?> type) {
        return batchLimit(type, AEStackTypeRegistry::getType);
    }

    /** The registry lookup is supplied separately so policy tests do not bootstrap Forge's native stack singletons. */
    static long batchLimit(final IAEStackType<?> type, final Function<String, IAEStackType<?>> registered) {
        if (type == null || registered.apply(type.getId()) != type) {
            return 0;
        }
        switch (type.getId()) {
            case "item":
            case "fluid":
                // Not a throughput quota: external vanilla container bridges cannot represent larger offers safely.
                return TransferEnergy.MAX_AMOUNT;
            case "essentia":
                // ThE 1.7.64-GTNH: native aspect units, one unit per AE energy operation.
                return type.getAmountPerUnit() == 1 ? TransferEnergy.MAX_AMOUNT : 0;
            default:
                return 0;
        }
    }
}
