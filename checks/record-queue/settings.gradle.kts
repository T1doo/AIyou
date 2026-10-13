pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
rootProject.name = "aiyou-record-queue-regression"
include(":core:model", ":core:domain", ":core:ai")
project(":core").projectDir = file("../../core")
project(":core:model").projectDir = file("../../core/model")
project(":core:domain").projectDir = file("../../core/domain")
project(":core:ai").projectDir = file("../../core/ai")
dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}
