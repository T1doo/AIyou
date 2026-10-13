plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

// Compile the product transaction/codec/schema, with only the platform SQL adapter replaced.
val production = layout.buildDirectory.dir("production-sources")
val syncProduction = tasks.register<Sync>("syncProduction") {
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RemoteTextTransactions.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RemoteTextCodec.kt")
    from("../../../core/data/src/main/kotlin/com/t1doo/aiyou/data/RemoteTextSqlSchema.kt")
    into(production)
}
kotlin.sourceSets.getByName("main").kotlin.srcDir(production)
tasks.named("compileKotlin") { dependsOn(syncProduction) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:ai"))
    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.serialization.json)
}

tasks.test { useJUnit() }
