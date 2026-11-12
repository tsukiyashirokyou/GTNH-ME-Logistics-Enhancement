package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.Random;

import org.junit.Test;

public class MEEnergyLossAccountTest {

    private static final BigInteger NINETEEN = BigInteger.valueOf(19);
    private static final BigInteger TWENTY = BigInteger.valueOf(20);

    @Test
    public void inverseAndForwardMatchIndependentBigIntegerReference() {
        final Random random = new Random(0x1920);
        for (int credit = 0; credit < 19; credit++) {
            final MEEnergyLossAccount account = new MEEnergyLossAccount(credit);
            for (int sample = 0; sample < 300; sample++) {
                final long gross = sample < 22 ? sample
                    : sample == 22 ? Long.MAX_VALUE : random.nextLong() & Long.MAX_VALUE;
                final long maximum = account.maximumNet(gross);
                assertEquals(
                    BigInteger.valueOf(gross)
                        .multiply(NINETEEN)
                        .add(BigInteger.valueOf(credit))
                        .divide(TWENTY),
                    BigInteger.valueOf(maximum));
                for (final long net : new long[] { 0, maximum / 2, maximum }) {
                    final BigInteger cost = referenceCost(net, credit);
                    assertEquals(cost.longValueExact(), account.grossCost(net));
                    assertEquals(
                        cost.multiply(NINETEEN)
                            .add(BigInteger.valueOf(credit))
                            .subtract(
                                BigInteger.valueOf(net)
                                    .multiply(TWENTY))
                            .intValueExact(),
                        account.creditAfter(net));
                    assertTrue(account.grossCost(net) <= gross);
                }
                assertTrue(referenceCost(maximum + 1, credit).compareTo(BigInteger.valueOf(gross)) > 0);
                assertEquals(credit, account.credit()); // All preceding calls are simulation.
            }
        }
    }

    @Test
    public void splitAndBatchedPaymentsHaveTheSameLongTermCostAcrossRestore() {
        final Random random = new Random(95);
        MEEnergyLossAccount account = new MEEnergyLossAccount();
        BigInteger delivered = BigInteger.ZERO;
        BigInteger paid = BigInteger.ZERO;
        for (int sample = 0; sample < 10000; sample++) {
            final long net = random.nextInt(1000000);
            final long gross = account.grossCost(net);
            account.record(net, gross);
            delivered = delivered.add(BigInteger.valueOf(net));
            paid = paid.add(BigInteger.valueOf(gross));
            assertEquals(
                BigInteger.valueOf(account.credit()),
                paid.multiply(NINETEEN)
                    .subtract(delivered.multiply(TWENTY)));
            assertTrue(account.credit() >= 0 && account.credit() < 19);
            if (sample == 4999) account = new MEEnergyLossAccount(account.credit());
        }
        assertEquals(
            delivered.multiply(TWENTY)
                .add(BigInteger.valueOf(18))
                .divide(NINETEEN),
            paid);
    }

    @Test
    public void nineteenSingleEuDeliveriesCostTwentyAndZeroDoesNothing() {
        final MEEnergyLossAccount account = new MEEnergyLossAccount();
        long paid = 0;
        for (int index = 0; index < 19; index++) {
            final long cost = account.grossCost(1);
            paid += cost;
            account.record(1, cost);
            if (index == 0) {
                assertEquals(2, cost);
                assertEquals(18, account.credit());
            }
        }
        assertEquals(20, paid);
        assertEquals(0, account.credit());
        account.record(0, 0);
        assertEquals(0, account.credit());
        assertEquals(0, new MEEnergyLossAccount(18).maximumNet(0));
    }

    @Test
    public void invalidOrUnrepresentablePaymentCannotMutateCredit() {
        for (final int value : new int[] { -1, 19, Integer.MAX_VALUE }) {
            assertThrows(IllegalArgumentException.class, () -> new MEEnergyLossAccount(value));
        }
        final MEEnergyLossAccount account = new MEEnergyLossAccount(7);
        assertThrows(IllegalArgumentException.class, () -> account.maximumNet(-1));
        assertThrows(IllegalArgumentException.class, () -> account.grossCost(-1));
        assertThrows(IllegalArgumentException.class, () -> account.creditAfter(-1));
        assertThrows(IllegalArgumentException.class, () -> account.record(19, 19));
        assertThrows(ArithmeticException.class, () -> account.grossCost(Long.MAX_VALUE));
        assertThrows(ArithmeticException.class, () -> account.record(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(7, account.credit());
    }

    private static BigInteger referenceCost(final long net, final int credit) {
        return BigInteger.valueOf(net)
            .multiply(TWENTY)
            .subtract(BigInteger.valueOf(credit))
            .add(BigInteger.valueOf(18))
            .divide(NINETEEN);
    }
}
