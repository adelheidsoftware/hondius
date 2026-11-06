package org.adelheid.hondius.akm.converter

import kotlin.math.abs

/**
 * Visvalingam-Whyatt line simplification. Iteratively removes the point that forms
 * the smallest triangle area with its neighbors until all remaining triangles
 * exceed the minimum area threshold.
 *
 * Compared to Ramer-Douglas-Peucker, this algorithm naturally eliminates narrow
 * spikes (which have small triangle areas) and produces smoother, more
 * natural-looking boundaries — preferred in GIS for geographic features.
 */
object VisvalingamWhyatt {

	/** Creates a [LineSimplifier] using the Visvalingam-Whyatt algorithm. */
	fun of(minArea: Float): LineSimplifier = LineSimplifier { points, pinned -> simplify(points, minArea, pinned) }

	/** Simplifies a polyline by iteratively removing the point with the smallest effective area. */
	fun simplify(points: List<FloatArray>, minArea: Float, pinned: BooleanArray?): List<FloatArray> {
		if (points.size <= 3) return points.toMutableList()

		val n = points.size
		val areas = FloatArray(n)
		val prev = IntArray(n) { it - 1 }
		val next = IntArray(n) { it + 1 }
		next[n - 1] = -1
		val removed = BooleanArray(n)

		for (i in 1 until n - 1) {
			areas[i] = triangleArea(points[prev[i]], points[i], points[next[i]])
		}
		areas[0] = Float.MAX_VALUE
		areas[n - 1] = Float.MAX_VALUE
		if (pinned != null) {
			for (i in 0 until n) {
				if (pinned[i]) areas[i] = Float.MAX_VALUE
			}
		}

		while (true) {
			var minIdx = -1
			var minVal = Float.MAX_VALUE
			var i = next[0]
			while (i != -1 && next[i] != -1) {
				if (areas[i] < minVal) {
					minVal = areas[i]
					minIdx = i
				}
				i = next[i]
			}

			if (minIdx == -1 || minVal >= minArea) break

			removed[minIdx] = true
			val p = prev[minIdx]
			val nx = next[minIdx]
			next[p] = nx
			prev[nx] = p

			if (prev[p] != -1) {
				areas[p] = maxOf(triangleArea(points[prev[p]], points[p], points[next[p]]), minVal)
			}
			if (next[nx] != -1) {
				areas[nx] = maxOf(triangleArea(points[prev[nx]], points[nx], points[next[nx]]), minVal)
			}
		}

		val result = ArrayList<FloatArray>(n)
		for (j in 0 until n) {
			if (!removed[j]) result.add(points[j])
		}
		return result
	}

	private fun triangleArea(a: FloatArray, b: FloatArray, c: FloatArray): Float =
		abs((a[0] - c[0]) * (b[1] - a[1]) - (a[0] - b[0]) * (c[1] - a[1])) / 2f
}
