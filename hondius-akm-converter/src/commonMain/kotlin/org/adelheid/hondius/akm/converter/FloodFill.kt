package org.adelheid.hondius.akm.converter

import org.adelheid.hondius.akm.image.ImageBuffer
import org.adelheid.hondius.rkm.RkmTerritory
import org.adelheid.hondius.rkm.RkmTerritoryIdentity
import kotlin.math.abs

/**
 * BFS flood-fill from nucleus seed points to assign each pixel to a territory.
 *
 * Each territory's nuclei act as seed pixels. The fill expands outward, assigning each
 * reachable pixel to the closest territory. Pixels that are fully transparent
 * (alpha = 0) in the base layer are treated as impassable barriers.
 */
object FloodFill {

	/** Label for unassigned / barrier pixels. */
	const val UNASSIGNED: Int = -1

	// RKM territory fill color (#E0DBE3). Pixels within COLOR_TOLERANCE on each channel are fillable.
	private const val TERRITORY_R = 224
	private const val TERRITORY_G = 219
	private const val TERRITORY_B = 227

	// RKM border color (#744F28).
	private const val BORDER_R = 116
	private const val BORDER_G = 79
	private const val BORDER_B = 40

	private const val COLOR_TOLERANCE = 15

	/** Upper bound on [fillGaps] passes. Well above what any legitimate map needs; see the comment there. */
	private const val MAX_FILL_PASSES = 64

	/** Result of flood-fill: the label grid and the identity → index mapping. */
	data class FillResult(
		val labels: Array<IntArray>,
		val width: Int,
		val height: Int,
		val identityToIndex: Map<RkmTerritoryIdentity, Int>,
	) {
		// Manual equals/hashCode skipped — this is a transient intermediate, never compared.
	}

	/** Performs BFS flood-fill from all territory nuclei simultaneously. */
	fun fill(baseLayer: ImageBuffer, territories: Set<RkmTerritory>): FillResult {
		val width = baseLayer.width
		val height = baseLayer.height
		val labels = Array(height) { IntArray(width) { UNASSIGNED } }

		val identityToIndex = LinkedHashMap<RkmTerritoryIdentity, Int>(territories.size)
		var index = 0
		for (t in territories) {
			identityToIndex[t.identity] = index++
		}

		val queue = ArrayDeque<IntArray>()
		for (t in territories) {
			val territoryIndex = identityToIndex[t.identity]!!
			for (nucleus in t.nuclei) {
				val nx = nucleus.x
				val ny = nucleus.y
				if (nx in 0 until width && ny in 0 until height && isFillable(baseLayer, nx, ny)) {
					labels[ny][nx] = territoryIndex
					queue.addLast(intArrayOf(nx, ny))
				}
			}
		}

		val dx4 = intArrayOf(0, 0, 1, -1)
		val dy4 = intArrayOf(1, -1, 0, 0)
		while (queue.isNotEmpty()) {
			val cell = queue.removeFirst()
			val cx = cell[0]
			val cy = cell[1]
			val currentLabel = labels[cy][cx]
			for (d in 0 until 4) {
				val nx = cx + dx4[d]
				val ny = cy + dy4[d]
				if (nx in 0 until width && ny in 0 until height &&
					labels[ny][nx] == UNASSIGNED && isFillable(baseLayer, nx, ny)
				) {
					labels[ny][nx] = currentLabel
					queue.addLast(intArrayOf(nx, ny))
				}
			}
		}

		fillGaps(baseLayer, labels, width, height)
		expandIntoBorders(baseLayer, labels, width, height)

		return FillResult(labels, width, height, identityToIndex)
	}

