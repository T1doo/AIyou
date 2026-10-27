#!/usr/bin/env python3
"""Require real, successful test XML instead of treating NO-SOURCE as a pass."""
from pathlib import Path
import argparse
import hashlib
import json
import math
import os
import subprocess
import xml.etree.ElementTree as ET


def verify_transcript_fontscale(folder: Path, head: str) -> dict:
    """Join real independent-window samples; labels and larger PNGs are not font evidence."""
    if len(head) != 40 or any(c not in "0123456789abcdef" for c in head):
        raise ValueError("Invalid font-evidence source SHA")
    samples, files = {}, []
    for label, scale in (("normal", 1.0), ("double", 2.0)):
        path = folder / f"editor-fontscale-{label}.json"
        raw = path.read_bytes()
        if not 0 < len(raw) <= 4096:
            raise ValueError("Missing or oversized font measurement")
        value = json.loads(raw)
        if value.get("source_sha") != head or value.get("font_case") != label:
            raise ValueError("Font measurement source/case mismatch")
        sample = value["measurement"]
        for key in ("configurationFontScale", "layoutFontScale"):
            if type(sample[key]) not in (int, float) or sample[key] != scale:
                raise ValueError("Dialog did not consume the requested platform font scale")
        for key in ("density", "firstLineHeightPx"):
            if type(sample[key]) not in (int, float) or not math.isfinite(sample[key]) or sample[key] <= 0:
                raise ValueError("Invalid real font metric")
        for key in ("layoutWidthPx", "layoutHeightPx", "lineCount"):
            if type(sample[key]) is not int or sample[key] <= 0:
                raise ValueError("Empty text layout")
        digest = sample["text_sha256"]
        if not isinstance(digest, str) or len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
            raise ValueError("Missing exact input-text identity")
        if sample["hasVisualOverflow"] is not False:
            raise ValueError("Real editor paragraph overflowed")
        samples[label] = sample
        files.append({"path": path.name, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()})
    normal, large = samples["normal"], samples["double"]
    if normal["density"] != large["density"] or normal["text_sha256"] != large["text_sha256"]:
        raise ValueError("Font comparison changed pixel density or input text")
    if large["firstLineHeightPx"] <= normal["firstLineHeightPx"]:
        raise ValueError("Actual editor text did not grow")
    receipt = {"source_sha": head, "kind": "Paired production Dialog measurements on JVM; not device evidence",
               "normal": normal, "double": large, "input_files": files}
    (folder / "editor-fontscale.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--component", action="store_true", help="also require actual component regression XML")
parser.add_argument("--queue", action="store_true", help="also require actual host queue regression XML")
parser.add_argument("--ui", action="store_true", help="also require actual Compose interaction/semantics regression XML on the JVM simulator")
parser.add_argument("--trace", action="store_true", help="also require actual test-only native observer regression XML")
parser.add_argument("--evidence", action="store_true", help="require production pre-key evidence regression XML")
args = parser.parse_args()
targets = [("domain", "core/domain/build/test-results/test", 3)]
if args.component:
    targets.append(("component", "core/designsystem/build/test-results/testDebugUnitTest", 7))
if args.queue:
    targets.append(("host queue", "checks/record-queue/build/test-results/test", 148))
if args.ui:
    targets.append(("Compose JVM interaction", "app/build/test-results/testDebugUnitTest", 189))
if args.trace:
    targets.append(("native trace observer", "checks/native-trace/build/test-results/test", 8))
if args.evidence:
    targets.append(("pre-open evidence", "core/data/build/test-results/testDebugUnitTest", 21))
