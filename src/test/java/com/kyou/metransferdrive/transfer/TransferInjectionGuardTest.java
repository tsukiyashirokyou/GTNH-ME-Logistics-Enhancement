package com.kyou.metransferdrive.transfer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class TransferInjectionGuardTest {

    @Test
    public void scopeBlocksOnlyTheOriginAndPreservesNestedScopes() {
        final Object first = new Object();
        final Object second = new Object();
        TransferInjectionGuard.call(first, () -> {
            assertTrue(TransferInjectionGuard.blocks(first));
            assertFalse(TransferInjectionGuard.blocks(second));
            TransferInjectionGuard.call(second, () -> {
                assertTrue(TransferInjectionGuard.blocks(first));
                assertTrue(TransferInjectionGuard.blocks(second));
                return null;
            });
            assertTrue(TransferInjectionGuard.blocks(first));
            assertFalse(TransferInjectionGuard.blocks(second));
            return null;
        });
        assertFalse(TransferInjectionGuard.blocks(first));
    }

    @Test
    public void exceptionCannotLeakAGuardIntoNormalStorageWrites() {
        final Object origin = new Object();
        try {
            TransferInjectionGuard.call(origin, () -> { throw new IllegalStateException("Injection failed"); });
            fail("Expected injection failure");
        } catch (final IllegalStateException expected) {
            assertFalse(TransferInjectionGuard.blocks(origin));
        }
    }
}
