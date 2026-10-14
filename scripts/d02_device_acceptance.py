"""Exact native inventory and strict external-process acceptance helpers; never installs SDKs."""
import re
from pathlib import Path

LEGACY_NATIVE_CASES = {
    "com.t1doo.aiyou.data.EncryptedRecordsIntegrationTest": (
        "encryptedDraftAndEntrySurviveCloseAndReopen",
        "actualJobInsertFailureRollsBackEntryRevisionAndDraftConsumption",
        "editCreatesHistoryAndRejectsStaleRevision",
        "editTransactionFailureRetainsCurrentAndPreviousOriginal",
        "repeatedAndConcurrentSaveHaveOneDurableEntryAndJob",
        "changedDraftCannotBeCommittedFromStaleSnapshot",
        "directRevisionOverwriteAndDuplicateInsertAreRejected",
        "wrongDatabaseKeyFailsWithoutResettingExistingFile",
        "keystoreWrapReopensAndTamperingFailsWithoutReplacement",
        "missingKeystoreAliasOrEnvelopeNeverCreatesReplacementKey",
        "encryptedVersionOneMigrationPreservesDraftAndOriginalAndAddsLocalJob",
        "invalidMigrationSchemaIsRejectedWithoutDestructiveFallback",
        "fiveAcknowledgedSavesSurviveSeparateDatabaseReopens",
    ),
    "com.t1doo.aiyou.data.AttachmentRecordsIntegrationTest": (
        "encryptedAttachmentIsRevisionBoundAndReopensWithoutChangingTextDraftOrJobs",
        "failedFinalRoomTransitionPreservesPublishedBlobAndStartupRecoveryIsIdempotent",
        "encryptedVersionTwoFixtureMigratesWithoutChangingOriginalsDraftAndLedger",
        "wrappedMediaKeyHasSeparatePurposeAndMissingMaterialNeverReplacesIt",
    ),
    "com.t1doo.aiyou.data.OriginalEditRecordsIntegrationTest": (
        "encryptedEditAndCaptureSurviveReopenWithoutChangingFormalOriginal",
        "changedNativeOriginalRejectsStaleEditAndDiscardTombstoneSurvivesReopen",
    ),
}
SOURCE_BOUND_NATIVE_CASES = {
    "com.t1doo.aiyou.data.SourceBoundTextRecordsIntegrationTest": (
        "freshVersionSixUsesGeneratedRoomAndEncryptedNativeStore",
        "encryptedVersionThreeMigrationPreservesAllLegacyRowsAcrossReopen",
        "withdrawFirstAcrossIndependentRoomsRejectsLateCommit",
        "commitFirstAcrossIndependentRoomsWithdrawalRemovesAnnotation",
        "annotationInsertThenFinishAbortRollsBackAcrossReopen",
        "revisionAndTombstoneRejectLateResults",
        "duplicateConflictAndForeignScopeNeverOverwriteOriginal",
        "codePointReferencesRequireExactUnicodeQuotes",
    ),
}
MANUAL_TAG_NATIVE_CASES = {
    "com.t1doo.aiyou.data.ManualTagRecordsIntegrationTest": (
        "freshVersionSixEncryptsManualTagsAndAuthenticatesAcrossReopen",
        "encryptedVersionFourMigrationPreservesOriginalDraftAndJobsWithEmptyTags",
        "independentRoomsCasAndActualInsertAbortPreserveOriginal",
        "replayEmptySetAndTombstoneRespectIndependentTagVersionAndOwnerClose",
    ),
}
MANUAL_TASK_NATIVE_CASES = {
    "com.t1doo.aiyou.data.ManualTaskRecordsIntegrationTest": (
        "freshVersionSixPersistsExplicitTaskAndTerminalStatesAcrossEncryptedReopen",
        "encryptedVersionFiveMigrationPreservesOriginalDraftJobAndManualTag",
        "actualNativeTransitionAbortAndIndependentRoomsCasKeepSingleVersion",
        "nativeReplayAuthenticationAndOwnerCloseNeverReplaceTaskCiphertext",
    ),
}
NATIVE_CASES = {**LEGACY_NATIVE_CASES, **SOURCE_BOUND_NATIVE_CASES, **MANUAL_TAG_NATIVE_CASES, **MANUAL_TASK_NATIVE_CASES}
NATIVE_METHOD_COUNT = 35
STORAGE_PROBE = "com.t1doo.aiyou.data.ProcessRestartPersistenceTest"
STORAGE_PHASES = ("write", "read", "confirm", "cleanup")
APP_PROBE = "com.t1doo.aiyou.ProductionColdStartPersistenceTest"
APP_PHASES = ("write", "read")


def collect_source(root):
    collected = {}
    source_root = Path(root) / "core/data/src/androidTest/kotlin"
    allowed = set(NATIVE_CASES) | {STORAGE_PROBE}
    for path in source_root.rglob("*.kt"):
        methods = re.findall(r"@Test(?:\s*\([^)]*\))?\s+fun\s+(\w+)\s*\(", path.read_text())
        class_name = ".".join(path.relative_to(source_root).with_suffix("").parts)
        if methods and class_name not in allowed:
            raise ValueError("Unregistered native test class: " + class_name)
    for class_name, expected in NATIVE_CASES.items():
        path = source_root / (class_name.replace(".", "/") + ".kt")
        actual = re.findall(r"@Test(?:\s*\([^)]*\))?\s+fun\s+(\w+)\s*\(", path.read_text())
        if len(actual) != len(expected) or set(actual) != set(expected):
            raise ValueError("Exact native inventory drift: " + class_name)
        collected[class_name] = actual
    if sum(map(len, collected.values())) != NATIVE_METHOD_COUNT:
        raise ValueError("Expected 35 real native methods (legacy 19 + source-bound 8 + manual tags 4 + manual tasks 4)")
    for version in (2, 3, 4, 5, 6):
        export = Path(root) / f"core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/{version}.json"
        asset = Path(root) / f"core/data/src/androidTest/assets/records-schema-v{version}.json"
        if asset.read_bytes() != export.read_bytes():
            raise ValueError("Native fixture asset differs from exact Room export: " + str(version))
    return collected


def parse_result(text, expected_count, expected_methods=None, phase=None, token=None):
    bundles, current = [], {}
    active, started, passed = None, set(), set()
    terminal_seen = False
    for line in text.splitlines():
        # A truncated/tagged protocol record is evidence loss, never ordinary stdout.
        for prefix, pattern in (
            ("INSTRUMENTATION_STATUS:", r"INSTRUMENTATION_STATUS: (\w+)=(.*)"),
            ("INSTRUMENTATION_STATUS_CODE:", r"INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*"),
            ("INSTRUMENTATION_CODE:", r"INSTRUMENTATION_CODE:\s*(-?\d+)\s*"),
        ):
            if line.lstrip().startswith(prefix) and re.fullmatch(pattern, line) is None:
                raise ValueError("Malformed/truncated tagged instrumentation record")
        status = re.fullmatch(r"INSTRUMENTATION_STATUS: (\w+)=(.*)", line)
        if status:
            if terminal_seen or status[1] in current:
                raise ValueError("Status after terminal or duplicate bundle field")
            current[status[1]] = status[2]
        code = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*", line)
        if code:
            if terminal_seen:
                raise ValueError("Status code after terminal")
            value = int(code[1])
            identity = (current.get("class"), current.get("test"))
            if value == 1:
                if active is not None or None in identity or identity in started:
                    raise ValueError("Missing/duplicate/overlapping exact test start")
                active = identity; started.add(identity)
            elif value == 0:
                if active is None or active != identity or identity in passed:
                    raise ValueError("End does not pair with its exact active start")
                passed.add(identity); active = None
            elif value == 2:
                if phase is None or active is None:
                    raise ValueError("Unrequested/outside-case phase evidence")
            else:
                raise ValueError("Skipped/error/unknown status is not a passing test")
            bundles.append((value, current)); current = {}
        if re.fullmatch(r"INSTRUMENTATION_CODE:\s*(-?\d+)\s*", line):
            if active is not None or current or terminal_seen:
                raise ValueError("Incomplete case/bundle or repeated terminal")
            terminal_seen = True
    if current or active is not None:
        raise ValueError("Unclosed status bundle/case")
    codes = [code for code, _ in bundles]
    terminals = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", text, re.M)
    if codes.count(1) != expected_count or codes.count(0) != expected_count or \
            terminals != ["-1"] or not re.search(r"^OK \(" + str(expected_count) + r" tests?\)\s*$", text, re.M):
        raise ValueError("Require exact starts/passes, one successful terminal and zero skips/errors")
    methods = [(bundle.get("class"), bundle.get("test")) for code, bundle in bundles if code == 0]
    if expected_methods is not None and (started != set(expected_methods) or passed != set(expected_methods)):
        raise ValueError("Runtime starts/passes differ from exact source inventory")
    result = {"tests": expected_count, "terminal_status_codes": codes, "runner_result": -1, "methods": methods}
    if phase is not None:
        evidence = [bundle for code, bundle in bundles if code == 2]
        if len(evidence) != 1 or evidence[0].get("aiyou_phase") != phase or evidence[0].get("aiyou_token") != token or \
                not re.fullmatch(r"[1-9][0-9]*", evidence[0].get("aiyou_pid", "")):
            raise ValueError("Missing or mismatched externally phased native PID evidence")
        result["pid"] = int(evidence[0]["aiyou_pid"])
    return result


def stop_packages(command, adb, serial, packages, env, label):
    for package in sorted(set(packages)):
        command([adb, "-s", serial, "shell", "am", "force-stop", package], label + "-force-stop-" + package, env=env)
        alive = command([adb, "-s", serial, "shell", "pidof", package], label + "-stopped-" + package, env=env, require=False)
        if alive.returncode not in (0, 1) or alive.stdout.strip() or alive.stderr.strip():
            raise RuntimeError("Cannot prove package stopped: " + package)


def run_storage(command, instrument, adb, serial, component, packages, env, report, token):
    report["native_tests"] = []
    for index, (class_name, methods) in enumerate(NATIVE_CASES.items()):
        report["native_tests"].append(instrument(adb, serial, component, class_name, len(methods),
            "native-" + str(index + 1), env, expected_methods=[(class_name, method) for method in methods]))
    prior_pids = set()
    for phase in STORAGE_PHASES:
        stop_packages(command, adb, serial, packages, env, "storage-" + phase)
        result = instrument(adb, serial, component, STORAGE_PROBE, 1, "storage-" + phase, env,
            ("-e", "aiyouRestartPhase", phase, "-e", "aiyouRestartToken", token), phase=phase, token=token,
            expected_methods=[(STORAGE_PROBE, "acknowledgedSaveAndUnsubmittedDraftSurviveProcessRestart")])
        if result["pid"] in prior_pids:
            raise RuntimeError("Actual Android PID reused; distinct-process gate remains OPEN")
        prior_pids.add(result["pid"])
        report["native_tests"].append(result)
