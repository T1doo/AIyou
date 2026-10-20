pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name="aiyou-reminder-native-contracts"
include(":core:model",":core:domain")
project(":core").projectDir=file("../../core")
project(":core:model").projectDir=file("../../core/model")
project(":core:domain").projectDir=file("../../core/domain")
dependencyResolutionManagement { versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } } }
