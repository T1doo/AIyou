plugins { alias(libs.plugins.kotlin.jvm) apply false }
allprojects { dependencyLocking { lockAllConfigurations() } }

subprojects {
    tasks.withType<Test>().configureEach {
        doFirst {
            val capture = rootProject.file("../../scripts/verify-ai-protocol-results.py")
            // Windows may resolve python3 to a Store alias; keep the same evidence
            // script mandatory while allowing an explicit installed interpreter.
            val process = ProcessBuilder(System.getenv("AIYOU_PYTHON") ?: "python3", capture.absolutePath, "--capture", project.name)
                .inheritIO().start()
            check(process.waitFor() == 0) { "Cannot capture contract-test inputs" }
        }
    }
}
