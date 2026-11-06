@file:JsModule("hondius-png")

package org.adelheid.hondius.akm.image

import org.khronos.webgl.Uint32Array
import org.khronos.webgl.Uint8Array

/**
 * Named imports of the Rust-backed PNG codec from the `hondius-png` wasm-bindgen
 * package. Kotlin/Wasm consumes the same wasm-bindgen-generated JS via its JS interop.
 */

internal external class PngImage : JsAny {
	val width: Int
	val height: Int
	val pixels: Uint32Array

	/** Releases the wasm-side allocation. Safe to call multiple times. */
	fun free()
}

internal external fun png_decode(bytes: Uint8Array): PngImage

internal external fun png_encode(width: Int, height: Int, pixels: Uint32Array): Uint8Array
