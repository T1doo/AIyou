plugins { alias(libs.plugins.kotlin.jvm) apply false }
allprojects { dependencyLocking { lockAllConfigurations() } }

subprojects {
    tasks.withType<Test>().configureEach {
        doFirst {
            val capture = rootProject.file("../../scripts/verify-ai-protocol-results.py")
            val process = ProcessBuilder("python3", capture.absolutePath, "--capture", project.name)
                .inheritIO().start()
            check(process.waitFor() == 0) { "Cannot capture contract-test inputs" }
        }
    }
}
