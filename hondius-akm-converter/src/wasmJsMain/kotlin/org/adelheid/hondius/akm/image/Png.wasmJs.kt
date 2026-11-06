package org.adelheid.hondius.akm.image

import org.khronos.webgl.Int32Array
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint32Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.toByteArray
import org.khronos.webgl.toInt32Array
import org.khronos.webgl.toInt8Array
import org.khronos.webgl.toIntArray

/**
 * Kotlin/Wasm PNG actuals — call into the Rust-backed `hondius-png` wasm-bindgen
 * package (`png` crate). `kotlinx-browser` provides the typed-array adapters; we bridge
 * IntArray ↔ Uint32Array through the shared ArrayBuffer (signed vs unsigned views over the
 * same 4-byte slots).
 */

actual fun decodePng(bytes: ByteArray): ImageBuffer {
	val input = Uint8Array(bytes.toInt8Array().buffer)
	val img = png_decode(input)
	val width = img.width
	val height = img.height
	val argb = Int32Array(img.pixels.buffer).toIntArray()
	img.free()
	return InMemoryImageBuffer(width, height, argb)
}

actual fun encodePng(buffer: ImageBuffer): ByteArray {
	val argb = buffer.toArgbArray()
	val pixels = Uint32Array(argb.toInt32Array().buffer)
	val output = png_encode(buffer.width, buffer.height, pixels)
	return Int8Array(output.buffer).toByteArray()
}

actual fun imageBufferOf(width: Int, height: Int, argb: IntArray): MutableImageBuffer =
	InMemoryImageBuffer(width, height, argb)
