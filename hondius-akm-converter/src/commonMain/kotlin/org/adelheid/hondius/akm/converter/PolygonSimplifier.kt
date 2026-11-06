package org.adelheid.hondius.akm.converter

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Polygon simplification and smoothing algorithms.
 * Pure functions — no side effects. Apply in order: simplify first (reduce point
 * count), then smooth (round corners).
 */
object PolygonSimplifier {

	/** Creates a [LineSimplifier] using the Ramer-Douglas-Peucker algorithm. */
	fun rdp(epsilon: Float): LineSimplifier = LineSimplifier { points, _ -> simplify(points, epsilon) }

	/** Ramer-Douglas-Peucker polygon simplification. */
	fun simplify(points: List<FloatArray>, epsilon: Float): List<FloatArray> {
		if (points.size <= 3) return points.toMutableList()

		var maxDist = 0f
		var maxIndex = 0
		val last = points.size - 1

		for (i in 1 until last) {
			val dist = perpendicularDistance(points[i], points[0], points[last])
			if (dist > maxDist) {
				maxDist = dist
				maxIndex = i
			}
		}

		return if (maxDist > epsilon) {
			val left = simplify(points.subList(0, maxIndex + 1), epsilon)
			val right = simplify(points.subList(maxIndex, points.size), epsilon)
			val result = ArrayList<FloatArray>(left.size + right.size - 1)
			result.addAll(left.subList(0, left.size - 1))
			result.addAll(right)
			result
		} else {
			arrayListOf(points[0], points[last])
		}
	}

	/**
	 * Chaikin's corner-cutting smoothing. Each iteration replaces each edge with two
	 * new points at 25% and 75% along the edge.
	 */
	fun smooth(points: List<FloatArray>, iterations: Int): List<FloatArray> {
		if (points.size < 3) return points.toMutableList()

		var current: List<FloatArray> = points
		repeat(iterations) {
			val smoothed = ArrayList<FloatArray>(current.size * 2)
			val n = current.size
			for (i in 0 until n) {
				val p0 = current[i]
				val p1 = current[(i + 1) % n]
				smoothed.add(floatArrayOf(0.75f * p0[0] + 0.25f * p1[0], 0.75f * p0[1] + 0.25f * p1[1]))
				smoothed.add(floatArrayOf(0.25f * p0[0] + 0.75f * p1[0], 0.25f * p0[1] + 0.75f * p1[1]))
			}
			current = smoothed
		}
		return current
	}

	/**
	 * Taubin λ-μ smoothing for open polylines. Alternates a forward Laplacian step (λ)
	 * with a backward step (μ) each iteration, preventing shrinkage. First and last
	 * points are kept fixed.
	 */
	fun smoothOpenTaubin(points: List<FloatArray>, iterations: Int, pinned: BooleanArray?): List<FloatArray> {
		if (points.size < 3 || iterations <= 0) return points.toMutableList()

		val lambda = 0.33f
		val mu = -0.34f

		val pts = ArrayList<FloatArray>(points.size)
		for (p in points) pts.add(floatArrayOf(p[0], p[1]))

		repeat(iterations) {
			laplacianPass(pts, lambda, pinned)
			laplacianPass(pts, mu, pinned)
		}
		return pts
	}

	private fun laplacianPass(pts: MutableList<FloatArray>, factor: Float, pinned: BooleanArray?) {
		val n = pts.size
		val dx = FloatArray(n)
		val dy = FloatArray(n)
		for (i in 1 until n - 1) {
			if (pinned != null && pinned[i]) continue
			val avgX = (pts[i - 1][0] + pts[i + 1][0]) / 2f
			val avgY = (pts[i - 1][1] + pts[i + 1][1]) / 2f
			dx[i] = factor * (avgX - pts[i][0])
			dy[i] = factor * (avgY - pts[i][1])
		}
		for (i in 1 until n - 1) {
			pts[i][0] += dx[i]
			pts[i][1] += dy[i]
		}
	}

	private fun perpendicularDistance(point: FloatArray, lineStart: FloatArray, lineEnd: FloatArray): Float {
		val dx = lineEnd[0] - lineStart[0]
		val dy = lineEnd[1] - lineStart[1]
		val lengthSq = dx * dx + dy * dy

		if (lengthSq == 0f) {
			val ddx = point[0] - lineStart[0]
			val ddy = point[1] - lineStart[1]
			return sqrt(ddx * ddx + ddy * ddy)
		}

		val numerator = abs(dy * point[0] - dx * point[1] + lineEnd[0] * lineStart[1] - lineEnd[1] * lineStart[0])
		return numerator / sqrt(lengthSq)
	}
}
