pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "aiyou-ai-protocol-contracts"
include(":core:model", ":core:domain", ":core:ai")
project(":core").projectDir = file("../../core")
project(":core:model").projectDir = file("../../core/model")
project(":core:domain").projectDir = file("../../core/domain")
project(":core:ai").projectDir = file("../../core/ai")
dependencyResolutionManagement {
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}