	/**
	 * Fills small pockets of territory-colored pixels that the BFS couldn't reach because
	 * they are completely enclosed by border pixels. For each unassigned fillable pixel,
	 * assigns it to the most common neighboring territory label. Repeats until stable.
	 */
	private fun fillGaps(image: ImageBuffer, labels: Array<IntArray>, width: Int, height: Int) {
		val dx = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
		val dy = intArrayOf(1, -1, 0, 0, 1, -1, -1, 1)

		// Each pass propagates a label by at most one pixel against the scan direction, so a
		// crafted image (a long channel oriented right-to-left) makes this quadratic in pixel
		// count. Real gaps are thin border artefacts that settle in a handful of passes; cap it
		// so a hostile file cannot pin a worker thread indefinitely.
		val counts = HashMap<Int, Int>(8)
		var changed = true
		var passes = 0
		while (changed && passes < MAX_FILL_PASSES) {
			changed = false
			passes++
			for (y in 0 until height) {
				for (x in 0 until width) {
					if (labels[y][x] != UNASSIGNED) continue
					if (!isFillable(image, x, y)) continue

					counts.clear()
					for (d in 0 until 8) {
						val nx = x + dx[d]
						val ny = y + dy[d]
						if (nx in 0 until width && ny in 0 until height) {
							val lbl = labels[ny][nx]
							if (lbl != UNASSIGNED) {
								counts[lbl] = (counts[lbl] ?: 0) + 1
							}
						}
					}
					if (counts.isNotEmpty()) {
						labels[y][x] = counts.maxBy { it.value }.key
						changed = true
					}
				}
			}
		}
	}

	private fun isFillable(image: ImageBuffer, x: Int, y: Int): Boolean {
		val argb = image.getRgb(x, y)
		val alpha = (argb ushr 24) and 0xFF
		if (alpha == 0) return false
		val r = (argb ushr 16) and 0xFF
		val g = (argb ushr 8) and 0xFF
		val b = argb and 0xFF
		return abs(r - TERRITORY_R) <= COLOR_TOLERANCE &&
			abs(g - TERRITORY_G) <= COLOR_TOLERANCE &&
			abs(b - TERRITORY_B) <= COLOR_TOLERANCE
	}

	/**
	 * Expands territory labels into border-colored pixels so adjacent territories share a contour
	 * line at the center of the border. Non-territory areas (land, water, connections) also
	 * participate using a temporary `LAND_LABEL` so coastline borders aren't one-sidedly claimed.
	 */
	private fun expandIntoBorders(image: ImageBuffer, labels: Array<IntArray>, width: Int, height: Int) {
		val dx = intArrayOf(0, 0, 1, -1)
		val dy = intArrayOf(1, -1, 0, 0)

		val landLabel = -2

		val queue = ArrayDeque<IntArray>()
		for (y in 0 until height) {
			for (x in 0 until width) {
				if (labels[y][x] != UNASSIGNED) queue.addLast(intArrayOf(x, y))
			}
		}

		for (y in 0 until height) {
			for (x in 0 until width) {
				if (labels[y][x] == UNASSIGNED && !isBorderPixel(image, x, y)) {
					val alpha = (image.getRgb(x, y) ushr 24) and 0xFF
					if (alpha > 0) {
						labels[y][x] = landLabel
						queue.addLast(intArrayOf(x, y))
					}
				}
			}
		}

		while (queue.isNotEmpty()) {
			val cell = queue.removeFirst()
			val cx = cell[0]
			val cy = cell[1]
			val currentLabel = labels[cy][cx]
			for (d in 0 until 4) {
				val nx = cx + dx[d]
				val ny = cy + dy[d]
				if (nx in 0 until width && ny in 0 until height &&
					labels[ny][nx] == UNASSIGNED && isBorderPixel(image, nx, ny)
				) {
					labels[ny][nx] = currentLabel
					queue.addLast(intArrayOf(nx, ny))
				}
			}
		}

		for (y in 0 until height) {
			for (x in 0 until width) {
				if (labels[y][x] == landLabel) labels[y][x] = UNASSIGNED
			}
		}
	}

	private fun isBorderPixel(image: ImageBuffer, x: Int, y: Int): Boolean {
		val argb = image.getRgb(x, y)
		val alpha = (argb ushr 24) and 0xFF
		if (alpha == 0) return false
		val r = (argb ushr 16) and 0xFF
		val g = (argb ushr 8) and 0xFF
		val b = argb and 0xFF
		return abs(r - BORDER_R) <= COLOR_TOLERANCE &&
			abs(g - BORDER_G) <= COLOR_TOLERANCE &&
			abs(b - BORDER_B) <= COLOR_TOLERANCE
	}
}
