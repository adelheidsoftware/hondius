package org.adelheid.hondius.akm

import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException
import kotlin.jvm.JvmStatic
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where a territory's label or count goes. A territory may name the point itself, as its anchor. For one that does
 * not, the format leaves the placement to the reader, and this is that placement for every reader built on this
 * library.
 */
object AkmAnchors {
	private val SQRT_2 = sqrt(2.0)

	/**
	 * [territory]'s anchor, or, when it has none, the [pole] of its largest region. In grid units.
	 *
	 * @param gridBits the map's grid
	 */
	@JvmStatic
	fun of(territory: AkmTerritory, gridBits: Int): AkmPoint {
		territory.anchor?.let { return it }
		val largest = territory.regions.maxByOrNull { region -> region.rings.sumOf { it.signedArea2() } }
			?: throw FormatException(FormatError.BAD_GEOMETRY, "A territory with no region has no place for a label")
		return pole(largest, gridBits)
	}

	/**
	 * The point of [region] that lies furthest from its outline and its holes, found to within one pixel. Unlike the
	 * center of mass it lies inside the region also when the region is a crescent or surrounds a hole.
	 *
	 * This is the polylabel search: the region's bounding box is cut into square cells, and a cell is cut into four
	 * again for as long as a point inside it could still beat the best one found.
	 */
	@JvmStatic
	fun pole(region: AkmRegion, gridBits: Int): AkmPoint {
		val outline = region.outline
		var minX = Int.MAX_VALUE
		var minY = Int.MAX_VALUE
		var maxX = Int.MIN_VALUE
		var maxY = Int.MIN_VALUE
		for (i in 0 until outline.pointCount) {
			minX = minOf(minX, outline.x(i))
			maxX = maxOf(maxX, outline.x(i))
			minY = minOf(minY, outline.y(i))
			maxY = maxOf(maxY, outline.y(i))
		}
		val width = (maxX - minX).toDouble()
		val height = (maxY - minY).toDouble()
		val cellSize = minOf(width, height)
		if (cellSize == 0.0) return AkmPoint(minX, minY)
		val precision = (1 shl gridBits).toDouble()

		// Cells in ascending order of how far from an edge a point in them could be; the last is the most promising
		val queue = ArrayList<Cell>()
		fun enqueue(cell: Cell) {
			var low = 0
			var high = queue.size
			while (low < high) {
				val middle = (low + high) ushr 1
				if (queue[middle].potential <= cell.potential) low = middle + 1 else high = middle
			}
			queue.add(low, cell)
		}

		var half = cellSize / 2
		var x = minX.toDouble()
		while (x < maxX) {
			var y = minY.toDouble()
			while (y < maxY) {
				enqueue(Cell(x + half, y + half, half, region))
				y += cellSize
			}
			x += cellSize
		}

		var best = centerOfMass(region)
		val boxCenter = Cell(minX + width / 2, minY + height / 2, 0.0, region)
		if (boxCenter.distance > best.distance) best = boxCenter

		while (queue.isNotEmpty()) {
			val cell = queue.removeAt(queue.size - 1)
			if (cell.distance > best.distance) best = cell
			if (cell.potential - best.distance <= precision) continue
			half = cell.half / 2
			enqueue(Cell(cell.x - half, cell.y - half, half, region))
			enqueue(Cell(cell.x + half, cell.y - half, half, region))
			enqueue(Cell(cell.x - half, cell.y + half, half, region))
			enqueue(Cell(cell.x + half, cell.y + half, half, region))
		}
		return AkmPoint(best.x.roundToInt().coerceIn(minX, maxX), best.y.roundToInt().coerceIn(minY, maxY))
	}

	/**
	 * A square of the search, by its center and half its side.
	 *
	 * @property distance from the center to the nearest edge of the region, negative when the center lies outside
	 * @property potential the largest distance any point of the cell could have
	 */
	private class Cell(val x: Double, val y: Double, val half: Double, region: AkmRegion) {
		val distance: Double = distanceToEdge(x, y, region)
		val potential: Double = distance + half * SQRT_2
	}

	private fun centerOfMass(region: AkmRegion): Cell {
		val outline = region.outline
		var area = 0.0
		var sumX = 0.0
		var sumY = 0.0
		val count = outline.pointCount
		var j = count - 1
		for (i in 0 until count) {
			val ax = outline.x(i).toDouble()
			val ay = outline.y(i).toDouble()
			val bx = outline.x(j).toDouble()
			val by = outline.y(j).toDouble()
			val cross = ax * by - bx * ay
			sumX += (ax + bx) * cross
			sumY += (ay + by) * cross
			area += cross * 3
			j = i
		}
		if (area == 0.0) return Cell(outline.x(0).toDouble(), outline.y(0).toDouble(), 0.0, region)
		return Cell(sumX / area, sumY / area, 0.0, region)
	}

	/** The distance from a point to the nearest edge of [region]: positive inside it, negative outside or in a hole. */
	private fun distanceToEdge(px: Double, py: Double, region: AkmRegion): Double {
		var inside = false
		var nearest = Double.MAX_VALUE
		for (ring in region.rings) {
			val count = ring.pointCount
			var j = count - 1
			for (i in 0 until count) {
				val ax = ring.x(i).toDouble()
				val ay = ring.y(i).toDouble()
				val bx = ring.x(j).toDouble()
				val by = ring.y(j).toDouble()
				// Crossing an edge of any ring flips the answer, so a point in a hole comes out as outside
				if ((ay > py) != (by > py) && px < (bx - ax) * (py - ay) / (by - ay) + ax) inside = !inside
				nearest = minOf(nearest, distanceToSegmentSquared(px, py, ax, ay, bx, by))
				j = i
			}
		}
		return if (inside) sqrt(nearest) else -sqrt(nearest)
	}

	private fun distanceToSegmentSquared(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
		var x = ax
		var y = ay
		val dx = bx - ax
		val dy = by - ay
		if (dx != 0.0 || dy != 0.0) {
			val t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
			if (t > 1) {
				x = bx
				y = by
			} else if (t > 0) {
				x += dx * t
				y += dy * t
			}
		}
		return (px - x) * (px - x) + (py - y) * (py - y)
	}
}
