plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.vanniktech.mavenPublish)
}

kotlin {
	js {
		// ES modules are required to consume the wasm-bindgen `bundler`-target output (hondius-png).
		useEsModules()
	}

	// Kotlin 2.4 marks wasm JS interop (external declarations, typed arrays) experimental; the
	// wasmJs actuals are built on it.
	sourceSets.matching { it.name == "wasmJsMain" }.configureEach {
		languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
	}

	sourceSets {
		commonMain.dependencies {
			api(project(":hondius-akm"))
			implementation(project(":hondius-container"))
		}
		jsMain.dependencies {
			// The Rust-built PNG codec, from ../native/png/build.ps1 (wasm-pack 0.14.0, `bundler` target).
			implementation(npm("hondius-png", file("../native/png/pkg")))
		}
		wasmJsMain.dependencies {
			implementation(npm("hondius-png", file("../native/png/pkg")))
			// kotlinx-browser provides org.khronos.webgl.Int8Array for Kotlin/Wasm JS interop.
			implementation(libs.kotlinx.browser)
		}
	}
}

// The targets, the Java level and the publishing settings every module shares are in the root build.
mavenPublishing {
	pom {
		name = "Hondius AKM Converter"
		description = "Converts legacy .rkm maps to the .akm format. Depends on hondius-akm."
	}
}
