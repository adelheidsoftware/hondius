plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.vanniktech.mavenPublish)
}

kotlin {
	sourceSets {
		commonMain.dependencies {
			// The map's metadata, errors and carried chunks are container types
			api(project(":hondius-container"))
		}
	}
}

// The targets, the Java level and the publishing settings every module shares are in the root build.
mavenPublishing {
	pom {
		name = "Hondius AKM"
		description = "Kotlin Multiplatform implementation of the .akm binary map format."
	}
}
