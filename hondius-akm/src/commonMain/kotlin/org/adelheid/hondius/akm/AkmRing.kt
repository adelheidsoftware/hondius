package org.adelheid.hondius.akm

import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException

/** A point on the canvas, in grid units. */
data class AkmPoint(val x: Int, val y: Int)

/**
 * A closed polygon, as `[x0, y0, x1, y1, ...]` in grid units. Its last point joins its first, which is not repeated.
 *
 * An outline has a positive [signedArea2] and a hole a negative one. With y growing downward, an outline therefore
 * runs clockwise on screen.
 */
class AkmRing(val points: IntArray) {
	init {
		if (points.size < 6 || points.size % 2 != 0) {
			throw FormatException(FormatError.BAD_GEOMETRY, "A ring has at least 3 points, as x and y pairs; got ${points.size} values")
		}
	}

	val pointCount: Int get() = points.size / 2

	fun x(index: Int): Int = points[index * 2]
	fun y(index: Int): Int = points[index * 2 + 1]

	/**
	 * Twice the signed area: the sum over the points, in order, of `x[i] * y[i+1] - x[i+1] * y[i]`. Exact, since
	 * coordinates are whole numbers.
	 */
	fun signedArea2(): Long {
		var sum = 0L
		val count = pointCount
		for (i in 0 until count) {
			val next = if (i + 1 == count) 0 else i + 1
			sum += x(i).toLong() * y(next) - x(next).toLong() * y(i)
		}
		return sum
	}

	/** The same points in the opposite direction, which turns an outline into a hole and back. */
	fun reversed(): AkmRing {
		val count = pointCount
		val out = IntArray(points.size)
		for (i in 0 until count) {
			out[i * 2] = x(count - 1 - i)
			out[i * 2 + 1] = y(count - 1 - i)
		}
		return AkmRing(out)
	}

	override fun equals(other: Any?): Boolean = other is AkmRing && points.contentEquals(other.points)
	override fun hashCode(): Int = points.contentHashCode()
	override fun toString(): String = "AkmRing($pointCount points)"
}

/** An open run of at least two points, as `[x0, y0, x1, y1, ...]` in grid units. Its ends are not joined. */
class AkmLine(val points: IntArray) {
	init {
		if (points.size < 4 || points.size % 2 != 0) {
			throw FormatException(FormatError.BAD_GEOMETRY, "A line has at least 2 points, as x and y pairs; got ${points.size} values")
		}
	}

	val pointCount: Int get() = points.size / 2

	override fun equals(other: Any?): Boolean = other is AkmLine && points.contentEquals(other.points)
	override fun hashCode(): Int = points.contentHashCode()
	override fun toString(): String = "AkmLine($pointCount points)"
}