for label, folder, minimum in targets:
    reports = list((root / folder).glob("TEST-*.xml"))
    if not reports:
        raise SystemExit(f"FAIL: no {label} test reports found")
    total = failures = errors = skipped = cases = 0
    observed_cases = []
    child_failures = False
    for report in reports:
        suite = ET.parse(report).getroot()
        total += int(suite.get("tests", 0))
        failures += int(suite.get("failures", 0))
        errors += int(suite.get("errors", 0))
        skipped += int(suite.get("skipped", 0))
        actual = list(suite.iter("testcase"))
        observed_cases.extend(actual)
        cases += len(actual)
        child_failures |= any(any(case.find(tag) is not None for tag in ("failure", "error", "skipped")) for case in actual)
    if total < minimum or cases != total or failures or errors or skipped or child_failures:
        raise SystemExit(f"FAIL: {label} tests={total} actual={cases} failures={failures} errors={errors} skipped={skipped}")
    print(f"PASS: {label} tests={total} actual={cases} failures=0 errors=0 skipped=0")
    additional = {}
    if label == "host queue":
        additional["com.t1doo.aiyou.records.QuestionKeywordLimitsTest"] = (
            "actualPublicationRejectsReentrantNavigationInputBeforeAcquiringNavigation",
            "hostilePasteSupplementary512AndTruncationStayBoundedWithoutLosingTheQuestion",
            "eightWordsAndSixtyFourCodepointsIncludeSpacesAndNeverSilentlyTruncate",
            "questionQuotesNegationAndNewVocabularyAreSourceFragmentsWithoutSynonyms",
            "actualFallbackLocationEntersNavigationBeforeThePrivateSearchMutex")
        additional["com.t1doo.aiyou.records.FocusedImagePanelLifecycleTest"] = (
            "exactFocusedImageRejectsOtherViewsAndImageWritesAndRetiresBeforeOpeningB",
            "lateFocusedAImageReadCannotPublishOverBAndItsAuthenticatedBytesAreWiped")
        additional["com.t1doo.aiyou.records.FocusedAudioPanelLifecycleTest"] = (
            "retiringFocusedCandidateStopsAcceptedPlaybackWithoutFakingGateReleaseAndAllowsExactB",
            "lateFocusedARefreshCannotRefillOrBlockBAndOldRetirementCannotClearB")
        additional["com.t1doo.aiyou.ui.AppAccessPreferenceOwnerTest"] = (
            "retiredBlockedEnableCannotLeaveNewOwnerOpenWithDisabledSnapshot",)
        additional["com.t1doo.aiyou.ui.AppAccessPreferenceOperationTest"] = (
            "retiredOwnerQueuedBehindAnotherOperationCannotWriteAfterItsOwnershipWasRevoked",
            "failedAcknowledgementAndReadRemainRecoverableByFreshOwnerWithoutRepeatingTheWrite")
        additional["com.t1doo.aiyou.ui.AppAccessControllerTest"] = (
            "disabledFirstStartOpensOnlyAfterConfirmedReadAndCloseBarrier",
            "enabledStartupCancelMissingCredentialAndForeignResultsNeverOpenRecords",
            "enableAndDisableRequireFreshAuthenticationAndExactReadbackBeforeOpeningNewSession",
            "manualLockWaitsForRetiredStorageAndOldSessionCannotLockReopenedRecords",
            "busyDurableBoundaryRejectsManualLockAndPreferenceChangeWithoutLaunchingAuthentication",
            "cancelledSettingsAuthenticationCanExplicitlyReturnToPriorAccessWithoutChangingPreference",
            "lostEnableAcknowledgementFailsClosedAndRetryReadsCommittedPolicyWithoutRepeatingWrite",
            "readFailureAndDisposedOwnerCannotReopenOrAcceptLateAuthentication")
        additional["AppAccessRecordsBoundaryTest"] = (
            "idleRetirementSealsAllAdmissionAndWaitsForActualStorageClosure",
            "queuedAndFailedDraftCannotRetireUntilTheSameInputIsConfirmedDurable",
            "failedStorageClosureCannotBecomeAnAcknowledgedRetiredSession")
        additional["com.t1doo.aiyou.ui.AppAccessRetirementBarrierTest"] = (
            "newAccessOwnerWaitsForPreviousOwnersStorageCloseAcknowledgement",
            "failedCloseSurvivesOwnerDisposalAndCannotBeForgottenByANewController")
        additional["com.t1doo.aiyou.records.OriginalSearchPreviewTest"] = (
            "foldedMatchHighlightsOnlyOriginalCodePointsAndNeverNormalizesOrStoresText",
            "longExactMatchClipsAtCodePointBudgetWithoutHighlightingOmissionMarks",
            "finalSupplementaryMatchAndLeadingWhitespaceUseActualUtf16Boundaries",
            "filterOnlyAndWhitespaceOnlyMatchesNeverInventAHighlightedWord",
            "foreignSnapshotChangedRevisionAndInvalidQuoteOrRangesFailClosed")
        additional["com.t1doo.aiyou.storage.StorageUsageTest"] = (
            "countsActualDatabaseSidecarsCiphertextDraftsAndRecoveryFilesWithoutChangingBytes",
            "freshInstallationReturnsZeroWithoutCreatingRecordsDirectoryOrReadingSiblingKeys",
            "invalidRootAndNestedSymbolicLinkFailInsteadOfReportingZeroOrFollowingOutside",
            "danglingLinkCannotBecomePartialSuccess",
            "entryAndDepthBudgetsRejectPartialTotals",
            "interruptedMeasurementCannotReturnPartialSuccessAndOverflowIsRejected",
            "explicitQueryRunsOnlyOnIoRejectsBusyDuplicateAndRefreshesChangedMeasurement",
            "readFailureHidesPreviousTotalAndExplicitRetryCanConfirmRealZero",
            "navigationDuringAnAlreadyExecutingReadRejectsItsResultAndRetiredCallback",
            "disposalCancelsQueuedIoAndCannotReopenOrPublish")
        additional["OriginalEditRecoveryTest"] = (
            "aiNoneSourceAllowsManualAutosaveAndNewVersionWithoutChangingItsPolicy",)
        additional["com.t1doo.aiyou.records.LocalOriginalSearchTest"] = (
            "manualTagOnlyAndCombinedFiltersFenceTagChangesAndReadFailureBeforeComposition",
            "keywordAndIsLiteralUnicodeLocaleIndependentAndPreservesSnapshotOrderAndPolicy",
            "exactAndFoldedExcerptsRoundTripOriginalCodePointsWithoutChangingCaseOrWhitespace",
            "captureDatesUseSavedIanaZoneInclusiveDaysAndDstWithOpenBounds",
            "invalidConditionsAreDistinctFromNoHitsAndBlankKeywordIsBrowseButExactBlankIsLiteral",
            "latestQueryAndSameQueryAbaRejectOldNonCancellableActualMatchingResults",
            "freshSnapshotFencesRevisionDeletionAndReadFailureBeforeSyncAndRejectsForeignHitIdentity",
            "rejectedCompletedWorkerInvalidatesRunningAndRestoredSnapshotCanFinish",
            "pauseUsesExactSnapshotCacheWithNewGenerationAndClearCannotReuseItsResults",
            "closedOwnerCannotResurrectAndOrdinaryMatcherFailureIsRedactedAndRetryable")
        additional["JpegPayloadValidationTest"] = (
            "baselineProgressiveAndAllExifDirectionsPreserveOriginalByDefault",
            "explicitStrippingKeepsDirectionAndEncodedScansWhileRemovingPrivateExtensions",
            "embeddedThumbnailsUnknownAppsAndCommentsBetweenProgressiveScansAreRemoved",
            "validatedMultipartColorProfileIsPreservedAndInvalidChunksRejected",
            "invalidBoundsHeadersExifOffsetsDirectionsAndTrailingBytesAreRejected",
            "missingScanPayloadIsRejectedByNecessaryBoundWithoutClaimingEntropyCompleteness")
        additional["AttachmentControllerTest"] = (
            "actualEncryptedSaveReadbackAndReopenKeepOriginalRevisionAndCapture",
            "pickerCancelAndOldCallbackCannotWriteOrReplaceNewOwner",
            "cancellationDuringSourceReadClosesStreamWithoutDurableIntent",
            "lateDecodedPictureCannotAttachToReopenedSameRecord",
            "revisionChangeBeforeCommitPreservesSourceAndNeverRebinds",
            "readyReadbackFailureRetriesSameIdWithoutSourceOrCipherRewrite",
            "restartingFailedImportRequiresSameBytesAndStableIntent",
            "corruptCiphertextIsHiddenPreservedAndHasNoOverwriteRetry",
            "invalidMimeHeaderPermissionZeroReadAndOversizeCreateNoIntent",
            "unavailableRecordPreservesPreparedChoiceUntilRecoveryWithoutAnyStoreOperation",
            "committingDisablesCancelCloseAndKeepsActiveSourceUntilAuthentication",
            "disposingDuringEncryptionNeverErasesActiveSourceOrPublishesLateResult",
            "actualViewModelRecoversLateCaptureFailureAndKeepsCaptureWhileImporting",
            "jpegRetainedOriginalHasOneSourceReadAndColdHistoricalBinding",
            "jpegExplicitStripRetriesConfirmationWithSameEncryptedBytesAndNoSourceReopen",
            "jpegLateDecodedSelectionReleasesBitmapAndClearsPreparedCopyWithoutWriting")
        additional["PngPayloadValidationTest"] = (
            "validColorDepthPaletteAndAdam7PassesAreValidatedWithoutChangingSource",
            "crcCorrectCompleteIendCannotHideTruncatedZlibBadAdlerOrTrailingCompressedData",
            "incompleteExcessRowsAndInvalidFiltersAreRejectedWithBoundedInflation",
            "invalidDimensionsDepthCriticalStructureAndUnsupportedMetadataAreRejected")
        additional["HistoricalAttachmentTest"] = (
            "encryptedAttachmentSurvivesEditAndColdHistoricalReadWithoutMigratingToNewVersion",
            "historicalModeRejectsAllImportAndRetryEntrypointsEvenForFailedAttachment",
            "missingWrongDuplicateRevisionOrMissingParentCannotAuthorizeAnyAttachmentOperation",
            "crossRecordOrRevisionRowsAreRejectedBeforeCipherReadAndNeverDisplayed",
            "oldDecodedReadCannotPublishAfterSameRecordChangesVersionAndReopensOldVersion",
            "revokedHistoryRequestDuringAuthenticatedReadClearsBytesAndPublishesNoPicture",
            "historicalReadFailureAndCorruptionPreserveCiphertextAndNeverEnableRepairImport",
            "actualViewModelSaveEditColdHistoryReadKeepsCurrentWriteGateAndBothInputs")
    if label == "Compose JVM interaction":
        additional["com.t1doo.aiyou.MobileNavigationPresentationTest"] = (
            "productionTabsSwipeOnceAtEdgesAndReturnToUnchangedUnsavedDraftWithoutWrites",
            "doubleFontTabsKeepRealLabelsTouchTargetsAndCancelRetiredGesture")
        additional["com.t1doo.aiyou.NotebookToolsPresentationTest"] = (
            "searchPrecedesCollapsedToolsAndDisclosurePreservesInputsFeedbackAndDisabledActions",
            "doubleFontLandscapeKeyboardDisclosureAndSearchRemainReachableWithoutCallingTools")
        additional["com.t1doo.aiyou.MixedNotebookPresentationTest"] = (
            "realNotebookUsesBundledLuluOnlyWhenPreferenceIsReadyAndVisibleWithoutWritingRecords",
            "emptyLandscapeDoubleFontHasReachableStartAndFocusedComposerWithoutWriting",
            "coldRegularFontLandscapeStartsCompactAndRetainsDraftAcrossReadOnlyActions")
        additional["com.t1doo.aiyou.QuestionKeywordPresentationTest"] = (
            "realAppRequiresExplicitDateAndQueriesOnceThenOpensUnchangedNegativeOriginal",
            "restorationAndActualActivityPauseRetireQuestionWithoutSavingOrQuerying",
            "landscapeDoubleFontScrollFocusAndEnterConfirmOnlyAfterValidWholeDateTuple")
        additional["com.t1doo.aiyou.OriginalAudioPresentationTest"] = (
            "narrowActualUiImportsExplicitSyntheticFilePlaysStopsAndSavesOnceThenPlaysExactSavedAttachment",
            "actualDraftRemoveDoesNotChangeTextAndNoAudioIsSavedAfterRemoval")
        additional["com.t1doo.aiyou.SelectedQuestionConsentPresentationTest"] = (
            "narrowDoubleFontSeparateMultiSourceReviewAndExactOriginalJump",
            "withdrawUnknownAckHidesResultAndExplicitRetryNeverExecutesAgain",
            "serviceReplacementRetiresOpenReviewAndDefaultCapabilityNeverOffersSend")
        additional["com.t1doo.aiyou.StorageUsagePresentationTest"] = (
            "explicitQueryAndRefreshShowCategoriesAndRetiredActualButtonCannotQueryNextVisit",
            "narrowDoubleFontFailureNeverShowsZeroAndExplicitRetryCanConfirmEmptyFiles")
        additional["com.t1doo.aiyou.ManualPeoplePresentationTest"] = (
            "manualEditorWaitsForAckKeepsFailedInputAndOnlyConfirmedRemoveChangesNames",
            "explicitPersonAndKeywordSavedZoneDateOpenOriginalAndRetireStalePersonResult",
            "doubleFontEditorKeepsCloseReachableAndPrivateInputOutOfSavedStateWhenSourceFails")
        additional["com.t1doo.aiyou.CurrentImageSearchPresentationTest"] = (
            "narrowDoubleFontProtocolQuestionRequiresSeparateScopeConfirmationAndJumpsToCurrentOriginal",
            "protocolQuestionStopAndServiceReplacementRejectNonCooperativeLateAnswer",
            "productionSelectedExcerptTimelineIsExplicitAndOpensExactOriginalWithFreshConfirmationOnReturn",
            "narrowDoubleFontProductionTimelineKeepsChronologyAndClearsOnSelectionOrInputChange",
            "localEvidenceCardsUseCurrentSourceAndReturnWithoutChangingQuestion",
            "localEvidenceRequiresExplicitSelectionAndReconfirmationAfterExclusion",
            "syntheticSelectedAnswerCitationsOpenOriginalAndReturningRequiresNewConfirmation",
            "narrowDoubleFontSyntheticNoEvidenceKeepsOriginalCardsAndNeverInventsAnswer",
            "syntheticAnswerStopCancelsRequestAndSuppressesLateText",
            "narrowDoubleFontLocalEvidenceSelectionAndConfirmationRemainReachable",
            "narrowDoubleFontEvidenceShowsExplicitNoEvidenceAndKeepsManualListReachable",
            "imageFilterStartsOffCombinesLiteralAndDatesAndClearResetsEveryCondition",
            "narrowDoubleFontFilterIsReachableAndReadFailureCannotBecomeEmptySuccess",
            "gridRequiresExplicitToggleKeepsFailureCardAndOpensOriginalSource",
            "narrowDoubleFontGridToggleAndFailureCardRemainReachableAndClearReturnsToList")
        additional["com.t1doo.aiyou.RecordEventDatePresentationTest"] = (
            "rejectedDateAndZoneAtActualDoubleFontRetireConfirmationUntilExplicitCorrection",
            "narrowDoubleFontManualSetCorrectionAndClearEachRequireVisiblePreview",
            "uncertainSaveRetainsSameIntentAndStaleConfirmCannotWriteAfterNavigation",
            "eventDateBasisCombinesKeywordTagFavoriteAndNeverMatchesUnknownCaptureDate")
        additional["com.t1doo.aiyou.RecordHistoryPackagePresentationTest"] = (
            "explicitHistoryAtDoubleFontPreviewsEveryVersionAndDeletedTextWarningBeforeScopeConfirmation",
            "authenticatedHistoryShowsAllVersionsBeforeSeparateRestoreConfirmationCreatesNewCopy",
            "originalEditScopeDefaultsOffRequiresFullHistoryAndShowsFullDraftBeforeConsentAtDoubleFont",
            "authenticatedOriginalEditsRequireSeparateConfirmationAndPublishRecoveryEntryWithoutSavingOriginal")
        additional["com.t1doo.aiyou.ManualTaskTitlePresentationTest"] = (
            "explicitTitleCorrectionRequiresReviewAndConfirmationWithoutSavingPrivateInput",
            "unconfirmedTitleCanBeCancelledAndUnknownCommitHasNoSuccessUntilExactRetry",
            "sameTitleTasksRejectRetiredDialogAndRouteCallbacks")
        additional["com.t1doo.aiyou.TaskReminderPresentationTest"] = (
            "explicitReminderAndRevocationRequireRealConfirmationWithoutSavingPrivatePlan",
            "dateOnlyAndGlobalReadFailureDisableReminderWithoutChangingOriginalInput",
            "staleDialogAndRouteCallbacksCannotWriteAnotherTaskWithTheSameTitle",
            "dashboardShowsActualDeniedUnknownAndScheduledStatesWithoutPromisingDelivery")
        additional["com.t1doo.aiyou.OriginalActivityExportPresentationTest"] = (
            "taskOnlyDoubleFontPreviewSeparatesKindsAndRequiresPlaintextConsentBeforeSafWithoutSavingPrivateState",
            "formatScopeChangesRetireConsentAndChangedActivitiesNeverLaunchDestinationOrPromoteDraft",
            "activitySafCancelReselectRetiresOldUuidAndDuplicateResultWritesAndReadsOnlyOnce")
        additional["com.t1doo.aiyou.RecordPackageActivityPresentationTest"] = (
            "taskOnlySelectionAtDoubleFontPreviewsEveryKindAndKeepsExplicitScopeGateAndPrivateState",
            "taskRestoreRequiresActualSeparateConfirmationThenUnknownReadbackRetriesWithoutPromotingDraft")
        additional["com.t1doo.aiyou.TaskProposalPresentationTest"] = (
            "narrowDraftColdRestoresInPendingGroupThenActualButtonRequiresSeparateConfirmation",
            "uncertainReadbackRetainsExactConfirmationAndNeverShowsPrematureSuccess",
            "changedSourceHidesQuoteAndOldConfirmationThenAllowsExplicitRejectionWithoutBundleInput",
            "refreshedSourceReadFailureAndOtherOwnerVersionChangeKeepInputsButRevokeUnqualifiedRecoveryReceipt",
            "otherOwnerConfirmationAndSourceRemovalWithdrawRecoveryReceiptInActualDialogWithoutReplacingInput",
            "failedRefreshQueueHidesUnverifiedEmptyAndRetainedDraftBodyUntilReadback")
        additional["com.t1doo.aiyou.SourceTaskPresentationTest"] = (
            "narrowDoubleFontLiteralFlowRequiresSeparateConfirmationAndJumpsToExactOriginal",
            "unknownReadbackHasNoSuccessAndRetriesSameRequestWithoutDuplicatingTask",
            "sourceReadFailureAndRevisionRefreshHideQuoteAndConfirmationButRetainInput",
            "cancelledRestoredAndNavigatedConfirmationsCannotReusePrivateSourceOrInput")
        additional["com.t1doo.aiyou.OriginalEditRecoveryPresentationTest"] = (
            "aiNoneColdDraftRecoveryAllowsExplicitManualSaveWithoutChangingPolicy",
            "aiNoneHistoryRouteCreatesOnlyAnExplicitNewVersionAndKeepsNoInferencePolicy")
        additional["com.t1doo.aiyou.LocalProcessingPresentationTest"] = (
            "actualNarrowDoubleFontDetailRequiresSeparateChoiceAndConfirmationForBothLocalScopes",
            "unknownReadbackHasNoSuccessAndRetryUsesTheIdenticalRequestWithoutAnotherGeneration",
            "cancelRestorationAndNavigationRetirePrivateConfirmationsAndSavedStateContainsNoSource",
            "pictureOnlyOriginalCanChooseNoInferenceWithoutInventingTextOrRemoteOption")
        additional["com.t1doo.aiyou.SourceMemoryInboxPresentationTest"] = (
            "narrowInboxReachesExactOriginalAndRequiresSeparateExistingAcceptanceAtDoubleFont",
            "failedReadAndRestorationHidePrivateResultsAndRetireRetainedPageCallbacks",
            "acceptedMemoriesAreReachableAtDoubleFontAndDisappearOnlyAfterSeparateRevocationConfirmation")
        additional["com.t1doo.aiyou.UnifiedKeywordPresentationTest"] = (
            "currentReadyImageCheckboxFreshReturnRestoreAndClearDoNotWriteAnySource",
            "currentReadyImageDoubleFontCheckboxKeyboardAndExactOriginalStayReachable",
            "originalFavoriteCheckboxFreshReturnRestoreAndClearDoNotWriteAnySource",
            "originalFavoriteDoubleFontCheckboxKeyboardAndExactOriginalStayReachable",
            "manualPersonTagDateIntersectionFreshReturnAndRestorationKeepOriginalAndPrivateIdentity",
            "manualPersonPagedLongMarkerKeyboardIntersectionAndExactOriginalStayReachableAtDoubleFont",
            "manualTagDateIntersectionReturnAndClearUseExactOriginalAndNeverSavePrivateFilter",
            "manualTagChooserPaginationLongValuesAndKeyboardSourceRemainReachableAtDoubleFont",
            "capturedDateErrorCorrectionKeyboardAndClearRangeRemainReachableAtDoubleFont",
            "capturedDateRangeIsPrivateAndRestorationOrOldClickCannotReviveIt",
            "doubleFontFourTypedResultsOpenExactOriginalAndPreferenceThenRequeryWithoutChangingSources",
            "literalNoResultClearAndRestoreNeverSaveQueryOrRetainOldSession",
            "restoredExactImageProofRetiresAPanelBeforeBLookupAndRejectsRetainedAAction",
            "landscapeDoubleFontScrollsToFocusedQuerySearchAndExactSourceWithoutHiddenActions",
            "noteOnlyAcceptedEmotionKeepsSeparateHighlightAndKeyboardReturnsExactQuoteInsteadOfNote",
            "quoteAndNoteCorrectionNeedsSeparateReacceptanceThenWithdrawalRemovesOnlyMemoryHit",
            "restoredAcceptedNoteQueryAndRetainedClickCannotRecoverPrivateProof",
            "memoryScopeOverflowShowsNoPartialResultsThenNarrowQueryRemainsUsable",
        )
        additional["com.t1doo.aiyou.ImageTextPresentationTest"] = (
            "unavailableModelAndAiNoneNeverInferCreateManualReplacementOrChangeOriginal",
            "doubleFontControlledExtractionCorrectionKeywordJumpAndConfirmedDeletionReachExactOriginal",
            "restoredPrivateImageSourceRetiresRetainedAThenBViewerAndLookupCannotDisplayA")
        additional["com.t1doo.aiyou.AudioTranscriptPresentationTest"] = (
            "normalPlatformFontRecordsActualRangeEditorLayoutWithoutSavingPrivateInput",
            "focusedRangeSaveSurvivesPanelRemovalAndRecompositionBeforeActorRelease",
            "focusedWithdrawalSurvivesPanelRemovalAndRecompositionBeforeActorRelease",
            "explicitCloseStillCancelsAnUnstartedFocusedCorrectionAfterPanelRetirement",
            "ownerReplacementStillRetiresPendingFocusedWithdrawalWithoutPublishingIntoNewOwner",
            "explicitHumanRangeAtNormalAndDoubleFontReachesExactFocusedSourceWithoutAutoplay",
            "actualLandscapeWindowKeepsRangeInputAndSaveReachableWithoutChangingTextOrSource",
            "invalidCustomEndpointRemainsVisibleThroughTextEditAndRefreshUntilExplicitCorrection",
            "doubleFontManualCorrectionWithdrawalAndKeywordJumpReachExactAudioWithoutChangingOriginal",
            "unsavedManualTextAndRetiredSessionDoNotSurviveRestorationAsWriteAuthority",
            "recreatedPrivateAudioRouteRetiresRetainedFocusBeforeOpeningOrLocatingAnotherRecord")
        additional["com.t1doo.aiyou.SourceFactPresentationTest"] = (
            "explicitCandidateSeparateAcceptanceCorrectionAndRevocationReachExactOriginalAndUnifiedFact",
            "actualPlatformDoubleFontKeepsEndpointRejectionStickyAndUnknownSameIntentFrozen",
            "acceptedWriteSurvivesQueryRetirementAndActualRootRecompositionWhileActorIsBlocked",
            "realCloseAndOwnerReplacementRejectQueuedOldConfirmationWithoutNewOwnerPublication")
        additional["com.t1doo.aiyou.SourcePreferencePresentationTest"] = (
            "doubleFontPendingSeparateAcceptanceCorrectionSourceJumpAndRevokeCancellationReachProductionRoutes",
            "privateFormRestorationAndClosedConfirmationHaveNoRetainedWriteAuthority",
            "failedGlobalReadHidesCountAndNeverPresentsAnUnverifiedEmptyPage",
            "doubleFontKeywordLookupClearsOldResultsAndOpensHighlightedOriginalWithoutSavingQuery")
        additional["com.t1doo.aiyou.SourceMemoryPresentationTest"] = (
            "explicitPendingAndAcceptanceAreReachableAtDoubleFontAndSourceJumpPreservesOriginal",
            "rejectionCorrectionHistoryAndRevocationSurviveReopeningWithoutAutomaticAcceptance",
            "unknownAckAndRestorationNavigationCannotReusePrivateConfirmationAuthority")
        additional["com.t1doo.aiyou.SourceTagPresentationTest"] = (
            "explicitPreviewConfirmAndManageRemovalAreReachableAtDoubleFontWithoutChangingOriginal",
            "lostReadbackHasNoFalseSuccessAndRetriesTheSameMemoryOnlyRequest",
            "cancelledAndRestoredOrNavigatedConfirmationsCannotReusePrivatePreviewAuthority")
        additional["com.t1doo.aiyou.LocalCandidatePresentationTest"] = (
            "narrowInterruptedBatchWaitsForExplicitSameBatchResumeAndSeparateAcceptance",
            "retainedResumeActionsCannotRestartReopenedOrCancelledBatches",
            "narrowCorrectionFlowPreviewsCancelsReopensAndRequiresASeparateNewAcceptance",
            "correctionConfirmationRestorationAndReopenedTokensCannotExecuteRetainedActions",
            "actualReaderGeneratesPreviewsConfirmsAndReturnsToExactOriginalWithoutTaskSideEffects",
            "lostReadbackShowsUncertainStateAndRetriesTheSameExplicitReviewWithoutFalseEmptyOrSuccess",
            "reopenedSameItemAndRetainedNavigationCallbacksCannotReuseOldConfirmation",
            "narrowDoubleFontKeepsReviewCancelSourceAndCloseReachableWithTouchTargets",
            "restorationDropsPrivateCandidateAndConfirmationStateAndRetiresOldCallbacks")
        additional["com.t1doo.aiyou.RecordFavoritePresentationTest"] = (
            "actualReaderChoiceFavoriteTimelineCombinedLookupAndUnfavoriteReturn",
            "uncertainChoiceHasReachableStableRetryAndNoCrossRecordSuccessNotice",
            "narrowDoubleFontControlIsReachableAndRetainedReaderActionCannotWriteAfterNavigation",
            "restorationKeepsPublicFilterAndDropsPrivateQueryWhileReadFailureCannotShowEmptyFavorites")
        additional["com.t1doo.aiyou.MixedCapturePresentationTest"] = (
            "narrowPreSaveRangeDefaultsLocalAndRetiredActualChoiceCannotAffectReturnedDraft",
            "narrowLostRangeAckKeepsPendingChoiceDisablesSaveAndRetriesExactTransition",
            "explicitReadAfterFailedRangeWriteDoesNotResendAndRetiresOldRetryHandler",
            "actualNowPurePictureHasThumbnailCountRemovalAndSeparateDurableSaveAtLargeFont",
            "removingLastImageRejectsEmptyRecordAndCancelKeepsDraftAndRetiresPicker",
            "failedSaveRetainsTextAndImagesAndPrivateComposerNeverEntersSavedState",
            "newSaveWhileComposerIsScrolledOnlyOffersExplicitJumpAndReaderReturnKeepsNextDraft",
            "lostClearAckHasReachableSameClearRetryAndCannotPromiseContinuedEditingBeforeVerification",
            "viewportAndFontChangesPreserveDraftAndRangeThenSaveOnlyOnExplicitAction",
        )
        additional["com.t1doo.aiyou.FactRecapPresentationTest"] = (
            "recapExactAudioOpensManualEditorAndReturnsToSameWeekWithoutChangingOriginal",
            "restoredRecapAudioProofRetiresRealFocusedPanelAndOldEditorBeforeAnotherSource",
            "actualSettingsPersistWeekAndOpenedRangeKeepsItsOwnChoiceBeforeExplicitGeneration",
            "latePreferenceReadDoesNotRewriteOpenDayAndRetainedOpenUsesLatestConfirmedDefault",
            "actualNarrowDoubleFontRouteShowsOriginalExcerptThenFullTextAndReturnsToSameCard",
            "pureImageCardOpensExactVersionAttachmentAndReturnsWithoutInventingText",
            "failedReadRetryCancelAndChangedSourceHidePrivateResultsAndRejectRetainedClick",
            "explicitWeekZoneEmptyResultPrivateSavedStateAndAccurateBackupSettings",
        )
        additional["com.t1doo.aiyou.FullBackupPresentationTest"] = (
            "settingsNormalBackupEntryReturnsWithExactDraftAndNoImplicitIo",
            "settingsNarrowDoubleFontBackupEntryIsFullyVisibleBeforeAnyScroll",
            "settingsShortLandscapeBackupEntryIsFullyVisibleBeforeAnyScroll",
            "settingsBackupRejectsUnreadyPendingUnknownAndRetiredCallbacks",
            "actualRootFullScopeRequiresExplicitAcceptanceAndMatchingIndependentPasswords",
            "fullRestoreAuthenticatesManifestThenRequiresSeparateEmptyTargetConfirmation",
            "narrowDoubleFontRecreationDropsPasswordsAndNavigationRetiresPickerBeforeRecomposition",
        )
        additional["com.t1doo.aiyou.RecordPackagePresentationTest"] = (
            "actualFullPreviewRequiresScopeAndTwoMatchingSecretInputsBeforeExportPicker",
            "restoreAuthenticatesAndPreviewsBeforeDistinctConfirmationAndNoImplicitWrite",
            "wrongPassphraseShowsFiniteFailureWithoutPreviewOrImport",
            "rawSavedStateAndRecreationNeverContainPassphrasesOriginalsTagsManifestOrPickerIdentity",
            "narrowDoubleFontKeepsConfirmationReachableAndReadFailureHidesOriginalAndSecrets",
            "imageCountBytesAndConfirmationRemainReachableAtTwoHundredPercentFont",
            "imageRestorePreviewRequiresSeparateExplicitConfirmationAtLargeFont",
            "actualNavigationRetiresRetainedPickerAndConfirmBeforeRecompositionWithoutOpeningOrImporting",
        )
        additional["com.t1doo.aiyou.RecordPackagePickerTest"] = (
            "actualLegacySafIntentHasOnlySelectedScopeAndPausedResultIsDeliveredOnce",
            "recreationPersistsOnlyMonotonicCounterNeverAdoptsOldRequestOrMasksHighCodes",
            "exhaustedCorruptAndMissingRestoredCountersFailClosedWithoutLaunching",
            "finalCodeCancellationAndSaveBoundaryNeverReuseCodesOrLaunchAfterStateSaved",
            "destroyedOwnerUnknownReservedResultsAndForeignSchemesNeverOpenOrReachRegistry",
            "selectedContentUriRemainsMemoryOnlyAcrossActualActivitySavedState",
        )
        additional["com.t1doo.aiyou.RecordPackageDocumentTest"] = (
            "actualContentProviderWritesOnlyCiphertextAndReadsEntireSelectedPackageWithoutDeletion",
            "actualProviderDenialAndForeignSchemesPreserveSelectedExistingFile",
        )
        additional["com.t1doo.aiyou.RecycleBinPresentationTest"] = (
            "actualMoveAndExplicitRestoreKeepFullOriginalTagsAndRequireEachConfirmation",
            "loadingEmptyAndFailedReadsAreDistinctAndNeverExposeStaleTrashBody",
            "failedOrLostAcknowledgementKeepsExactTargetForStableRetryWithoutClaimingSuccess",
            "narrowDoubleFontKeepsConfirmCancelAndRestoreReachableAndHidesContentOnReadFailure",
            "retainedReaderAndConfirmationCallbacksRejectNavigationAndNewSessionBeforeRecomposition",
            "rawSavedStateExcludesRecycleOriginalTagsOperationAndConfirmationAndQueuedCloseDoesNotWrite",
            "retainedRestoreButtonForBRejectsAfterSelectingAAndConfirmationNamesTheCurrentTarget",
            "permanentEraseRequiresExplicitConfirmationAndCancelKeepsPrivateStateOutOfSavedStateAtDoubleFont",
            "permanentEraseLostReceiptOffersExplicitRetryWithTheSameIntentAndNoFalseSuccess",
            "rereadPendingErasureDisablesRestoreAndContinuesTheDurableConfirmation",
            "retainedPermanentEraseButtonCannotRetargetAfterSelectionChangesBeforeRecomposition")
        additional["com.t1doo.aiyou.OriginalExportPresentationTest"] = (
            "legacyAndCurrentNullRangeUiNeverClaimsWholeAudioCoverage",
            "explicitHumanTranscriptScopeAtActualDoubleFontRequiresFreshPlaintextConsentAndStaysOutOfSavedState",
            "narrowAudioZipShowsExactSourceAndSeparatePlaintextConsentWithoutOpeningMedia",
            "imageOnlyZipShowsActualAuthenticatedPictureAndRequiresConsentBeforeZipIntent",
            "mixedZipMalformedAuthenticatedOriginalFailsBeforePreviewOrSystemPicker",
            "actualDialogRequiresSelectionPreviewAndFreshPlaintextConsentBeforeSystemDocumentIntent",
            "sourceChangeShowsConflictAndRequiresExplicitNewPreviewWithoutWritingAnyFile",
            "failedPartialFileHasNoAutomaticDeleteAndRequiresSeparateExactTargetConfirmation",
            "narrowDoubleFontKeepsRiskDestinationAndCloseReachableAndHidesPreviewOnReadFailure",
            "actualRegistryRestorationCannotRelaunchOrDeliverOldResultToNewVmUuid",
            "rawSavedStateExcludesSnapshotOriginalTagsConsentAndDestinationAndNavigationRetiresPendingWork")
        additional["com.t1doo.aiyou.PlatformExportDocumentTest"] = (
            "actualContentResolverTruncatesSelectedSyntheticFileAndVerifiesExactUtf8Readback",
            "actualProviderPermissionDenialPreservesExistingFileAndSelectedDeleteIsExplicit",
            "fileAndNetworkSchemesCannotBecomeSelectedDocuments")
        additional["com.t1doo.aiyou.ManualTaskPresentationTest"] = (
            "explicitCreateWaitsForAckKeepsFailedInputThenCompletesAndCancelsOnlyConfirmedTasks",
            "sameTitleTasksUseExactIdentityAndRetainedVersionActionsRejectBeforeRecomposition",
            "lostReadbackAckReinitializationPreservesOneIdentityAndNeverPublishesFalseEmpty",
            "narrowDoubleFontKeepsCreateCloseAndNavigationReachableAndRevokesOldPageCallbacks",
            "rawSavedStateExcludesTitlesAndEditorUndoAndRestorationRevokesOldNavigation",
            "failedCompletionRetainsStableTargetAndInputWithoutClaimingTaskSuccess",
            "explicitDateOnlyCreateModifyPostponeAndClearWaitForAcknowledgement",
            "nonexistentAndRepeatedLocalTimesRequireExplicitCorrectionsAndOffsetChoice",
            "lostDateReadbackKeepsStableRequestAfterCloseAndNeverShowsFalseEmpty",
            "dateGroupsUseChosenZoneAndTerminalStateRetainsItsPlan",
            "narrowDoubleFontDateActionsAndOldBusinessCallbacksRespectCloseAndNavigation",
            "rawSavedStateExcludesPlanFieldsAndRecreationRetiresOldBusinessCallbacks",
            "dashboardSeparatesDraftCountAndCreatingTaskNeverAddsAReminder",
            "landscapeDashboardKeepsCreateCancelAndConfirmedCompletionReachable")
        additional["com.t1doo.aiyou.ManualTagPresentationTest"] = (
            "tagSearchReturnKeepsExactPositionWithExportControlVisible",
            "actualTagEditorWaitsForAcknowledgementKeepsFailedInputAndRemovesOnlyAfterConfirmation",
            "tagOnlyAndKeywordSavedZoneDateCombinationOpenFullOriginalAndReturnActualListPosition",
            "changedTagVersionRevokesOldResultBeforeCompositionAndReadFailureIsDistinctFromEmpty",
            "narrowLargeFontEditorKeepsActionsReachableAndHidesPersistedLabelsWhenSourceFails",
            "rawSavedStateAndRestorationExcludeTagConditionsInputsAndClearedFieldUndoHistory")
        additional["com.t1doo.aiyou.AppAccessPreferenceTest"] = (
            "replacementOwnerReadsCommittedRealFileAfterRetiredBlockingEnableCompletes",
            "confirmedSettingSurvivesNewInstancesAndStaleWriterCannotDisableIt",
            "malformedOversizedAndIncompleteSettingFailClosedAndPreserveTheirBytes",
            "atomicRecoveryKeepsConfirmedEnabledBackupAndVersionOverflowCannotOverwriteIt")
        additional["com.t1doo.aiyou.AppAccessPresentationTest"] = (
            "lockedDoubleFontGateKeepsOriginalAbsentUntilCurrentSystemResultAndRejectsEarlierResult",
            "actualSettingsRequireVerificationRespectBusyBoundaryAndRetireCallbacksAcrossPageAndLock",
            "uncertainPreferenceWriteKeepsActualProtectedTreeAbsentUntilExplicitReadAndNewVerification")
        additional["com.t1doo.aiyou.AppAccessStartupPresentationTest"] = (
            "productionLockedStartupAndRecreationNeverOpenRecordsOrExposeTheirSurface",)
        additional["com.t1doo.aiyou.OriginalReadingPriorityPresentationTest"] = (
            "normalReaderPrioritizesOriginalAndAttachmentsBeforeDisclosedTools",
            "narrowDoubleFontReaderKeepsFirstLineAndPrimaryActionsBeforeScrolling",
            "shortLandscapeReaderKeepsFirstLineAndPrimaryActionsBeforeScrolling",
            "toolsRetireAcrossCloseReopenEntryOwnerFailureAndObservedAba",
            "toolsPreserveExactHighlightSelectionAndScrollWhileChangedSourceFailsClosed")
        additional["com.t1doo.aiyou.LocalOriginalSearchPresentationTest"] = (
            "emptyAdvancedFiltersUseRealKeyboardDisclosureWithoutQueryOrInputReplacement",
            "pendingPersonInputAndReadErrorsKeepAdvancedFiltersVisibleUntilExplicitClear",
            "searchCardsHighlightVerifiedOriginalUnicodeKeepOrderAndClearWithoutTouchingSource",
            "searchCardFirstHitIsVisibleAtDoubleFontAndRevisionDeletionRetireItsHighlightAndAction",
            "editableKeywordExactAndSavedZoneDateFieldsOpenEveryOriginalCharacterWithoutTouchingDraft",
            "readerReturnKeepsActualMemoriesListIndexAndOffsetWhenPaddingShowsPreviousItem",
            "narrowLargeFontSeparatesNoHitsInvalidAndFailedDatesAndKeepsCorrectionAndClearReachable",
            "retainedResultActionsRejectQueryAbaRevisionDeletionReadFailureAndPageLeave",
            "compositionRestorationDropsSensitiveQueryDatesAndResultsAndRevokesOldActions",
            "longOriginalTailHitScrollsToExactUnicodeRangeAndChangedVersionDropsOldHighlight",
            "indexPendingAndFailureKeepLiteralResultsAndExplicitRebuildControlHonest",
            "indexedLocationExitFailureRetiresActualReaderAndKeepsCurrentSearchInput",
            "indexedLocationExitFailureCannotRevertActualLaterDestinationNavigation")
        additional["com.t1doo.aiyou.ProductionTasksPresentationTest"] = (
            "localRecordsNeverBecomeTasksAndUnopenedCompletionIsExplicit",
            "shortNarrowDoubleFontTasksScrollAndSettingsReturnWithoutChangingDraft",
            "shortLandscapeKeepsTaskStatusNavigationAndSettingsReachable")
        additional["com.t1doo.aiyou.ModelConnectionSettingsPresentationTest"] = (
            "sameOwnerCompositionReplacementRetainsFormAndUnconfirmedRequestButRetiresOldActions",
            "narrowDoubleFontConfiguresBothProtocolsAndRetiredPageActionsCannotSaveOrDelete",
            "invalidRootAndUncertainSaveKeepInputAndRetryOnlyTheSameConfiguration")
        additional["com.t1doo.aiyou.ModelConnectionSecretsPresentationTest"] = (
            "narrowDoubleFontExplicitSaveReplaceAndRevokeKeepCapabilitiesUnknownAndRetireOldActions",
            "passwordNeverEntersSavedStateAndRestorationOrOwnerReplacementRetiresInputCallbacks",
            "uncertainWriteRetainsOnlySameOpaqueOperationAcrossRecreationAndConflictRequiresReload")
        additional["com.t1doo.aiyou.ModelCapabilitiesPresentationTest"] = (
            "explicitFixedSampleReviewShowsExactVersionAndLimitedUsesWithoutAutomaticCalls",
            "unknownFailureAndConfigurationChangesNeverDisplayStaleSupportOrInvokeRetiredReview",
            "uncertainArchiveWriteRetriesOnlySameObservationAndProductionGateStaysDisabled")
        additional["com.t1doo.aiyou.UiPreferencesTest"] = (
            "legacyDefaultsStayLocalAndExplicitNoneSurvivesNewFileAndViewModelOwners",
            "unreadableDefaultRestrictsOnlyNewDraftsAndPreservesEveryUnknownPreferenceByte",
            "lostDefaultWriteAcknowledgementShowsUnconfirmedAndRetriesOnlyTheSamePreference",
            "confirmedRecapWeekSurvivesNewStorageAndViewModelAndKeepsDisplayChoices",
            "legacyDisplayBytesDefaultToDayAndAreUntouchedUntilExplicitPreferenceSave",
            "failedRecapSaveKeepsConfirmedPeriodAndRetryConfirmsOnlyRequestedChoice",
            "unknownOrIncompleteRecapPreferenceIsPreservedAndCannotBeOverwritten",
            "bothDisplayChoicesSurviveNewStorageAndViewModelInstances",
            "failedWriteKeepsConfirmedValuesAndRetryPersistsTheRequestedChoice",
            "corruptPreferencesArePreservedAndNeverFlashTheCompanion")
        additional["com.t1doo.aiyou.CompanionSettingsPresentationTest"] = (
            "thinkingAndYawningUseDistinctLocalArtworkAndCancelWithoutRecordSideEffects",
            "explicitDoubleFontInteractionsCancelAndRetireWithoutWritingOrReopeningThePanel",
            "finiteInteractionMotionStopsOnReductionAndBackgroundWithoutAutomaticReplay",
            "captureDefaultHasExplicitLocalChoicesAndRetiredOrUnconfirmedActionsCannotChangeIt",
            "companionIsLocalOptionalAndSettingsReturnPreservesCapture",
            "narrowDoubleFontSettingsHaveOneSwitchTargetAndReachableLicenseAndRetry",
            "eachProductionDestinationReturnsFromSettingsWithoutChangingSelection",
            "shortDoubleFontViewportKeepsDialogCloseAndProductionSaveReachable",
            "loadingPreferencesAndFailedSaveDoNotPublishCompanionSuccess",
            "oneNodEndsAndReducedMotionOrRemountCannotReplayIt",
            "successfulReadbackAcknowledgementDoesNotReplayOnSettingsReturn",
            "actualMotionGateTracksLifecycleSystemScalePowerSaverAndUserPreference")
        additional["com.t1doo.aiyou.ui.CompanionInteractionTest"] = (
            "onlyExplicitForegroundActionsStartAndBusyDoubleClicksCannotReplaceTheirRun",
            "quietCancelsAndOldCompletionCannotFinishTheNextExplicitAction",
            "backgroundCancelsAndForegroundReturnDoesNotReplayAnOldAction",
            "retirementFencesBlockedCompletionAndAllOldCallbacksWithoutAffectingNewPanel")
        additional["com.t1doo.aiyou.PlatformJpegPipelineTest"] = (
            "gridJpegUsesSmallerAllocationAndKeepsExifOrientationAndOriginalBytes",
            "nativeDecoderAppliesAllEightExifDirectionsOnceWithCorrectPixelsAndBothEndianHeaders",
            "progressiveAndLargeJpegActuallyDecodeWithinDisplayBound",
            "explicitMetadataRemovalHasNoGpsAndKeepsNativeOrientedPixelsWithoutRecompression",
            "nativeEarlyEoiAcceptanceIsRecordedWhileProductionRejectsInsufficientPayloadBeforeSave")
        additional["com.t1doo.aiyou.PlatformPngPipelineTest"] = (
            "gridPngIsSampledDirectlyWithinAllocationBudgetAndPreservesOriginal",
            "actualNativeDecoderReadsPixelsAndKeepsOriginalBytesUnchanged",
            "largePngIsActuallySampledAndDimensionsAboveBoundAreRejected",
            "malformedPixelsCrcTruncationAnimationOrientationAndTrailingBytesAreRejected",
            "selectedContentSourceUsesRealResolverAndPreservesPermissionFailure")
        additional["com.t1doo.aiyou.AttachmentPresentationTest"] = (
            "narrowLargeFontFailedConfirmationAndCancelAreReachableWithTouchTargets",
            "savingAndCorruptStatesOfferNoFalseCancelViewOrOverwriteAction",
            "savedOriginalOpensRealAttachmentRouteAndReturnsWithoutReplacingCapture",
            "actualPickerRegistryBindsOldResultToOldRequestAndRestorationDoesNotRelaunch",
            "globalRecordFailureHidesPictureAndShowsReachableActualRecovery",
            "launchedPickerKeepsActualRegistryOwnerAcrossGlobalRecordFailureAndRecovery",
            "restoredCompositionWithNewControllerCannotAdoptOldPickerResult",
            "jpegMetadataChoiceIsReachableAndFrozenByActualImagePickerRequest")
        additional["com.t1doo.aiyou.AttachmentNavigationPresentationTest"] = (
            "currentLongOriginalTailJpegViewerAndBackPreserveBothScrollPositions",
            "historicalLongOriginalTailPngViewerAndBackPreserveBothInputsAndLimits")
        additional["com.t1doo.aiyou.HistoricalAttachmentPresentationTest"] = (
            "actualHistoricalRouteViewsOldNativePictureAndReturnsToSameOriginalAndBothInputs",
            "retainedHistoryActionRejectsChangedRequestAndNewVersionNeverShowsOldPicture",
            "lateAuthenticatedReadAndGlobalFailureHideHistoricalPictureWithoutChangingOwnership",
            "narrowLargeFontHistoricalFailureRecoveryHasNoImportOrOverwriteActions")
        additional["com.t1doo.aiyou.RecordConnectionReviewPresentationTest"] = (
            "narrowDoubleFontDirectoryChoiceShowsDestinationVersionReferenceBoundaryAndSeparateConfirmation",
            "cancelledRetainedReviewAndChangedConfigurationCannotGrantAnOldConnection",
            "uncertainDecisionRetryOnlyReadsReceiptAndNeverRefreshesIntervalOrSends")
        additional["com.t1doo.aiyou.ManualTagRecallPresentationTest"] = (
            "keywordTagRecallShowsLabelWithoutBodyHighlightKeepsNavigationAndExactOriginalMode",
            "changedOrRemovedTagAndRevisionRetireRetainedMixedResultBeforeItCanOpenOrHighlight")
        additional["com.t1doo.aiyou.ManualAliasRecallPresentationTest"] = (
            "keywordAliasRecallShowsLabelWithoutBodyHighlightKeepsNavigationAndExactOriginalMode",
            "changedOrRevokedAliasAndRevisionRetireRetainedMixedResultBeforeItCanOpenOrHighlight",
            "doubleFontExplicitAliasEditorCreatesChangesAndRevokesWithoutSavingPrivateInputOrChangingNames")
    for owner, names in additional.items():
        for name in names:
            matches = [case for case in observed_cases if case.get("classname") == owner
                       and case.get("name", "").split("[", 1)[0].strip().removesuffix("()") == name]
            if len(matches) != 1:
                raise SystemExit(f"FAIL: required attachment UI case={owner}.{name} actual={len(matches)}")
            print(f"PASS: required attachment UI case={owner}.{name} actual=1 failures=0 errors=0 skipped=0")
    if label == "host queue":
        owner = "com.t1doo.aiyou.data.AttachmentStorageTest"
        required = (
            "readyBytesAreEncryptedVerifiedAndSurviveNewStoreAndLedger",
            "interruptionBeforeStagedRequiresExplicitRetryAndPreservesIntent",
            "stagedAndPublishedInterruptionsRecoverWithoutSourceOrDuplicateBytes",
            "failedReadyLedgerCommitRecoversPublishedBlobWithoutWritingAgain",
            "lostReadyAcknowledgementIsReadOnlyAndStableIdIsIdempotent",
            "badHashSizeAndInterruptedSourceNeverBecomeReadyAndRetryKeepsIdentity",
            "alteredCiphertextAndTagReturnNoPlaintextAndRetainOriginalEvidence",
            "missingAndTruncatedStagedFilesAreCorruptAndNeverReportedSaved",
            "metadataBindingRejectsWrongKeySourceRevisionMimeAndDigest",
            "changedIntentAndInvalidRevisionNeverOverwriteOrCreateBlob",
            "pathTraversalOversizeAndUnsupportedTypesAreRejectedBeforeImport",
            "unknownFilesAndSymlinksArePreservedWithoutEscapingOwnedDirectory",
            "simultaneousStoreInstanceCannotRecoverOrReplaceAnActiveWriter",
            "freshNonceAndClosedStorePreventCiphertextReuseAndAfterCloseAccess",
            "maximumStorageBoundAuthenticatesExactBytesWithoutPlaintextFiles",
            "closingDuringActiveImportNeverEncryptsWithErasedKey",
            "startupRecoveryDetectsMissingOrAlteredReadyBytesBeforeListingSavedState",
        )
        for name in required:
            matches = [case for case in observed_cases if case.get("classname") == owner
                       and case.get("name", "").split("[", 1)[0].strip().removesuffix("()") == name]
            if len(matches) != 1:
                raise SystemExit(f"FAIL: required attachment case={owner}.{name} actual={len(matches)}")
            print(f"PASS: required attachment case={owner}.{name} actual=1 failures=0 errors=0 skipped=0")
        owner = "com.t1doo.aiyou.data.AttachmentProcessTest"
        process_methods = (
            "distinctPidsRejectRecoveryAndImportWhileWriterOwnsLockThenRecoverOnce",
            "terminatedWritingPidsNeedExplicitSameIntentSourceRetryEvenWithCompleteCiphertext",
            "terminatedStagedAndPublishedPidsRecoverSameAuthenticatedFileWithoutRecopying",
            "terminatedPidAfterDurableReadyBeforeLedgerAcknowledgementRecoversReadOnly",
            "independentPidRecoveryRejectsChangedIdentitySourceHashKeyAndCiphertext",
        )
        for name in process_methods:
            matches = [case for case in observed_cases if case.get("classname") == owner
                       and case.get("name", "").split("[", 1)[0].strip().removesuffix("()") == name]
            if len(matches) != 1:
                raise SystemExit(f"FAIL: required attachment process case={owner}.{name} actual={len(matches)}")
            print(f"PASS: required attachment process case={owner}.{name} actual=1 failures=0 errors=0 skipped=0")
        process_suites = [ET.parse(report).getroot() for report in reports
                          if ET.parse(report).getroot().get("name") == owner]
        if len(process_suites) != 1:
            raise SystemExit("FAIL: require one actual attachment process suite")
        events = [json.loads(line.split("=", 1)[1])
                  for line in process_suites[0].findtext("system-out", "").splitlines()
                  if line.startswith("D02_ATTACHMENT_PROCESS_EVENT=")]
        expected = {"lock", "intent", "writing", "encrypted", "staged", "published", "ledger-ack",
                    "bad-identity", "bad-source", "bad-revision", "bad-digest", "bad-type", "bad-tag", "bad-key",
                    "bad-source-bytes"}
        if {event.get("scenario") for event in events} != expected:
            raise SystemExit("FAIL: missing/unexpected actual child-process scenarios")
        fixture_hash = hashlib.sha256(bytes((i * 31 + i // 251) % 256 for i in range(64 * 1024))).hexdigest()
        starts = [e for e in events if e.get("event") == "started"]
        if len(starts) != 31 or len({e.get("pid") for e in starts}) != 31:
            raise SystemExit("FAIL: require 31 distinct actual owned child PIDs")
        for scenario in expected:
            group = [e for e in events if e["scenario"] == scenario]
            roles = {"holder", "contender", "recoverer", "changed-intent"} if scenario == "lock" else (
                {"bad-writer"} if scenario == "bad-source-bytes" else
                {"writer", "rejector"} if scenario.startswith("bad-") else {"writer", "recoverer"})
            started = [e for e in group if e["event"] == "started"]
            if len(started) != len(roles) or {e["role"] for e in started} != roles:
                raise SystemExit("FAIL: wrong independent process roles for " + scenario)
            by_role = {e["role"]: e for e in started}
            if any(e["pid"] not in {s["pid"] for s in started} for e in group):
                raise SystemExit("FAIL: process evidence refers to an unstarted PID")
            for start in started:
                pid = start["pid"]
                related = [e for e in group if e["pid"] == pid]
                if type(pid) is not int or pid <= 0 or type(start["parent_pid"]) is not int or start["parent_pid"] <= 0 or pid == start["parent_pid"] \
                        or any(e["parent_pid"] != start["parent_pid"] or e["role"] != start["role"] for e in related):
                    raise SystemExit("FAIL: invalid independent PID/parent binding")
                exits = [e for e in related if e["event"] == "exited"]
                if len(exits) != 1 or exits[0]["alive"] is not False or type(exits[0]["exit"]) is not int:
                    raise SystemExit("FAIL: missing bounded child exit confirmation")
                kills = [e for e in related if e["event"] == "terminate-requested"]
                if kills:
                    reached = [e for e in related if e["event"] == "reached"]
                    if len(kills) != 1 or len(reached) != 1 or kills[0]["stage"] != reached[0]["stage"] \
                            or start["role"] not in {"holder", "writer"} or exits[0]["exit"] == 0 or exits[0]["result_written"] is not False:
                        raise SystemExit("FAIL: termination lacks reached-stage/nonzero-exit/no-ack proof")
                elif exits[0]["exit"] != 0 or exits[0]["result_written"] is not True:
                    raise SystemExit("FAIL: unexpected normal child result/exit")
            if scenario == "bad-source-bytes":
                rejected = [e for e in group if e["event"] == "source-rejected"]
                if len(rejected) != 1 or rejected[0]["state"] != "FAILED" or rejected[0]["source_calls"] != 1:
                    raise SystemExit("FAIL: bad source bytes not rejected")
                continue
            writer = by_role["holder" if scenario == "lock" else "writer"]
            if scenario == "lock":
                for event, role, state in (("busy", "contender", "STAGED"), ("conflict", "changed-intent", "READY")):
                    matches = [e for e in group if e["event"] == event]
                    if len(matches) != 1 or matches[0]["pid"] != by_role[role]["pid"] \
                            or matches[0]["state"] != state or matches[0]["source_calls"] != 0:
                        raise SystemExit("FAIL: cross-process busy/conflict boundary differs")
            reached = [e for e in group if e["event"] == "reached" and e["pid"] == writer["pid"]]
            killed = [e for e in group if e["event"] == "terminate-requested" and e["pid"] == writer["pid"]]
            phase = "staged" if scenario == "lock" or scenario.startswith("bad-") else scenario
            if len(reached) != 1 or len(killed) != 1 or reached[0]["stage"] != phase:
                raise SystemExit("FAIL: missing actual writer phase/termination")
            required_state = "WRITING" if phase in {"intent", "writing", "encrypted"} else "READY" if phase == "ledger-ack" else "STAGED"
            if reached[0]["ledger_state"] != required_state:
                raise SystemExit("FAIL: phase differs from durable fixture ledger state")
            size = reached[0]["cipher_bytes"]
            if phase == "intent" and size != 0 or phase == "writing" and not 17 < size < 64 * 1024 + 33 \
                    or phase not in {"intent", "writing"} and size != 64 * 1024 + 33:
                raise SystemExit("FAIL: reached-stage ciphertext boundary differs")
            if scenario.startswith("bad-"):
                rejected = [e for e in group if e["event"] == "rejected"]
                if len(rejected) != 1 or rejected[0]["pid"] != by_role["rejector"]["pid"] \
                        or rejected[0]["writer_pid"] != writer["pid"] or rejected[0]["state"] != "CORRUPT" \
                        or rejected[0]["source_calls"] != 0:
                    raise SystemExit("FAIL: changed metadata/key/ciphertext not rejected in new PID")
            else:
                verified = [e for e in group if e["event"] == "verified"]
                if len(verified) != 1 or verified[0]["pid"] != by_role["recoverer"]["pid"] \
                        or verified[0]["writer_pid"] != writer["pid"] or verified[0]["state"] != "READY" \
                        or verified[0]["read_sha256"] != fixture_hash \
                        or verified[0]["source_calls"] != (1 if phase in {"intent", "writing", "encrypted"} else 0):
                    raise SystemExit("FAIL: fresh PID authenticated read / source access boundary differs")
                if phase not in {"intent", "writing", "encrypted"}:
                    for field in ("cipher_sha256", "cipher_file_key", "cipher_bytes", "cipher_mtime_millis"):
                        if reached[0][field] != verified[0][field]:
                            raise SystemExit("FAIL: authenticated publication rewritten during recovery")
        for event in events:
            print("D02_ATTACHMENT_PROCESS_EVENT=" + json.dumps(event, separators=(",", ":"), sort_keys=True))
        print("PASS: attachment independent-process evidence scenarios=15 child_pids=31 owned_terminations=14 confirmed_exits=31 authenticated_new_pid_reads=7 corruption_rejections=7")
    if label == "Compose JVM interaction":
        additional["com.t1doo.aiyou.ManualTagPresentationTest"] = (
            "actualTagEditorWaitsForAcknowledgementKeepsFailedInputAndRemovesOnlyAfterConfirmation",
            "tagOnlyAndKeywordSavedZoneDateCombinationOpenFullOriginalAndReturnActualListPosition",
            "changedTagVersionRevokesOldResultBeforeCompositionAndReadFailureIsDistinctFromEmpty",
            "narrowLargeFontEditorKeepsActionsReachableAndHidesPersistedLabelsWhenSourceFails",
            "rawSavedStateAndRestorationExcludeTagConditionsInputsAndClearedFieldUndoHistory")
        owner = "com.t1doo.aiyou.LocalRecordsPresentationTest"
        required = (
            "narrowLargeFontSaveFailureRetryScrollsIntoViewAndPreservesPendingDraft",
            "narrowLargeFontReadFailureRetryScrollsIntoViewAndPreservesKnownDraft",
            "aiCommitConfirmationUsesPendingLabelAndStoppedSnapshotHidesPreviewAtLargeFont",
            "aiStoredAnnotationUsesCandidateHeadingWithoutUnsavedOrPendingLabel",
        )
        for name in required:
            matches = [case for case in observed_cases if case.get("classname") == owner
                       and case.get("name", "").split("[", 1)[0].strip().removesuffix("()") == name]
            if len(matches) != 1:
                raise SystemExit(f"FAIL: required Compose case={owner}.{name} actual={len(matches)}")
            print(f"PASS: required Compose case={owner}.{name} actual=1 failures=0 errors=0 skipped=0")

        owner = "com.t1doo.aiyou.HistoryEditPresentationTest"
        required = (
            "historicalTextLoadsWithoutWritesAndSaveAppendsOneImmutableVersion",
            "replacementCancelBackAndExplicitConfirmationPreserveIndependentDraft",
            "retainedHistoryActionsRejectChangedSelectionClosedReopenedAndDisposedNavigation",
            "concurrentNewVersionShowsConflictAndRetainsHistorySourceAndUserInput",
            "failedSaveRetainsLoadedHistoryAndUserCorrectionCanRetry",
            "committedHistorySaveReadbackFailureRetriesReadingWithoutAnotherWrite",
            "textMatchingCurrentOriginalDisablesSaveAndCreatesNoRevision",
            "narrowLargeFontFullUnicodeSourceAndReplacementPrimaryActionsRemainReachable",
        )
        for name in required:
            matches = [case for case in observed_cases if case.get("classname") == owner
                       and case.get("name", "").split("[", 1)[0].strip().removesuffix("()") == name]
            if len(matches) != 1:
                raise SystemExit(f"FAIL: required Compose case={owner}.{name} actual={len(matches)}")
            print(f"PASS: required Compose case={owner}.{name} actual=1 failures=0 errors=0 skipped=0")

        owner = "com.t1doo.aiyou.OriginalHistoryPresentationTest"
        required = (
            "currentAndOldVersionsOpenFullUnicodeAndNativeCopyOnlySelectedHistory",
            "historyReturnPreservesEditorInputCaptureDraftAndRecordListAnchorThenSaveShowsVersions",
            "failedListAndSelectedOriginalReadsHidePlaintextAndRetryWithoutDiscardingEdits",
            "versionChangeAndDeletionDuringReadsNeverReplaceUserInputOrShowStaleHistory",
            "closedReopenedAndDisposedHistoryRejectsRetainedReadsCopiesAndNavigation",
            "knownVersionChangeReturningWithoutRetryShowsCurrentAndRejectsOldCopy",
            "narrowLargeFontHistoryListReturnsToAnchorAndFullMultilineOriginalReachesEnd",
            "lateCaptureFailureCanRecoverWhileViewingHistoryWithoutLosingEitherInput",
            "missingSelectedRevisionAndBusySaveNeverOfferWrongOriginalOrHistory",
            "wideHistoryListAndReadFailureKeepCenteredMaximumContentWidth",
        )
        for name in required:
            matches = [case for case in observed_cases if case.get("classname") == owner
                       and case.get("name", "").split("[", 1)[0].strip().removesuffix("()") == name]
            if len(matches) != 1:
                raise SystemExit(f"FAIL: required Compose case={owner}.{name} actual={len(matches)}")
            print(f"PASS: required Compose case={owner}.{name} actual=1 failures=0 errors=0 skipped=0")

        owner = "com.t1doo.aiyou.OriginalEditPresentationTest"
        required = (
            "readingEditSaveReturnsToNewOriginalAndPreservesCaptureDraftAndListAnchor",
            "cancelAndSystemBackRequireDiscardAndKeepOriginalWithoutWrites",
            "busySaveRejectsCancelInputAndRetainedReaderCopyUntilCommit",
            "failedSaveKeepsEditsAndUserCorrectionRetriesWithoutFalseSuccess",
            "newerOrDeletedSourceNeverReplacesUserInputOrCreatesReplacement",
            "committedReadbackFailureRetriesReadOnlyWithoutAnotherRevision",
            "reopenedAndDisposedEditorRejectsRetainedSessionCallbacks",
            "narrowLargeFontBoundaryInputAndPrimaryActionsRemainReachable",
            "lateCaptureWriteFailureRetriesStorageWithoutLosingEitherInput",
            "endOfListSaveReturnsToVisibleSelectedCurrentOriginal",
            "tailReturnKeepsExpandedSavedPreviewFullyVisible",
            "tailReturnKeepsSelectedOriginalVisibleAfterControlledViewportShrink",
        )
        for name in required:
            matches = [case for case in observed_cases if case.get("classname") == owner
                       and case.get("name", "").split("[", 1)[0].strip().removesuffix("()") == name]
            if len(matches) != 1:
                raise SystemExit(f"FAIL: required Compose case={owner}.{name} actual={len(matches)}")
            print(f"PASS: required Compose case={owner}.{name} actual=1 failures=0 errors=0 skipped=0")

        owner = "com.t1doo.aiyou.OriginalReadingPresentationTest"
        required = (
            "boundedUnicodePreviewOpensAndCopiesEveryOriginalCharacter",
            "closeAndSystemBackPreserveEachListPositionAndSelectedRecord",
            "stableIdTracksReorderAndRevisionAndShowsDeletedRecordFallback",
            "retainedOpenAndFullCopyCallbacksRejectNavigationRevisionAndReadFailure",
            "nativeSelectionCopiesOnlySelectedTextWithSensitiveFlagAndRejectsStaleCopy",
            "retainedNavigationCallbacksRejectOtherRecordAndReopenedSameId",
            "restoredNavigationResolvesLatestOriginalAndRestoresListAnchor",
            "maximumMultilineOriginalReachesLastLineWithLargeFontAndKeyboardCopy",
            "openingAndReadFailureHidePlaintextAndRetryPreservesDraft",
            "platformClipboardDenialKeepsOriginalAndShowsFailureForBothCopyPaths",
        )
        for name in required:
            matches = [case for case in observed_cases if case.get("classname") == owner
                       and case.get("name", "").split("[", 1)[0].strip().removesuffix("()") == name]
            if len(matches) != 1:
                raise SystemExit(f"FAIL: required Compose case={owner}.{name} actual={len(matches)}")
            print(f"PASS: required Compose case={owner}.{name} actual=1 failures=0 errors=0 skipped=0")

    if label in ("host queue", "Compose JVM interaction"):
        required_edit_cases = {
            "host queue": {
                "OriginalEditStorageTest": (
                    "productionCipherColdReopenRestoresExactUnicodeAndContainsNoPlaintext",
                    "discardTombstoneRejectsLateAutosaveBeforeAndAfterColdReopen",
                    "concurrentStoresCompareAcknowledgedSnapshotsAndNeverOverwriteAnotherWriter",
                    "beforeRenameWriteFailureKeepsAcknowledgedCipherAndEncryptedPartial",
                    "lostPublicationAcknowledgementRetriesExactSnapshotWithoutReencrypting",
                    "wrongKeyBitFlipAndFilenameSwapFailClosedAndPreserveOriginalBytes",
                    "maximumExactUtf16DraftAndTerminalStatesStayBoundedAndClosedKeyIsZeroed",
                    "existingDirectoryStillRequiresKnownParentConfirmationAndCreatesNoUnknownAncestors",
                ),
                "OriginalEditIndexIsolationTest": (
                    "twoHealthyAndOneCorruptCipherColdIndexIsolatesAndPreservesEveryFile",
                    "multipleCorruptAndWrongKeyAreCountedWithoutPretendingStoreIsEmpty",
                    "directoryAndListingFailuresPropagateInsteadOfReturningPartialOrEmptySuccess",
                    "fileIoFailureRemainsGlobalAndCannotBeMisclassifiedAsAuthenticationDamage",
                    "indexLockAndFullSnapshotCasRejectConcurrentAndLateWritesWithoutChangingDrafts",
                    "replacementDuringAuthenticationIsIsolatedEvenWithPreservedMtimeAndOtherDraftsStayReadable",
                    "actualControllerRestoresHealthyAndUnavailableSourcesWhileCorruptCipherRemainsUntouched",
                    "wrongKeyControllerShowsSafeFailureCountWithFormalAndCaptureStillAvailable",
                    "globalIndexRefreshFailureDisablesStaleRecoveryUntilRealIndexRetrySucceeds",
                    "fileReplacementDuringRecoveryCannotDisplayOldInputOrOverwriteNewIdentity",
                ),
                "OriginalEditRecoveryTest": (
                    "explicitKeepColdReopensExactDraftAndResumedSessionCreatesOnlyOneRevision",
                    "keepDrainsLatestAcceptedAutosaveAndBusyOrOldSessionCallbacksCannotDiscardIt",
                    "keepReconcilesPublicationFailureAndConfirmationFailuresRetainEditorUntilRetry",
                    "keepRejectsCompetingIdentityOrMissingSourceWithoutClosingOrRecreatingRecords",
                    "coldControllerRebuildRestoresEditIndependentlyThenSavesOneNewOriginal",
                    "changedAndRemovedSourcesRestoreAsConflictsAndNeverBecomeReplacementRecords",
                    "explicitDiscardQueuesAfterStartedWriteAndLateCallbacksCannotResurrectIt",
                    "writeFailureKeepsInputsAndCorrectedRetryAcknowledgesOnlyCompleteSnapshot",
                    "lostWriteAcknowledgementCanPersistCorrectedInputWithoutOverwritingCompetingWriter",
                    "committedReadbackRetryAndColdReopenNeverAppendTheSameRevisionTwice",
                    "interruptionBetweenFormalCommitAndDraftFinalizationRestoresConflictWithoutDuplicateWrite",
                    "concurrentRestoredEditorsKeepCompetingCipherAndExplicitLocalDiscardCannotDeleteIt",
                    "corruptEditCipherDoesNotReplaceOriginalCaptureDraftOrCipherEvidence",
                    "lostFirstPublicationAcknowledgementThenDirectDiscardSurvivesColdReopen",
                    "competingDraftAfterFormalCommitDoesNotBlockConfirmationOrRepeatRevision",
                    "conflictCloseReleasesOldBindingBeforeHistoryRestoresCompetingDormantDraft",
                    "staleRecoveryIdentityRefreshesIndexWithoutDeletingReplacementDraft",
                    "publishedActiveRetryRequiresDurabilityConfirmationBeforeSavedNotice",
                    "publishedDiscardRetryCannotCloseUntilDurabilityConfirmationSucceeds",
                    "removedOrUnsupportedSourceRejectsLateAutosaveWithoutRestoringDeletedContent",
                    "dormantRecoveredDraftIsNotReplacedByHistoryUntilExplicitDiscardConfirmation",
                ),
            },
            "Compose JVM interaction": {
                "com.t1doo.aiyou.OriginalEditRecoveryPresentationTest": (
                    "narrowLargeFontExplicitKeepReturnsToOriginalAndResumesWithoutFormalWrite",
                    "explicitKeepConfirmationFailureStaysInEditorAndVisibleRetryThenReturns",
                    "narrowLargeFontColdRecoveryCorrectionAndSaveKeepCaptureAndImmutableOriginals",
                    "missingSourceRecoveryRetainsTextAndExplicitDiscardCannotResurrectIt",
                    "actualCipherWriteFailureRemainsVisibleAndCorrectedRetryPreservesCompleteInput",
                    "partialIndexWarningIsVisibleAndHealthyRecoveryRemainsUsableWithoutShowingDamagedText",
                    "wrongKeyIndexHasExplicitVisibleErrorAndNoRecoveryButtons",
                    "globalIndexFailureDisablesExistingButtonsAndVisibleRetryRestoresHealthyRecovery",
                ),
            },
        }
        for owner, names in required_edit_cases[label].items():
            for name in names:
                matches = [case for case in observed_cases if case.get("classname") == owner
                           and case.get("name", "").split("[", 1)[0].strip().removesuffix("()") == name]
                if len(matches) != 1:
                    raise SystemExit(f"FAIL: required edit recovery case={owner}.{name} actual={len(matches)}")
                print(f"PASS: required edit recovery case={owner}.{name} actual=1 failures=0 errors=0 skipped=0")

if args.ui:
    head = os.environ.get("GITHUB_SHA") or subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=root, text=True, timeout=5).strip()
    font_receipt = verify_transcript_fontscale(
        root / "app/build/reports/tests/testDebugUnitTest/transcript-ranges", head)
    print("TRANSCRIPT_FONT_SCALE_JSON " + json.dumps(font_receipt))
