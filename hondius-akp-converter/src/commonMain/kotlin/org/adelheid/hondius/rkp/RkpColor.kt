package org.adelheid.hondius.rkp

import kotlinx.serialization.Serializable

/**
 * A color entry in an `.rkp` palette file. RKP is JSON-encoded; the [order] field is
 * the explicit sort key (in AKP, position in the file replaces this).
 *
 * Alpha is intentionally absent — RKP never supported it. Conversion to AKP sets alpha to 0xFF.
 */
@Serializable
data class RkpColor(
	val order: Int = Int.MIN_VALUE,
	val name: String,
	val r: Int,
	val g: Int,
	val b: Int,
) : Comparable<RkpColor> {
	init {
		require(name.isNotBlank()) { "RkpColor name must not be blank" }
		require(r in 0..255) { "r must be 0..255, got $r" }
		require(g in 0..255) { "g must be 0..255, got $g" }
		require(b in 0..255) { "b must be 0..255, got $b" }
	}

	override fun compareTo(other: RkpColor): Int = order.compareTo(other.order)
}
