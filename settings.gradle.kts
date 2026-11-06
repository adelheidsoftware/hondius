pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositories {
		mavenCentral()
	}
}

plugins {
	// Downloads a Java 17 toolchain on machines that have none installed
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "hondius"

// Each published module's folder is named after its artifact, org.adelheid:<folder>
include(
	"hondius-container",
	"hondius-akm",
	"hondius-akm-converter",
	"hondius-akp",
	"hondius-akp-converter",
)
