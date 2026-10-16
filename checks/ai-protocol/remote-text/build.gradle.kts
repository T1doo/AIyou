plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

// Compile the product transaction/codec/schema, with only the platform SQL adapter replaced.
val production = layout.buildDirectory.dir("production-sources")
val syncProduction = tasks.register<Sync>("syncProduction") {
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RecordHistoryPackageCodec.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/TaskProposalCipher.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/SqlTaskProposalRepository.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/SqlSourceTaskRepository.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/SqlLocalProcessingRepository.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/SqlFactRecapRepository.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RemoteTextTransactions.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/PersistentSourceBoundTextSubscription.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RemoteTextCodec.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RemoteTextSqlSchema.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/DrainingRecordsSqlDatabase.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/EncryptedAttachmentStore.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/EncryptedOriginalEditStore.kt")
    listOf("ErasureFileProtocol.kt", "PermanentErasureSqlSchema.kt", "SqlPermanentErasureRepository.kt", "DrainingRecordsStorage.kt", "PermanentErasureRecovery.kt").forEach {
        from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/$it")
    }
    listOf("ManualTagSqlSchema.kt", "ManualTagCipher.kt", "SqlManualTagRepository.kt", "SqlSourceTagRepository.kt", "SourceMemoryCipher.kt", "SqlSourceMemoryRepository.kt").forEach {
        from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/$it")
    }
    listOf("ManualTaskSqlSchema.kt", "ManualTaskCipher.kt", "SqlManualTaskRepository.kt", "SqlOriginalExportRepository.kt", "SqlRecycleBinRepository.kt").forEach {
        from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/$it")
    }
    listOf("RecordPackageActivityCodec.kt", "SqlRecordPackageActivities.kt", "RecordPackageBinary.kt", "JcaRecordPackageCipher.kt", "RecordMediaPackageCipher.kt", "SqlRecordMediaPackageRepository.kt", "SqlCaptureRepository.kt", "SqlRecordPackageRepository.kt").forEach {
        from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/$it")
    }
    listOf("LocalSearchTokenizer.kt", "LocalSearchSqlSchema.kt", "SqlLocalSearchRepository.kt").forEach {
        from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/$it")
    }
    listOf("RecordFavoriteSqlSchema.kt", "SqlRecordFavoriteRepository.kt").forEach {
        from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/$it")
    }
    listOf("LocalCandidateSqlSchema.kt", "LocalCandidateCipher.kt", "SqlLocalCandidateRepository.kt").forEach {
        from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/$it")
    }
    into(production)
}
kotlin.sourceSets.getByName("main").kotlin.srcDir(production)
tasks.named("compileKotlin") { dependsOn(syncProduction) }

// Actual ViewModel/controller code, with only Android opening/lifecycle/image platform shells.
val vmProduction = layout.buildDirectory.dir("vm-production-test-sources")
val syncVmProduction = tasks.register<Sync>("syncVmProduction") {
    from("../../../app/src/main/java/com/t1doo/aiyou/records/TaskProposalController.kt")
    from("../../../app/src/main/java/com/t1doo/aiyou/records/TaskReminderController.kt")
    from("../../../app/src/main/java/com/t1doo/aiyou/records/FactRecapController.kt")
    from("../../../app/src/main/java/com/t1doo/aiyou/records/LocalProcessingController.kt")
    from("../../../app/src/main/java/com/t1doo/aiyou/records/SourceTaskController.kt")
    listOf("MixedCaptureController.kt", "LocalOriginalSearchController.kt", "LocalOriginalSearchMatcher.kt", "RecordPackageController.kt", "RecordPackageIo.kt", "RecycleBinController.kt", "OriginalExportController.kt", "OriginalExportIo.kt", "ManualTaskController.kt", "ManualTagController.kt", "RecordFavoriteController.kt", "LocalRecordsViewModel.kt", "OriginalEditUiState.kt", "OriginalHistoryUiState.kt",
        "LocalAttachmentController.kt", "PngPayloadValidator.kt", "JpegPayloadValidator.kt",
        "LocalRecordAiController.kt", "RecordAiCapability.kt", "RecordAiUiState.kt", "LocalCandidateController.kt", "SourceTagController.kt", "SourceMemoryController.kt").forEach { name ->
        from("../../../app/src/main/java/com/t1doo/aiyou/records/$name")
    }
    into(vmProduction)
}
kotlin.sourceSets.getByName("test").kotlin.srcDir(vmProduction)
tasks.named("compileTestKotlin") { dependsOn(syncVmProduction) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:ai"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.serialization.json)
    testImplementation(libs.coroutines.core)
}

tasks.test {
    useJUnit()
    systemProperty("aiyou.erasure.process.classpath", sourceSets.test.get().runtimeClasspath.asPath)
    systemProperty("aiyou.erasure.process.evidence", layout.buildDirectory.dir("reports/erasure-process").get().asFile.absolutePath)
}
