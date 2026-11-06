package org.adelheid.hondius.akm.image

/**
 * Platform-agnostic ARGB image buffer.
 *
 * Pixel values are ARGB-packed `Int`s in the layout `0xAARRGGBB`, matching the
 * `BufferedImage.TYPE_INT_ARGB` convention used by the original Java converter.
 *
 * Instances are obtained via [decodePng] (decoding PNG bytes) or [imageBufferOf]
 * (wrapping an in-memory pixel array).
 */
interface ImageBuffer {
	val width: Int
	val height: Int

	/** Returns the ARGB-packed pixel at `(x, y)`. */
	fun getRgb(x: Int, y: Int): Int

	/**
	 * Returns all pixels in row-major order. Length is `width * height`.
	 * Implementations may return a fresh copy or share storage — callers must not mutate.
	 */
	fun toArgbArray(): IntArray
}

/** Mutable variant of [ImageBuffer]. Used by [BaseLayerSplitter] when constructing layer outputs. */
interface MutableImageBuffer : ImageBuffer {
	fun setRgb(x: Int, y: Int, argb: Int)
}
