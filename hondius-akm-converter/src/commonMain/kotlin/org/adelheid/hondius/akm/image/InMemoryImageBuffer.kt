package org.adelheid.hondius.akm.image

/**
 * IntArray-backed [MutableImageBuffer]. Used by JS and Wasm targets (and by the pure-Kotlin
 * PNG codec) where `java.awt.image.BufferedImage` is unavailable.
 */
internal class InMemoryImageBuffer(
	override val width: Int,
	override val height: Int,
	private val pixels: IntArray,
) : MutableImageBuffer {
	init {
		require(width > 0 && height > 0) { "image dimensions must be positive" }
		require(pixels.size == width * height) {
			"pixel array length ${pixels.size} does not match $width * $height = ${width * height}"
		}
	}

	override fun getRgb(x: Int, y: Int): Int = pixels[y * width + x]

	override fun setRgb(x: Int, y: Int, argb: Int) {
		pixels[y * width + x] = argb
	}

	override fun toArgbArray(): IntArray = pixels.copyOf()
}
