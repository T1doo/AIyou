plugins { kotlin("jvm") version "2.4.21" }
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
dependencyLocking { lockAllConfigurations() }

// Compile the actual product sources, never an independently copied ViewModel.
val production = layout.buildDirectory.dir("production-sources")
val syncProduction = tasks.register<Sync>("syncProduction") {
    from("../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RecordsStorageUsageReader.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/ui/StorageUsageController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/ui/AppAccessController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/ui/AppAccessPreferenceOperations.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/ui/AppAccessRetirementBarrier.kt")
    from("../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RecordHistoryPackageCodec.kt")
    from("../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RecordEventDatePackageCodec.kt")
    from("../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RecordPeoplePackageCodec.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/TaskProposalController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/TaskReminderController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/FactRecapController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/LocalProcessingController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/SourceTaskController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/RecordPackageController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/RecordPackageIo.kt")
    from("../../core/data/src/main/kotlin/com/t1doo/aiyou/data/JcaRecordPackageCipher.kt")
    from("../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RecordPackageBinary.kt")
    from("../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RecordEditDraftPackageCodec.kt")
    from("../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RecordMediaPackageCipher.kt")
    from("../../core/data/src/main/kotlin/com/t1doo/aiyou/data/FullBackupCodec.kt")
    from("../../core/data/src/main/kotlin/com/t1doo/aiyou/data/CaptureDraftCodec.kt")
    from("../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RecordPackageActivityCodec.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/LocalRecordsViewModel.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/RecordMetadataReadingSource.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/MixedCaptureController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/ManualTagController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/ManualPeopleController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/ModelConnectionsController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/LocalConnectionSecretResolver.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/RecordFavoriteController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/RecordEventDateController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/CurrentImageGridController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/ManualTaskController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/OriginalExportController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/OriginalExportIo.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/RecycleBinController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/LocalRecordAiController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/LocalCandidateController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/SourceTagController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/SourceMemoryController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/SourceMemoryInboxController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/LocalOriginalSearchMatcher.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/OriginalSearchPreview.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/LocalOriginalSearchController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/RecordAiCapability.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/RecordAiUiState.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/OriginalEditUiState.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/OriginalHistoryUiState.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/LocalAttachmentController.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/PngPayloadValidator.kt")
    from("../../app/src/main/java/com/t1doo/aiyou/records/JpegPayloadValidator.kt")
    from("../../core/data/src/main/kotlin/com/t1doo/aiyou/data/EncryptedAttachmentStore.kt")
    from("../../core/data/src/main/kotlin/com/t1doo/aiyou/data/EncryptedOriginalEditStore.kt")
    from("../../core/data/src/main/kotlin/com/t1doo/aiyou/data/ErasureFileProtocol.kt")
    into(production)
}
kotlin.sourceSets.getByName("main").kotlin.srcDir(production)
tasks.named("compileKotlin") { dependsOn(syncProduction) }

val fixture = layout.buildDirectory.dir("jpeg-test-fixture")
val syncJpegFixture = tasks.register<Sync>("syncJpegFixture") {
    from("../../app/src/test/kotlin/com/t1doo/aiyou/JpegFixture.kt")
    into(fixture)
}
kotlin.sourceSets.getByName("test").kotlin.srcDir(fixture)
tasks.named("compileTestKotlin") { dependsOn(syncJpegFixture) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:ai"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.test {
    useJUnit()
    // Child JVMs run the same compiled production Cipher/file implementation.
    systemProperty("aiyou.attachment.process.classpath", sourceSets.test.get().runtimeClasspath.asPath)
    systemProperty("aiyou.attachment.process.evidence", layout.buildDirectory.dir("reports/attachment-process").get().asFile.absolutePath)
}
