package org.adelheid.hondius.akm.converter

/**
 * Strategy interface for line simplification algorithms.
 * Implementations reduce point count while preserving shape.
 *
 * The tolerance or threshold is baked into the implementation instance, keeping the
 * interface clean. Use the factories on [VisvalingamWhyatt] and [PolygonSimplifier]
 * to create instances.
 */
fun interface LineSimplifier {
	/**
	 * Simplifies a polyline by removing points that contribute little to the overall shape.
	 *
	 * @param points ordered list of `[x, y]` coordinate pairs
	 * @param pinned optional boolean array (same length as `points`) marking vertices that must
	 *   not be removed; `null` if no pinning
	 */
	fun simplify(points: List<FloatArray>, pinned: BooleanArray?): List<FloatArray>
}
