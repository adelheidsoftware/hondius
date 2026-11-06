package org.adelheid.hondius.akm.converter

import org.adelheid.hondius.akm.v1.AkmV1Region
import org.adelheid.hondius.akm.v1.AkmV1Ring
import kotlin.math.sqrt

/**
 * Finds the "pole of inaccessibility" — the point inside a polygon region that is
 * farthest from any edge. Based on Mapbox's polylabel algorithm.
 *
 * Produces better visual centroids than center-of-mass, especially for crescent-shaped
 * territories or those with large holes.
 */
object PolyLabel {

	/** Finds the pole of inaccessibility for a region (exterior + holes). */
	fun findPole(region: AkmV1Region, precision: Float): FloatArray {
		val exterior = region.exterior
		val holes = region.holes

		var minX = Float.MAX_VALUE
		var minY = Float.MAX_VALUE
		var maxX = -Float.MAX_VALUE
		var maxY = -Float.MAX_VALUE
		for (i in 0 until exterior.pointCount) {
			val x = exterior.x(i)
			val y = exterior.y(i)
			if (x < minX) minX = x
			if (x > maxX) maxX = x
			if (y < minY) minY = y
			if (y > maxY) maxY = y
		}

		val w = maxX - minX
		val h = maxY - minY
		val cellSize = maxOf(w, h)
		if (cellSize == 0f) return floatArrayOf(minX, minY)

		var halfCell = cellSize / 2f

		// Max-heap ordered by maxPotential descending. Using a manually-sorted ArrayList instead
		// of java.util.PriorityQueue so the algorithm works in commonMain.
		val queue = ArrayList<Cell>()
		fun enqueue(c: Cell) {
			// Insertion sort by descending maxPotential. The queue is short relative to total
			// work; this avoids pulling in a heap implementation for a multiplatform target.
			var idx = queue.size
			while (idx > 0 && queue[idx - 1].maxPotential < c.maxPotential) idx--
			queue.add(idx, c)
		}

		var x = minX
		while (x < maxX) {
			var y = minY
			while (y < maxY) {
				enqueue(Cell(x + halfCell, y + halfCell, halfCell, exterior, holes))
				y += cellSize
			}
			x += cellSize
		}

		var bestCell = centroidCell(exterior, holes)
		val bbCenter = Cell(minX + w / 2f, minY + h / 2f, 0f, exterior, holes)
		if (bbCenter.dist > bestCell.dist) bestCell = bbCenter

		while (queue.isNotEmpty()) {
			val cell = queue.removeAt(0)
			if (cell.dist > bestCell.dist) bestCell = cell
			if (cell.maxPotential - bestCell.dist <= precision) continue

			halfCell = cell.halfSize / 2f
			enqueue(Cell(cell.x - halfCell, cell.y - halfCell, halfCell, exterior, holes))
			enqueue(Cell(cell.x + halfCell, cell.y - halfCell, halfCell, exterior, holes))
			enqueue(Cell(cell.x - halfCell, cell.y + halfCell, halfCell, exterior, holes))
			enqueue(Cell(cell.x + halfCell, cell.y + halfCell, halfCell, exterior, holes))
		}

		return floatArrayOf(bestCell.x, bestCell.y)
	}

	private fun centroidCell(exterior: AkmV1Ring, holes: List<AkmV1Ring>): Cell {
		val c = RingClassifier.computeCentroid(exterior)
		return Cell(c[0], c[1], 0f, exterior, holes)
	}

	private class Cell(val x: Float, val y: Float, val halfSize: Float, exterior: AkmV1Ring, holes: List<AkmV1Ring>) {
		val dist: Float = pointToPolygonDist(x, y, exterior, holes)
		val maxPotential: Float = dist + halfSize * sqrt(2f)
	}

	private fun pointToPolygonDist(px: Float, py: Float, exterior: AkmV1Ring, holes: List<AkmV1Ring>): Float {
		var dist = signedDistToRing(px, py, exterior, isExterior = true)
		for (hole in holes) {
			val holeDist = signedDistToRing(px, py, hole, isExterior = false)
			if (holeDist < dist) dist = holeDist
		}
		return dist
	}

	private fun signedDistToRing(px: Float, py: Float, ring: AkmV1Ring, isExterior: Boolean): Float {
		val inside = pointInRing(px, py, ring)
		var minDistSq = Float.MAX_VALUE
		val n = ring.pointCount
		var j = n - 1
		for (i in 0 until n) {
			val ax = ring.x(i)
			val ay = ring.y(i)
			val bx = ring.x(j)
			val by = ring.y(j)
			val dSq = pointToSegmentDistSq(px, py, ax, ay, bx, by)
			if (dSq < minDistSq) minDistSq = dSq
			j = i
		}
		val minDist = sqrt(minDistSq)
		return if (isExterior) {
			if (inside) minDist else -minDist
		} else {
			if (inside) -minDist else minDist
		}
	}

	private fun pointInRing(px: Float, py: Float, ring: AkmV1Ring): Boolean {
		var inside = false
		val n = ring.pointCount
		var j = n - 1
		for (i in 0 until n) {
			val xi = ring.x(i)
			val yi = ring.y(i)
			val xj = ring.x(j)
			val yj = ring.y(j)
			if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) {
				inside = !inside
			}
			j = i
		}
		return inside
	}

	private fun pointToSegmentDistSq(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
		val dx = bx - ax
		val dy = by - ay
		val lenSq = dx * dx + dy * dy
		if (lenSq == 0f) {
			val ddx = px - ax
			val ddy = py - ay
			return ddx * ddx + ddy * ddy
		}
		var t = ((px - ax) * dx + (py - ay) * dy) / lenSq
		t = t.coerceIn(0f, 1f)
		val projX = ax + t * dx
		val projY = ay + t * dy
		val ddx = px - projX
		val ddy = py - projY
		return ddx * ddx + ddy * ddy
	}
}
