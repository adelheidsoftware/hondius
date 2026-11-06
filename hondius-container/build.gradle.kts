plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.vanniktech.mavenPublish)
}

// The targets, the Java level and the publishing settings every module shares are in the root build.
mavenPublishing {
	pom {
		name = "Hondius Container"
		description = "What the .akm and .akp formats share: binary reading and writing, SHA-256 and SHA-512, in plain Kotlin."
	}
}
