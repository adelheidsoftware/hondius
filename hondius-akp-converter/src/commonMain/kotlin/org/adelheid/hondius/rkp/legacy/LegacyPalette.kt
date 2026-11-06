package org.adelheid.hondius.rkp.legacy

import kotlinx.serialization.Serializable
import org.adelheid.hondius.rkp.RkpColor
import org.adelheid.hondius.rkp.RkpPalette

/**
 * Legacy palette format predating the modern [RkpPalette] shape — just a name and a
 * set of legacy colors, no role colors. Read-only fallback when modern decoding fails.
 *
 * Use [toRkpPalette] to upgrade; defaults are filled in for the six role colors.
 */
@Suppress("DEPRECATION")
@Deprecated("Use RkpPalette for new code; LegacyPalette is read-only fallback for old .rkp files.")
@Serializable
data class LegacyPalette(
	val name: String,
	val set: List<LegacyColor>,
) {
	fun toRkpPalette(): RkpPalette {
		val upgraded = set.sortedBy { it.id }.map { it.toRkpColor() }
		return RkpPalette(
			name = name,
			textColor = DEFAULT_TEXT_COLOR,
			borderColor = DEFAULT_BORDER_COLOR,
			territoryColor = DEFAULT_TERRITORY_COLOR,
			landColor = DEFAULT_LAND_COLOR,
			waterColor = DEFAULT_WATER_COLOR,
			connectionColor = DEFAULT_CONNECTION_COLOR,
			sortedColorSet = upgraded,
		)
	}

	companion object {
		// The same defaults as RkpPalette's.
		val DEFAULT_TEXT_COLOR = RkpColor(Int.MIN_VALUE, "Text", 116, 79, 40)
		val DEFAULT_BORDER_COLOR = RkpColor(Int.MIN_VALUE, "Border", 116, 79, 40)
		val DEFAULT_TERRITORY_COLOR = RkpColor(Int.MIN_VALUE, "Territory", 224, 219, 227)
		val DEFAULT_LAND_COLOR = RkpColor(Int.MIN_VALUE, "Land", 200, 183, 173)
		val DEFAULT_WATER_COLOR = RkpColor(Int.MIN_VALUE, "Water", 192, 163, 146)
		val DEFAULT_CONNECTION_COLOR = RkpColor(Int.MIN_VALUE, "Connection", 148, 125, 111)
	}
}
