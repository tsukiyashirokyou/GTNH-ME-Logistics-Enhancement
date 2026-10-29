package com.kyou.gtnhmelogisticsenhancement.distributed;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collection;

import net.minecraft.launchwrapper.LaunchClassLoader;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Unmodified GT helpers under Forge's loader; no GT mod lifecycle, worlds or controllers run. */
@RunWith(Parameterized.class)
public class NativeOutputIntegrationTest {

    private static LaunchClassLoader loader;
    private static Class<?> fixture;

    @Parameterized.Parameter
    public String scenario;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> scenarios() {
        return Arrays.asList(
            new Object[][] { { "nativeItemRecipeCheckAcceptsWholeOverageWithoutChangingStock" },
                { "nativeItemActualParallelAndDuplicateIdentityCommitWholeBatch" },
                { "nativeFluidRecipeCheckAndActualLargeChunksAgree" },
                { "allFourVoidModeFlagPairsUseNativeHelperProtectionAndKeepOtherProducts" },
                { "helpersTryOtherOutputsAfterOnePartitionRejects" },
                { "actualInterfacesPassFlagsAndPreventSimulationCommitAndRepeatedCommit" },
                { "nativeIdentitiesAndNbtKeepItemMetaAndBothKindsOfTagsSeparate" },
                { "callerNbtMutationDoesNotChangeStoredIdentityOrMergePartitions" },
                { "rejectedAndSimulatedDirectCallsKeepRealStockAndPreserveRemainders" },
                { "twoRealPrechecksCanPassButNativeCompletionCannotGuaranteeSharedDelivery" },
                { "nativeHelpersRespectOneAndSixteenPartitionsAndNeverCreateTheNextIdentity" },
                { "sharedSameIdentityAndFluidPrechecksCannotReserveFutureOutput" },
                { "failedGtCommitKeepsEvidenceAndCompleteNormalizesQuantityBearingIds" },
                { "invalidNativeIdentityNbtCannotTurnIntoDefaultRegistryStock" },
                { "emergencyActualReceiptNeverAuthorizesRecipeChecksOrUnprotectedDirectCalls" },
                { "nativeProtectedReceiptHandlesSixtyFourKindsAndCountsAllOfThemInPrechecks" },
                { "nativeEmergencyFluidChunksAndDuplicatesSurviveLongNbtRoundTrip" },
                { "nativeRecipeToCompletionSurvivesCapacityAndFilterChangesWithoutTokenOrMixin" },
                { "nativeTwoHelpersCanStageBeforeEitherCommitAndRebaseCompleteDeltas" },
                { "protectedActualDrainsAndPromotesEmergencyTypesUsingTheCommonOutputView" },
                { "actualPhaseFlagsCannotBeChangedAfterAnyInventoryDecision" },
                { "actualHelperOverflowPreservesStockAndNonMaterializedFailureEvidence" },
                { "duplicateOutputEndpointReferenceDoesNotReplayCommittedStock" },
                { "mixedNonAwareOutputKeepsItsNativeInterfaceContractAndCapacity" } });
    }

    @BeforeClass
    public static void openIsolatedRegistry() throws Exception {
        final URL[] urls = ((URLClassLoader) NativeOutputIntegrationTest.class.getClassLoader()).getURLs();
        loader = new LaunchClassLoader(urls);
        loader.addClassLoaderExclusion("org.junit.");
        loader.addClassLoaderExclusion("org.hamcrest.");
        fixture = loader.loadClass("com.kyou.gtnhmelogisticsenhancement.distributed.NativeOutputFixture");
        fixture.getMethod("registries")
            .invoke(null);
    }

    @AfterClass
    public static void closeIsolatedRegistry() throws Exception {
        if (loader != null) loader.close();
        fixture = null;
        loader = null;
    }

    @Test
    public void pinnedHelperScenario() throws Throwable {
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
