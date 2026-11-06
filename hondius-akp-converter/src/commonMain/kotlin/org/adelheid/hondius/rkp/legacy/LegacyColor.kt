package org.adelheid.hondius.rkp.legacy

import kotlinx.serialization.Serializable
import org.adelheid.hondius.rkp.RkpColor

/**
 * Legacy RKP color format predating the [RkpColor] structure. Kept for one-way
 * compatibility — the older Adelkrieg releases wrote `.rkp` files with this shape
 * (`id` instead of `order`, no role colors at the top level). New code should not
 * produce this format.
 *
 * Use [toRkpColor] to upgrade.
 */
@Suppress("DEPRECATION")
@Deprecated("Use RkpColor for new code; LegacyColor is read-only fallback for old .rkp files.")
@Serializable
data class LegacyColor(
	val id: Int,
	val name: String,
	val r: Int,
	val g: Int,
	val b: Int,
) {
	init {
		require(name.isNotBlank()) { "LegacyColor name must not be blank" }
		require(r in 0..255) { "r must be 0..255, got $r" }
		require(g in 0..255) { "g must be 0..255, got $g" }
		require(b in 0..255) { "b must be 0..255, got $b" }
	}

	fun toRkpColor(): RkpColor = RkpColor(order = id, name = name, r = r, g = g, b = b)
}
