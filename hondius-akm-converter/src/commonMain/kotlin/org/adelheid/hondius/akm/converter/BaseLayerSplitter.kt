package org.adelheid.hondius.akm.converter

import org.adelheid.hondius.akm.AkmMap
import org.adelheid.hondius.akm.image.ImageBuffer
import org.adelheid.hondius.akm.image.imageBufferOf
import kotlin.math.abs

/**
 * Splits an RKM base layer image into separate per-color category images.
 * Each output contains only the pixels matching one visual category (water, land,
 * connections, territories, borders), with all others transparent.
 */
object BaseLayerSplitter {

	private const val WATER_R = 0xC0
	private const val WATER_G = 0xA3
	private const val WATER_B = 0x92
	private const val LAND_R = 0xC8
	private const val LAND_G = 0xB7
	private const val LAND_B = 0xAD
	private const val CONNECTION_R = 0x94
	private const val CONNECTION_G = 0x7D
	private const val CONNECTION_B = 0x6F
	private const val TERRITORY_R = 0xE0
	private const val TERRITORY_G = 0xDB
	private const val TERRITORY_B = 0xE3
	private const val BORDER_R = 0x74
	private const val BORDER_G = 0x4F
	private const val BORDER_B = 0x28

	private const val TOLERANCE = 15

	const val LAYER_WATER = "water"
	const val LAYER_LAND = "land"
	const val LAYER_CONNECTIONS = "connections"
	const val LAYER_TERRITORIES = "territories"
	const val LAYER_BORDERS = "borders"

	/** Result of splitting: a map from layer name to the extracted image. Only non-empty layers included. */
	data class SplitResult(val layers: Map<String, ImageBuffer>)

	fun split(baseImage: ImageBuffer): SplitResult {
		val width = baseImage.width
		val height = baseImage.height
		// This method holds six full-canvas int rasters live at once (~24 bytes per source pixel),
		// so the area has to be checked in Long arithmetic — width * height silently wraps negative
		// for large canvases and would surface as NegativeArraySizeException instead of a clear error.
		require(width.toLong() * height <= AkmMap.MAX_CANVAS_PIXELS) {
			"Image area ${width}x$height exceeds ${AkmMap.MAX_CANVAS_PIXELS} pixels"
		}
		val pixelCount = width * height

		val src = baseImage.toArgbArray()

		val water = IntArray(pixelCount)
		val land = IntArray(pixelCount)
		val connections = IntArray(pixelCount)
		val territories = IntArray(pixelCount)
		val borders = IntArray(pixelCount)

		var hasWater = false
		var hasLand = false
		var hasConnections = false
		var hasTerritories = false
		var hasBorders = false

		for (i in 0 until pixelCount) {
			val argb = src[i]
			val alpha = (argb ushr 24) and 0xFF
			if (alpha == 0) continue

			val r = (argb ushr 16) and 0xFF
			val g = (argb ushr 8) and 0xFF
			val b = argb and 0xFF

			when {
				matches(r, g, b, BORDER_R, BORDER_G, BORDER_B) -> {
					borders[i] = argb
					hasBorders = true
				}
				matches(r, g, b, CONNECTION_R, CONNECTION_G, CONNECTION_B) -> {
					connections[i] = argb
					hasConnections = true
				}
				matches(r, g, b, TERRITORY_R, TERRITORY_G, TERRITORY_B) -> {
					territories[i] = argb
					hasTerritories = true
				}
				matches(r, g, b, WATER_R, WATER_G, WATER_B) -> {
					water[i] = argb
					hasWater = true
				}
				matches(r, g, b, LAND_R, LAND_G, LAND_B) -> {
					land[i] = argb
					hasLand = true
				}
				else -> {
					// Unmatched opaque pixels fall back to land so we never silently drop content.
					land[i] = argb
					hasLand = true
				}
			}
		}

		val layers = linkedMapOf<String, ImageBuffer>()
		if (hasWater) layers[LAYER_WATER] = imageBufferOf(width, height, water)
		if (hasLand) layers[LAYER_LAND] = imageBufferOf(width, height, land)
		if (hasConnections) layers[LAYER_CONNECTIONS] = imageBufferOf(width, height, connections)
		if (hasTerritories) layers[LAYER_TERRITORIES] = imageBufferOf(width, height, territories)
		if (hasBorders) layers[LAYER_BORDERS] = imageBufferOf(width, height, borders)

		return SplitResult(layers)
	}

	private fun matches(r: Int, g: Int, b: Int, tR: Int, tG: Int, tB: Int): Boolean =
		abs(r - tR) <= TOLERANCE && abs(g - tG) <= TOLERANCE && abs(b - tB) <= TOLERANCE
}
