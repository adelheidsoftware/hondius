plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.vanniktech.mavenPublish)
}

kotlin {
	sourceSets {
		commonMain.dependencies {
			api(project(":hondius-akp"))
			implementation(libs.kotlinx.serialization.json)
		}
	}
}

// The targets, the Java level and the publishing settings every module shares are in the root build.
mavenPublishing {
	pom {
		name = "Hondius AKP Converter"
		description = "Converts legacy .rkp palettes to the .akp format. Depends on hondius-akp."
	}
}
