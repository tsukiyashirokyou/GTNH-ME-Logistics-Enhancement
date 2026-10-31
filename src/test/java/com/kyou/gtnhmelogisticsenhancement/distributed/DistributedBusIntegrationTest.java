package com.kyou.gtnhmelogisticsenhancement.distributed;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collection;

import net.minecraft.launchwrapper.LaunchClassLoader;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Pinned GT/AE classes under Forge's loader. Does not start Forge's mod lifecycle or an AE world. */
@RunWith(Parameterized.class)
public class DistributedBusIntegrationTest {

    private static LaunchClassLoader loader;
    private static Class<?> fixture;

    @Parameterized.Parameter
    public String scenario;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> scenarios() {
        return Arrays.asList(
            new Object[][] { { "mteInstancesAndTransactionsAreIndependent" }, { "genericBusHazardsAreDisabled" },
                { "twoUnfilteredBussesRoutePastFullFirstBus" },
                { "invertedNativeCellFiltersAndGtPriorityRouteBothBusOrders" },
                { "fortyTickScheduleDoesNotBankIdleTime" }, { "completePartialAndRejectedInsertionConserveStock" },
                { "outputAmountsNeverExceedIntBridge" }, { "fiveThousandEmergencyIdentitiesRotateInBoundedBatches" },
                { "uncertainOutputCannotReplayAcrossSaveReload" }, { "badRemainderNeverDebitsAndPersistsEvidence" },
                { "synchronousRemovalDisarmsOldBusAndPreservesJournal" },
                { "cardCopyCapacityAndStoredContentsStayUnchanged" },
                { "nativeRestrictionAndPreciseFiltersSurviveReloadBeforeInput" },
                { "nativeAdvancedAndExtremeFiniteCellsAreAccepted" },
                { "invalidCardsAreNotExpansionAndNeverTrimStock" }, { "mteMachineItemNbtPreservesCardAndAllStock" },
                { "safeRecipeRejectsEveryPayloadBearingIngredient" },
                { "corruptEnvelopeIsRetainedAndNeverBecomesUsableEmptyStock" },
                { "configurationBoundsAndTypeLimitRetainLegacyStock" }, { "totalBeyondLongAndEmergencyPagesAreExact" },
                { "nativePoweredInsertSettlesOnlyAcceptedItemsAndHonorsAvailablePower" },
                { "rawInvalidReceiptIsCaughtBeforePoweredInsertMasksIt" },
                { "corruptCardInventoryAndProxyDataRemainPreserved" },
                { "fixedIdConflictDoesNotOverwriteExistingMachine" },
                { "nativeFuzzyOreAndInverterPoliciesUseCopiedCards" },
                { "realMuiPanelHasReadOnlyStockAndServerOnlyViewSync" },
                { "capacityCardSlotUsesStableFacadeAndNativePolicies" },
                { "nativeGtWrenchRecognizesDriveAndPickaxeFallbackRemainsAvailable" } });
    }

    @BeforeClass
    public static void bootstrap() throws Exception {
        loader = new LaunchClassLoader(
            ((URLClassLoader) DistributedBusIntegrationTest.class.getClassLoader()).getURLs());
        loader.addClassLoaderExclusion("org.junit.");
        loader.addClassLoaderExclusion("org.hamcrest.");
        fixture = loader.loadClass("com.kyou.gtnhmelogisticsenhancement.distributed.DistributedBusFixture");
        fixture.getMethod("registries")
            .invoke(null);
    }

    @AfterClass
    public static void close() throws Exception {
        if (loader != null) loader.close();
        loader = null;
        fixture = null;
    }

    @Test
    public void nativeScenario() throws Throwable {
        try {
            fixture.getMethod(this.scenario)
                .invoke(
                    fixture.getDeclaredConstructor()
                        .newInstance());
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
