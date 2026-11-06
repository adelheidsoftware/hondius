package org.adelheid.hondius.akm.image

import org.khronos.webgl.Int32Array
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint32Array
import org.khronos.webgl.Uint8Array

/**
 * JS PNG actuals — call into the Rust-backed `hondius-png` wasm-bindgen package
 * (`png` crate). The wasm side returns/accepts ARGB-packed `u32` pixels matching the
 * `ImageBuffer.toArgbArray()` layout.
 */

actual fun decodePng(bytes: ByteArray): ImageBuffer {
	val input = Uint8Array(bytes.unsafeCast<Int8Array>().buffer)
	val img = png_decode(input)
	val width = img.width
	val height = img.height
	// Pixels are u32 ARGB. Kotlin's IntArray is signed Int32Array with the same bit pattern —
	// reinterpret the buffer as Int32Array → IntArray (zero-copy).
	val argb = Int32Array(img.pixels.buffer).unsafeCast<IntArray>()
	img.free()
	return InMemoryImageBuffer(width, height, argb)
}

actual fun encodePng(buffer: ImageBuffer): ByteArray {
	val argb = buffer.toArgbArray()
	val pixels = Uint32Array(argb.unsafeCast<Int32Array>().buffer)
	val output = png_encode(buffer.width, buffer.height, pixels)
	return Int8Array(output.buffer).unsafeCast<ByteArray>()
}

actual fun imageBufferOf(width: Int, height: Int, argb: IntArray): MutableImageBuffer =
	InMemoryImageBuffer(width, height, argb)
