plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

// Compile the product transaction/codec/schema, with only the platform SQL adapter replaced.
val production = layout.buildDirectory.dir("production-sources")
val syncProduction = tasks.register<Sync>("syncProduction") {
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RemoteTextTransactions.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/PersistentSourceBoundTextSubscription.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RemoteTextCodec.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RemoteTextSqlSchema.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/DrainingRecordsSqlDatabase.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/EncryptedAttachmentStore.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/EncryptedOriginalEditStore.kt")
    listOf("ManualTagSqlSchema.kt", "ManualTagCipher.kt", "SqlManualTagRepository.kt").forEach {
        from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/$it")
    }
    listOf("ManualTaskSqlSchema.kt", "ManualTaskCipher.kt", "SqlManualTaskRepository.kt", "SqlOriginalExportRepository.kt", "SqlRecycleBinRepository.kt").forEach {
        from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/$it")
    }
    listOf("RecordPackageBinary.kt", "JcaRecordPackageCipher.kt", "SqlRecordPackageRepository.kt").forEach {
        from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/$it")
    }
    into(production)
}
kotlin.sourceSets.getByName("main").kotlin.srcDir(production)
tasks.named("compileKotlin") { dependsOn(syncProduction) }

// Actual ViewModel/controller code, with only Android opening/lifecycle/image platform shells.
val vmProduction = layout.buildDirectory.dir("vm-production-test-sources")
val syncVmProduction = tasks.register<Sync>("syncVmProduction") {
    listOf("RecordPackageController.kt", "RecordPackageIo.kt", "RecycleBinController.kt", "OriginalExportController.kt", "OriginalExportIo.kt", "ManualTaskController.kt", "ManualTagController.kt", "LocalRecordsViewModel.kt", "OriginalEditUiState.kt", "OriginalHistoryUiState.kt",
        "LocalAttachmentController.kt", "PngPayloadValidator.kt", "JpegPayloadValidator.kt",
        "LocalRecordAiController.kt", "RecordAiCapability.kt", "RecordAiUiState.kt").forEach { name ->
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
    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.serialization.json)
    testImplementation(libs.coroutines.core)
}

tasks.test { useJUnit() }
