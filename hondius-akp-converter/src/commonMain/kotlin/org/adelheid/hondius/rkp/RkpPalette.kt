package org.adelheid.hondius.rkp

import kotlinx.serialization.Serializable

/**
 * In-memory representation of a parsed `.rkp` JSON palette file.
 *
 * The six "role" colors (text/border/territory/land/water/connection) are renderer
 * defaults. The [sortedColorSet] is the playable palette — colors a player can pick
 * to claim territories. Sorted by [RkpColor.order].
 *
 * Validation:
 * - `name` non-blank
 * - 2 ≤ sortedColorSet.size ≤ 256
 */
@Serializable
data class RkpPalette(
	val name: String,
	val textColor: RkpColor,
	val borderColor: RkpColor,
	val territoryColor: RkpColor,
	val landColor: RkpColor,
	val waterColor: RkpColor,
	val connectionColor: RkpColor,
	val sortedColorSet: List<RkpColor>,
) {
	init {
		require(name.isNotBlank()) { "Palette name must not be blank" }
		require(sortedColorSet.size in MIN_SIZE..MAX_SIZE) {
			"sortedColorSet must have $MIN_SIZE..$MAX_SIZE colors, got ${sortedColorSet.size}"
		}
	}

	val size: Int get() = sortedColorSet.size

	companion object {
		const val MIN_SIZE: Int = 2
		const val MAX_SIZE: Int = 256

		// Default role colors.
		val DEFAULT_TEXT_COLOR = RkpColor(Int.MIN_VALUE, "Text", 116, 79, 40)
		val DEFAULT_BORDER_COLOR = RkpColor(Int.MIN_VALUE, "Border", 116, 79, 40)
		val DEFAULT_TERRITORY_COLOR = RkpColor(Int.MIN_VALUE, "Territory", 224, 219, 227)
		val DEFAULT_LAND_COLOR = RkpColor(Int.MIN_VALUE, "Land", 200, 183, 173)
		val DEFAULT_WATER_COLOR = RkpColor(Int.MIN_VALUE, "Water", 192, 163, 146)
		val DEFAULT_CONNECTION_COLOR = RkpColor(Int.MIN_VALUE, "Connection", 148, 125, 111)

		/** Default palette with the six built-in role colors. Most factory methods below use these. */
		private fun withDefaults(name: String, colors: List<RkpColor>): RkpPalette = RkpPalette(
			name = name,
			textColor = DEFAULT_TEXT_COLOR,
			borderColor = DEFAULT_BORDER_COLOR,
			territoryColor = DEFAULT_TERRITORY_COLOR,
			landColor = DEFAULT_LAND_COLOR,
			waterColor = DEFAULT_WATER_COLOR,
			connectionColor = DEFAULT_CONNECTION_COLOR,
			sortedColorSet = colors,
		)

		/** The default palette. Includes support for most types of color blindness. */
		@kotlin.jvm.JvmStatic
		fun standard16(): RkpPalette = withDefaults("Default-16", listOf(
			RkpColor(0, "Salmon", 255, 140, 150),
			RkpColor(1, "Lavender", 155, 120, 190),
			RkpColor(2, "Thistle", 215, 190, 240),
			RkpColor(3, "Ice", 195, 230, 255),
			RkpColor(4, "Sky", 120, 165, 215),
			RkpColor(5, "Sea", 140, 225, 175),
			RkpColor(6, "Forest", 85, 155, 60),
			RkpColor(7, "Sod", 170, 190, 95),
			RkpColor(8, "Cream", 255, 254, 208),
			RkpColor(9, "Sun", 240, 225, 80),
			RkpColor(10, "Gold", 255, 195, 5),
			RkpColor(11, "Cadmium", 250, 105, 65),
			RkpColor(12, "Sanguine", 95, 10, 0),
			RkpColor(13, "Mocha", 75, 40, 0),
			RkpColor(14, "Matte", 30, 30, 30),
			RkpColor(15, "Cobalt", 0, 50, 120),
		))

		/** The original palette. */
		@kotlin.jvm.JvmStatic
		fun original16(): RkpPalette = withDefaults("Original-16", listOf(
			RkpColor(0, "Cadmium", 240, 130, 50),
			RkpColor(1, "Sun", 241, 224, 82),
			RkpColor(2, "Sod", 171, 191, 94),
			RkpColor(3, "Sea", 140, 198, 183),
			RkpColor(4, "Sky", 122, 165, 217),
			RkpColor(5, "Burgundy", 111, 16, 56),
			RkpColor(6, "Sanguine", 104, 7, 2),
			RkpColor(7, "Bistre", 84, 35, 25),
			RkpColor(8, "Forest", 32, 64, 18),
			RkpColor(9, "Cobalt", 32, 42, 122),
			RkpColor(10, "Indigo", 39, 25, 79),
			RkpColor(11, "Amethyst", 82, 28, 124),
			RkpColor(12, "Gold", 227, 170, 49),
			RkpColor(13, "Tenné", 193, 146, 114),
			RkpColor(14, "Gunmetal", 170, 159, 140),
			RkpColor(15, "Aphotic", 64, 49, 51),
		))

		@kotlin.jvm.JvmStatic
		fun desatur8(): RkpPalette = withDefaults("Desatur-8", listOf(
			RkpColor(0, "Merino", 240, 240, 225),
			RkpColor(1, "Canary", 255, 255, 143),
			RkpColor(2, "Algae", 123, 224, 152),
			RkpColor(3, "Bell", 132, 154, 216),
			RkpColor(4, "Peach", 232, 179, 130),
			RkpColor(5, "Blush", 216, 130, 142),
			RkpColor(6, "Lilac", 167, 118, 193),
			RkpColor(7, "Flint", 118, 114, 120),
		))

		@kotlin.jvm.JvmStatic
		fun pollen8(): RkpPalette = withDefaults("Pollen-8", listOf(
			RkpColor(0, "Verdigris", 52, 172, 186),
			RkpColor(1, "Bermuda", 114, 220, 187),
			RkpColor(2, "Powder", 255, 231, 214),
			RkpColor(3, "Jasmine", 255, 224, 126),
			RkpColor(4, "Flamingo", 255, 167, 165),
			RkpColor(5, "Penk", 238, 106, 124),
			RkpColor(6, "Plum", 171, 86, 117),
			RkpColor(7, "Ferra", 186, 108, 120),
		))

		@kotlin.jvm.JvmStatic
		fun gothic6(): RkpPalette = withDefaults("Gothic-6", listOf(
			RkpColor(0, "Mercury", 230, 230, 236),
			RkpColor(1, "Heather", 193, 193, 210),
			RkpColor(2, "Steel", 128, 128, 164),
			RkpColor(3, "Space", 51, 51, 70),
			RkpColor(4, "Mirage", 26, 26, 36),
			RkpColor(5, "Onyx", 14, 14, 18),
		))
	}
}
