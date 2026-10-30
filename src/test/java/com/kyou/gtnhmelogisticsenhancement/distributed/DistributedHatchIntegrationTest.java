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

/** Executes fixed GT helpers/wrappers, AE2FC cells and MUI2 in isolation; never boots a game/world. */
@RunWith(Parameterized.class)
public class DistributedHatchIntegrationTest {

    private static LaunchClassLoader loader;
    private static Class<?> fixture;
    @Parameterized.Parameter
    public String scenario;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> scenarios() {
        return Arrays.asList(
            new Object[][] { { "hatchesOwnIndependentStateAndUseStandardParent" }, { "defaultConfigurationAndBounds" },
                { "singleTankContainerAndNeighbourPathsAreDisabled" }, { "recipeCheckIsStrictAndWholeBatchIsSoft" },
                { "sharedN1ProtectedRacesRetainBothIdentities" }, { "sharedQuotaProtectedRaceMerges2350" },
                { "nativeHelperMergesDuplicatesAndIntMaxSegments" }, { "fourVoidingModesPropagateFluidProtection" },
                { "directFillAndStoreSimulationNeverGrantEmergency" },
                { "layerWrapperRejectsOtherLayersEvenProtected" }, { "layerWrapperForwardsFlagsAndIntChunks" },
                { "staleCommitRevalidatesNCapacityAndFilter" },
                { "finiteNativeFluidCellsUse2048WithoutUnitConversion" }, { "effectiveQuotasUseNativeFluidTypeLimits" },
                { "fluidOreUpgradeKeepsNativePreciseAndInverterFilters" }, { "preexistingCardContentsRemainUntouched" },
                { "nativeFiltersInverterAndRestrictionsUseCopies" }, { "nonFiniteForeignAndDamagedCardsAreRejected" },
                { "shrinkingPolicyRetainsOldStockAndAllowsDrain" }, { "nativePoweredInsertUsesFluidEnergyUnits" },
                { "distributedEnergySharesBudgetBetweenItemsAndFluids" },
                { "distributedEnergyChargesActualReceiptsAndPreservesStock" },
                { "distributedEnergyChecksTopologyAndSynchronousReentry" },
                { "distributedEnergyRenewsOnServerTickAndRetainsUncertainFlight" },
                { "rawInvalidReceiptsAndFluidNbtChangesAreIsolated" },
                { "partialRefusalAndRecoveryConserveQuantities" }, { "fortyTickTimerAndIntBridgeRemainBounded" },
                { "allEmergencyIdentitiesRotateAndCursorSurvivesReload" },
                { "uncertainOutputJournalNeverReplaysAfterReload" }, { "synchronousRemovalAndTransactionsAreIsolated" },
                { "machineItemPreservesCardAndAllFluidStock" }, { "corruptNbtAndForeignSingleTankRemainEvidence" },
                { "nodeAndBrokenInventoryEvidenceSurviveReload" }, { "realMuiPanelIsReadOnlyAndServerValidated" },
                { "guiReusesIdentityIconsAndClearsEmptyPositions" },
                { "capacityCardSlotUsesFluidPolicyAndLockedState" },
                { "safeHatchRecipeRejectsPayloadsAndCreatesFreshOutput" }, { "fixedFluidIdConflictNeverOverwrites" },
                { "realOrdinaryHatchAndDistributedHatchKeepSeparateTransactions" },
                { "unrepresentableNbtNeverDispatchesAndDoesNotStarveOtherFluids" },
                { "readonlyFluidViewPreservesLongCountsAndDisplayNbt" } });
    }

    @BeforeClass
    public static void bootstrap() throws Exception {
        loader = new LaunchClassLoader(
            ((URLClassLoader) DistributedHatchIntegrationTest.class.getClassLoader()).getURLs());
        loader.addClassLoaderExclusion("org.junit.");
        loader.addClassLoaderExclusion("org.hamcrest.");
        fixture = loader.loadClass("com.kyou.gtnhmelogisticsenhancement.distributed.DistributedHatchFixture");
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
