package org.adelheid.hondius.akm

import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException
import kotlin.jvm.JvmStatic
import kotlin.math.roundToInt

/**
 * Moves between pixel coordinates and a map's grid. An editor or a converter that holds its outlines as float
 * pixels writes them through here, so the way an outline lands on the grid is decided in one place.
 */
object AkmGrid {

	/**
	 * The grid coordinate nearest to [pixels], kept on a canvas side of [sizePixels].
	 *
	 * @param gridBits the map's grid: a unit is 1/2^gridBits of a pixel
	 */
	@JvmStatic
	fun toGrid(pixels: Float, gridBits: Int, sizePixels: Int): Int {
		if (pixels.isNaN()) throw FormatException(FormatError.BAD_GEOMETRY, "A coordinate is not a number")
		return (pixels * (1 shl gridBits)).roundToInt().coerceIn(0, sizePixels shl gridBits)
	}

	/** A grid coordinate in pixels. Exact: a grid unit is a power of two. */
	@JvmStatic
	fun toPixels(grid: Int, gridBits: Int): Float = grid.toFloat() / (1 shl gridBits)

	/** The grid point nearest to a point in pixels, kept on the canvas. */
	@JvmStatic
	fun point(x: Float, y: Float, gridBits: Int, canvasWidth: Int, canvasHeight: Int): AkmPoint =
		AkmPoint(toGrid(x, gridBits, canvasWidth), toGrid(y, gridBits, canvasHeight))

	/**
	 * Puts a closed outline onto the grid. Each point is rounded to the nearest grid point; a point that lands on
	 * the one before it is dropped; and the ring is turned to run the way its place in a region demands.
	 *
	 * @param pixels the outline as `[x0, y0, x1, y1, ...]` in pixels, its first point not repeated at the end
	 * @param outline true for a region's outline, false for a hole
	 * @return the ring, or null when fewer than three points or no area is left of it on the grid
	 */
	@JvmStatic
	fun ring(pixels: FloatArray, gridBits: Int, canvasWidth: Int, canvasHeight: Int, outline: Boolean): AkmRing? {
		if (pixels.size % 2 != 0) throw FormatException(FormatError.BAD_GEOMETRY, "An outline is x and y pairs; got ${pixels.size} values")
		val points = ArrayList<Int>(pixels.size)
		for (i in 0 until pixels.size / 2) {
			val x = toGrid(pixels[i * 2], gridBits, canvasWidth)
			val y = toGrid(pixels[i * 2 + 1], gridBits, canvasHeight)
			val last = points.size - 2
			if (last >= 0 && points[last] == x && points[last + 1] == y) continue
			points.add(x)
			points.add(y)
		}
		while (points.size >= 4 && points[0] == points[points.size - 2] && points[1] == points[points.size - 1]) {
			points.removeAt(points.size - 1)
			points.removeAt(points.size - 1)
		}
		if (points.size < 6) return null
		val rounded = AkmRing(points.toIntArray())
		val area = rounded.signedArea2()
		return when {
			area == 0L -> null
			(area > 0) == outline -> rounded
			else -> rounded.reversed()
		}
	}

	/**
	 * Puts a region onto the grid: its outline and its holes, each through [ring].
	 *
	 * @param rings the outline first, then the holes, each as `[x0, y0, x1, y1, ...]` in pixels
	 * @return the region, or null when nothing is left of its outline. A hole that leaves nothing is dropped.
	 */
	@JvmStatic
	fun region(rings: List<FloatArray>, gridBits: Int, canvasWidth: Int, canvasHeight: Int): AkmRegion? {
		val outline = rings.firstOrNull()?.let { ring(it, gridBits, canvasWidth, canvasHeight, outline = true) } ?: return null
		val holes = rings.drop(1).mapNotNull { ring(it, gridBits, canvasWidth, canvasHeight, outline = false) }
		return AkmRegion(listOf(outline) + holes)
	}

	/** A ring's points as `[x0, y0, x1, y1, ...]` in pixels. */
	@JvmStatic
	fun pixels(ring: AkmRing, gridBits: Int): FloatArray = FloatArray(ring.points.size) { toPixels(ring.points[it], gridBits) }
}
