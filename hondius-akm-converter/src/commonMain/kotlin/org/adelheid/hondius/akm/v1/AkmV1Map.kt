package org.adelheid.hondius.akm.v1

import org.adelheid.hondius.akm.BlendMode
import org.adelheid.hondius.akm.ImageFormat

/** An author record of a version 1 file: an id, and the display name at the time the file was written. */
data class AkmV1Author(val authorId: String, val name: String)

/**
 * A closed polygon of a version 1 file, as alternating `[x0, y0, x1, y1, ...]` floats in pixels. An exterior
 * boundary has a positive [signedArea], a hole a negative one.
 */
class AkmV1Ring(val points: FloatArray) {
	init {
		require(points.size >= 6 && points.size % 2 == 0) {
			"Ring must have at least 3 points (6 floats) and even length, got ${points.size}"
		}
	}

	val pointCount: Int get() = points.size / 2

	fun x(index: Int): Float = points[index * 2]
	fun y(index: Int): Float = points[index * 2 + 1]

	/** Signed area via the shoelace formula. */
	fun signedArea(): Double {
		var area = 0.0
		val n = pointCount
		for (i in 0 until n) {
			val j = (i + 1) % n
			area += x(i).toDouble() * y(j).toDouble()
			area -= x(j).toDouble() * y(i).toDouble()
		}
		return area / 2.0
	}

	override fun equals(other: Any?): Boolean = other is AkmV1Ring && points.contentEquals(other.points)
	override fun hashCode(): Int = points.contentHashCode()
}

/** A contiguous landmass of a version 1 territory: ring 0 is the exterior boundary, the others are holes. */
data class AkmV1Region(val rings: List<AkmV1Ring>) {
	init {
		require(rings.isNotEmpty()) { "Region must have at least one ring (exterior boundary)" }
	}

	val exterior: AkmV1Ring get() = rings.first()

	val holes: List<AkmV1Ring> get() = rings.drop(1)
}

data class AkmV1Territory(
	val territoryId: String,
	val label: String,
	val centroidX: Float,
	val centroidY: Float,
	val regions: List<AkmV1Region>,
)

data class AkmV1Edge(val sourceId: String, val targetId: String, val bidirectional: Boolean)

class AkmV1Layer(
	val name: String,
	val zOrder: Int,
	val blendMode: BlendMode,
	val opacity: Int,
	val imageFormat: ImageFormat,
	val imageData: ByteArray,
)

/**
 * A version 1 `.akm` file as it was written. [AkmV1Converter] turns it into the current format; a host that migrates
 * stored files reads here what the current format no longer holds: the id, the description, the authors and the
 * creation time.
 */
class AkmV1Map(
	val mapId: String,
	val canvasWidth: Int,
	val canvasHeight: Int,
	val displayName: String,
	val description: String,
	val authors: List<AkmV1Author>,
	val createdAtEpochSeconds: Long,
	val editorVersion: String,
	val territories: List<AkmV1Territory>,
	val edges: List<AkmV1Edge>,
	val layers: List<AkmV1Layer>,
)
