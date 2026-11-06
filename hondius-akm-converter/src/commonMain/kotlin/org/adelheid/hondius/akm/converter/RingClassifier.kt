package org.adelheid.hondius.akm.converter

import org.adelheid.hondius.akm.v1.AkmV1Region
import org.adelheid.hondius.akm.v1.AkmV1Ring
import kotlin.math.abs

/**
 * Classifies raw boundary polygons into exterior and hole rings, then groups them
 * into [AkmV1Region] instances.
 *
 * Uses the signed area (Shoelace formula) to determine winding order and
 * ray-casting point-in-polygon containment to assign holes to exterior rings.
 */
object RingClassifier {

	/**
	 * Classifies a list of raw boundary polygons into AKM regions.
	 *
	 * Polygons with positive signed area (CCW) are treated as exterior boundaries; those
	 * with negative signed area (CW) are treated as holes. Holes are assigned to the
	 * smallest-area exterior ring that contains them. Winding is normalized as needed.
	 */
	fun classify(polygons: List<List<FloatArray>>): List<AkmV1Region> {
		if (polygons.isEmpty()) return emptyList()

		val rings = ArrayList<RingWithArea>()
		for (polygon in polygons) {
			val flat = flatten(polygon)
			if (flat.size < 6) continue
			val ring = AkmV1Ring(flat)
			rings.add(RingWithArea(ring, ring.signedArea()))
		}

		val exteriors = ArrayList<RingWithArea>()
		val holes = ArrayList<RingWithArea>()
		for (rwa in rings) {
			if (rwa.area >= 0) {
				exteriors.add(if (rwa.area > 0) rwa else RingWithArea(reverse(rwa.ring), abs(rwa.area)))
			} else {
				holes.add(rwa)
			}
		}

		var globallyInverted = exteriors.isEmpty()
		if (!globallyInverted && holes.isNotEmpty()) {
			val maxHoleAbs = holes.maxOf { abs(it.area) }
			val maxExtAbs = exteriors.maxOf { abs(it.area) }
			globallyInverted = maxHoleAbs > maxExtAbs
		}
		if (globallyInverted) {
			val all = ArrayList<RingWithArea>(exteriors.size + holes.size)
			for (r in exteriors) all.add(RingWithArea(reverse(r.ring), -r.area))
			for (r in holes) all.add(RingWithArea(reverse(r.ring), -r.area))
			exteriors.clear()
			holes.clear()
			for (r in all) {
				if (r.area >= 0) exteriors.add(r) else holes.add(r)
			}
		}

		val regions = ArrayList<AkmV1Region>(exteriors.size)
		val unassignedHoles = ArrayList(holes)
		for (exterior in exteriors) {
			val regionRings = ArrayList<AkmV1Ring>()
			regionRings.add(exterior.ring)
			val it = unassignedHoles.iterator()
			while (it.hasNext()) {
				val hole = it.next()
				if (containsPoint(exterior.ring, firstPoint(hole.ring))) {
					regionRings.add(hole.ring)
					it.remove()
				}
			}
			regions.add(AkmV1Region(regionRings))
		}

		// Orphan holes become standalone exterior regions (tiny exclaves edge case).
		for (orphan in unassignedHoles) {
			regions.add(AkmV1Region(listOf(reverse(orphan.ring))))
		}

		return regions
	}

	private data class RingWithArea(val ring: AkmV1Ring, val area: Double)

	private fun flatten(points: List<FloatArray>): FloatArray {
		val out = FloatArray(points.size * 2)
		for (i in points.indices) {
			out[i * 2] = points[i][0]
			out[i * 2 + 1] = points[i][1]
		}
		return out
	}

	private fun reverse(ring: AkmV1Ring): AkmV1Ring {
		val original = ring.points
		val reversed = FloatArray(original.size)
		val n = ring.pointCount
		for (i in 0 until n) {
			val ri = n - 1 - i
			reversed[i * 2] = original[ri * 2]
			reversed[i * 2 + 1] = original[ri * 2 + 1]
		}
		return AkmV1Ring(reversed)
	}

	private fun firstPoint(ring: AkmV1Ring): FloatArray = floatArrayOf(ring.x(0), ring.y(0))

	private fun containsPoint(ring: AkmV1Ring, point: FloatArray): Boolean {
		val px = point[0]
		val py = point[1]
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
}
