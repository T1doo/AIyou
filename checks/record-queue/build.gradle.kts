plugins { kotlin("jvm") version "2.4.21" }
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
dependencyLocking { lockAllConfigurations() }

// Compile the actual product sources, never an independently copied ViewModel.
val production = layout.buildDirectory.dir("production-sources")
val syncProduction = tasks.register<Sync>("syncProduction") {
    from("../../app/src/main/java/com/t1doo/aiyou/records/LocalRecordsViewModel.kt")
    from("../../core/model/src/main/kotlin/com/t1doo/aiyou/model/LocalRecords.kt")
    from("../../core/model/src/main/kotlin/com/t1doo/aiyou/model/ProcessingPolicy.kt")
    from("../../core/domain/src/main/kotlin/com/t1doo/aiyou/domain/EntryRepository.kt")
    into(production)
}
kotlin.sourceSets.getByName("main").kotlin.srcDir(production)
tasks.named("compileKotlin") { dependsOn(syncProduction) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { useJUnit() }
