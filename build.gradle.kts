import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile

// Settings every module shares: the guard against credentials in this repository, the targets and the Java level, the
// publishing of each module to Maven Central, the license in every jar, and the source paths recorded in the JS and
// Wasm libraries. The group and version are in gradle.properties; RELEASING.md has the release steps.

plugins {
	// A root `clean`, which also removes the staging repository below
	base
	alias(libs.plugins.kotlinMultiplatform) apply false
	alias(libs.plugins.kotlin.serialization) apply false
	alias(libs.plugins.vanniktech.mavenPublish) apply false
}

// Publishing credentials belong in ~/.gradle/gradle.properties. A build whose own property files name one, commented
// out or not, stops here, before the file can be committed.
listOf("gradle.properties", "local.properties").forEach { name ->
	val file = rootProject.file(name)
	if (file.isFile) {
		val credentials = file.readLines()
			.map { it.trimStart('#', '!', ' ', '\t').substringBefore('=').substringBefore(':').trim() }
			.filter { it.startsWith("mavenCentral") || it.startsWith("signing") }
		check(credentials.isEmpty()) {
			"$name names $credentials. Publishing credentials belong in ~/.gradle/gradle.properties, never in this repository."
		}
	}
}

val stagingRepository = layout.buildDirectory.dir("staging-repo")

// The type-safe `libs` accessor resolves against the project being configured, so inside `subprojects` it would target
// a module, where the catalog extension is not registered. Capture what that block needs here, in root scope.
val kotlinTest = libs.kotlin.test

subprojects {
	pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
		extensions.configure<KotlinMultiplatformExtension> {
			// Java 17 is the oldest release the JVM artifacts support. Compiling and testing on it keeps that true.
			jvmToolchain(17)
			jvm()
			js {
				browser()
				nodejs()
			}
			@OptIn(ExperimentalWasmDsl::class)
			wasmJs {
				browser()
			}
			sourceSets {
				commonTest.dependencies {
					implementation(kotlinTest)
				}
			}
		}
	}

	pluginManager.withPlugin("com.vanniktech.maven.publish") {
		extensions.configure<MavenPublishBaseExtension> {
			publishToMavenCentral()
			// Maven Central takes only signed artifacts, but a local repository needs no key, so sign when there is one
			if (providers.gradleProperty("signingInMemoryKey").isPresent ||
				providers.gradleProperty("signing.keyId").isPresent) {
				signAllPublications()
			}
			pom {
				inceptionYear = "2026"
				url = "https://github.com/adelheidsoftware/hondius"
				licenses {
					license {
						name = "MIT License"
						url = "https://opensource.org/license/mit"
						distribution = "repo"
					}
				}
				developers {
					developer {
						id = "adelheidsoftware"
						name = "Adelheid Software"
						url = "https://github.com/adelheidsoftware"
					}
				}
				scm {
					url = "https://github.com/adelheidsoftware/hondius"
					connection = "scm:git:https://github.com/adelheidsoftware/hondius.git"
					developerConnection = "scm:git:ssh://git@github.com/adelheidsoftware/hondius.git"
				}
			}
		}
		// build/staging-repo receives the exact signed file set a release uploads, to be inspected before it is
		extensions.configure<PublishingExtension> {
			repositories {
				maven {
					name = "staging"
					url = uri(stagingRepository)
				}
			}
		}
		// The repository is in this build's own folder, which its clean deletes, so in `clean publish…` that comes first
		tasks.withType<PublishToMavenRepository>().configureEach {
			if (name.endsWith("ToStagingRepository")) {
				mustRunAfter(":clean")
			}
		}
	}

	// Every jar, sources and Javadoc included, carries the license. A klib keeps exactly the layout the Kotlin compiler
	// gives it; its extension is set after this rule is registered, hence the lazy check. The rule is on the archive
	// base type because the Kotlin plugin's jars are not all of one Jar class.
	tasks.withType<AbstractArchiveTask>().configureEach {
		from(archiveExtension.map { if (it == "jar") listOf(rootProject.file("LICENSE")) else emptyList() }) {
			into("META-INF")
		}
	}

	// A JS or Wasm library records the path of each of its source files. Relative to the repository root, a published
	// library says nothing about the machine that built it.
	tasks.withType<Kotlin2JsCompile>().configureEach {
		compilerOptions.freeCompilerArgs.add("-Xklib-relative-path-base=${rootDir.absolutePath}")
	}
}
