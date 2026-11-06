package org.adelheid.hondius.akm

import kotlin.jvm.JvmStatic

/**
 * A stretch of border that two territories have in common: consecutive edges that are in both their outlines.
 *
 * @property territoryA the territory with the lower index
 * @property territoryB the territory with the higher index
 * @property points the stretch as `[x0, y0, x1, y1, ...]` in grid units, in the direction [territoryA]'s ring runs.
 *   When a whole ring is shared, the first point is repeated at the end.
 */
class SharedBorder(val territoryA: Int, val territoryB: Int, val points: IntArray) {
	val pointCount: Int get() = points.size / 2

	override fun toString(): String = "SharedBorder($territoryA, $territoryB, $pointCount points)"
}

/**
 * A place where two territories touch without sharing a vertex: a vertex of [territory] lies inside an edge of
 * [otherTerritory]. The border is not written with the same vertices on both sides there, so a gap or an overlap
 * can open between the two when their outlines are transformed.
 */
data class BorderContact(val territory: Int, val otherTerritory: Int, val x: Int, val y: Int)

/**
 * What the outlines of a map say about its borders. Coordinates are whole numbers, so a vertex two territories
 * share is exactly the same value in both, and borders can be found by comparison alone.
 */
object AkmBorders {
	private data class Edge(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

	/** The edge between two points, the same value whichever way it is walked. */
	private fun edge(x1: Int, y1: Int, x2: Int, y2: Int): Edge =
		if (x1 < x2 || (x1 == x2 && y1 <= y2)) Edge(x1, y1, x2, y2) else Edge(x2, y2, x1, y1)

	private inline fun forEachRing(map: AkmMap, action: (territory: Int, ring: AkmRing) -> Unit) {
		for ((index, territory) in map.territories.withIndex()) {
			for (region in territory.regions) {
				for (ring in region.rings) action(index, ring)
			}
		}
	}

	/**
	 * Every stretch of border that two territories share, each once. A reader can draw each of them a single time,
	 * and tell a border between two territories from one that faces water or land of no territory.
	 */
	@JvmStatic
	fun shared(map: AkmMap): List<SharedBorder> {
		val owners = HashMap<Edge, IntArray>()
		forEachRing(map) { territory, ring ->
			for (i in 0 until ring.pointCount) {
				val next = (i + 1) % ring.pointCount
				val key = edge(ring.x(i), ring.y(i), ring.x(next), ring.y(next))
				val known = owners[key]
				owners[key] = if (known == null) intArrayOf(territory) else known + territory
			}
		}

		val borders = ArrayList<SharedBorder>()
		forEachRing(map) { territory, ring ->
			val count = ring.pointCount
			// For each edge, the territory on its other side when exactly one other territory has the same edge
			val partner = IntArray(count) { i ->
				val next = (i + 1) % count
				val edgeOwners = owners.getValue(edge(ring.x(i), ring.y(i), ring.x(next), ring.y(next)))
				when {
					edgeOwners.size != 2 -> -1
					edgeOwners[0] == territory && edgeOwners[1] != territory -> edgeOwners[1]
					edgeOwners[1] == territory && edgeOwners[0] != territory -> edgeOwners[0]
					else -> -1
				}
			}
			val start = (0 until count).firstOrNull { partner[it] != partner[(it + count - 1) % count] }
			if (start == null) {
				// One partner all the way round: the whole ring is one border
				if (partner[0] > territory) {
					borders.add(SharedBorder(territory, partner[0], ring.points + intArrayOf(ring.x(0), ring.y(0))))
				}
			} else {
				var i = 0
				while (i < count) {
					val first = (start + i) % count
					val other = partner[first]
					var length = 1
					while (i + length < count && partner[(start + i + length) % count] == other) length++
					if (other > territory) {
						val points = IntArray((length + 1) * 2)
						for (step in 0..length) {
							val vertex = (first + step) % count
							points[step * 2] = ring.x(vertex)
							points[step * 2 + 1] = ring.y(vertex)
						}
						borders.add(SharedBorder(territory, other, points))
					}
					i += length
				}
			}
		}
		return borders
	}

	/**
	 * Every vertex of a territory that lies inside an edge of another territory. A map whose neighbors are written
	 * with the same vertices along their borders has none.
	 */
	@JvmStatic
	fun contacts(map: AkmMap): List<BorderContact> {
		// Edges are looked up by the cells of a coarse grid that their bounding boxes cover
		val cell = 64 shl map.gridBits
		val columns = (map.canvasWidth shl map.gridBits) / cell + 1
		val buckets = HashMap<Int, ArrayList<IntArray>>()
		forEachRing(map) { territory, ring ->
			for (i in 0 until ring.pointCount) {
				val next = (i + 1) % ring.pointCount
				val segment = intArrayOf(ring.x(i), ring.y(i), ring.x(next), ring.y(next), territory)
				for (cellY in minOf(segment[1], segment[3]) / cell..maxOf(segment[1], segment[3]) / cell) {
					for (cellX in minOf(segment[0], segment[2]) / cell..maxOf(segment[0], segment[2]) / cell) {
						buckets.getOrPut(cellY * columns + cellX) { ArrayList() }.add(segment)
					}
				}
			}
		}

		val contacts = LinkedHashSet<BorderContact>()
		forEachRing(map) { territory, ring ->
			for (i in 0 until ring.pointCount) {
				val x = ring.x(i)
				val y = ring.y(i)
				for (segment in buckets[(y / cell) * columns + x / cell].orEmpty()) {
					if (segment[4] != territory && liesInside(x, y, segment)) contacts.add(BorderContact(territory, segment[4], x, y))
				}
			}
		}
		return contacts.toList()
	}

	/** True when the point is on the segment and is neither of its ends. */
	private fun liesInside(x: Int, y: Int, segment: IntArray): Boolean {
		val x1 = segment[0]
		val y1 = segment[1]
		val x2 = segment[2]
		val y2 = segment[3]
		if ((x == x1 && y == y1) || (x == x2 && y == y2)) return false
		if (x < minOf(x1, x2) || x > maxOf(x1, x2) || y < minOf(y1, y2) || y > maxOf(y1, y2)) return false
		return (x2 - x1).toLong() * (y - y1) == (y2 - y1).toLong() * (x - x1)
	}
}
