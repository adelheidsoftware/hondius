package org.adelheid.hondius.akp.converter

import org.adelheid.hondius.akp.AkpColor
import org.adelheid.hondius.akp.codec.AkpDecoder
import org.adelheid.hondius.akp.codec.AkpEncoder
import org.adelheid.hondius.rkp.RkpColor
import org.adelheid.hondius.rkp.RkpPalette
import org.adelheid.hondius.rkp.codec.RkpDecoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end RKP → AKP conversion + AKP encode/decode round-trip tests.
 *
 * Lives in commonTest so it runs on jvm, jsNode, and wasmJs targets — no resource
 * loading needed since the test fixtures are built in code.
 */
class RkpToAkpConverterTest {

	// --- Conversion correctness ---

	@Test
	fun convertsStandard16Palette() {
		val rkp = standard16Rkp()
		val akp = RkpToAkpConverter.convert(rkp)

		assertEquals("Default-16", akp.metadata.name)
		assertEquals(RkpToAkpConverter.GENERATOR, akp.metadata.generator)
		assertEquals(16, akp.playerColors.size)
		assertEquals(6, akp.roleColors.size)
		// First player color should match the lowest-order RKP color (Salmon).
		assertEquals("Salmon", akp.playerColors[0].name)
		assertEquals(255, akp.playerColors[0].r)
		// All alpha values default to 0xFF (RKP is RGB only).
		akp.playerColors.forEach { assertEquals(0xFF, it.a, "player color ${it.name} should be opaque") }
		akp.roleColors.forEach { assertEquals(0xFF, it.a, "role color ${it.name} should be opaque") }
	}

	@Test
	fun mapsRoleColorsToAkmLayerNames() {
		val rkp = standard16Rkp()
		val akp = RkpToAkpConverter.convert(rkp)

		// Each RKP role color should land at the role named after the part of a map it colors.
		assertColorMatches(rkp.waterColor, akp.water)
		assertColorMatches(rkp.landColor, akp.land)
		assertColorMatches(rkp.connectionColor, akp.connections)
		assertColorMatches(rkp.territoryColor, akp.territories)
		assertColorMatches(rkp.borderColor, akp.borders)
		assertColorMatches(rkp.textColor, akp.labels) // textColor → labels
	}

	@Test
	fun preservesPlayerColorOrderFromRkpOrderField() {
		// Build an RKP with a deliberately scrambled `order` field and confirm the converter
		// sorts by order before producing the AKP playerColors list.
		val rkp = standard16Rkp().copy(
			sortedColorSet = listOf(
				RkpColor(order = 3, name = "Third", r = 30, g = 30, b = 30),
				RkpColor(order = 0, name = "First", r = 0, g = 0, b = 0),
				RkpColor(order = 2, name = "Second", r = 20, g = 20, b = 20),
				// Need at least 2 colors; pad to meet MIN_SIZE.
			)
		)
		val akp = RkpToAkpConverter.convert(rkp)
		assertEquals(listOf("First", "Second", "Third"), akp.playerColors.map { it.name })
	}

	@Test
	fun convertedPaletteEncodesAndRoundTripsThroughAkp() {
		val rkp = original16Rkp()
		val akp = RkpToAkpConverter.convert(rkp)
		val bytes = AkpEncoder.encode(akp)
		val decoded = AkpDecoder.decode(bytes)

		assertEquals(akp, decoded)

		// A palette has one encoding, so encoding what was decoded gives the same bytes.
		val reencoded = AkpEncoder.encode(decoded)
		assertContentEquals(bytes, reencoded)
	}

	// --- JSON decode coverage ---

	@Test
	fun decodesJsonRkpThenConvertsToAkp() {
		val json = """
			{
			  "name": "Tiny",
			  "textColor":       { "order": 0, "name": "Text",       "r": 10, "g": 10, "b": 10 },
			  "borderColor":     { "order": 0, "name": "Border",     "r": 20, "g": 20, "b": 20 },
			  "territoryColor":  { "order": 0, "name": "Territory",  "r": 30, "g": 30, "b": 30 },
			  "landColor":       { "order": 0, "name": "Land",       "r": 40, "g": 40, "b": 40 },
			  "waterColor":      { "order": 0, "name": "Water",      "r": 50, "g": 50, "b": 50 },
			  "connectionColor": { "order": 0, "name": "Connection", "r": 60, "g": 60, "b": 60 },
			  "sortedColorSet": [
			    { "order": 0, "name": "A", "r": 100, "g": 0,   "b": 0 },
			    { "order": 1, "name": "B", "r": 0,   "g": 100, "b": 0 },
			    { "order": 2, "name": "C", "r": 0,   "g": 0,   "b": 100 }
			  ]
			}
			""".trimIndent()
		val rkp = RkpDecoder.decode(json)
		assertEquals("Tiny", rkp.name)
		assertEquals(3, rkp.sortedColorSet.size)

		val akp = RkpToAkpConverter.convert(rkp)
		assertEquals(50, akp.water.r)
		assertEquals(40, akp.land.r)
		assertEquals(60, akp.connections.r)
		assertEquals(30, akp.territories.r)
		assertEquals(20, akp.borders.r)
		assertEquals(10, akp.labels.r) // textColor → labels
		assertEquals(listOf("A", "B", "C"), akp.playerColors.map { it.name })
	}

	@Test
	fun decoderFallsBackToLegacyPaletteSchemaWhenModernFailsToParse() {
		// The legacy schema has only `name` + `set` of `{id, name, r, g, b}`. Decoder should
		// auto-upgrade it to RkpPalette with the six default role colors.
		val legacyJson = """
			{
			  "name": "Old-Style",
			  "set": [
			    { "id": 0, "name": "Red",   "r": 255, "g": 0,   "b": 0 },
			    { "id": 1, "name": "Green", "r": 0,   "g": 255, "b": 0 },
			    { "id": 2, "name": "Blue",  "r": 0,   "g": 0,   "b": 255 }
			  ]
			}
			""".trimIndent()
		val rkp = RkpDecoder.decode(legacyJson)
		assertEquals("Old-Style", rkp.name)
		assertEquals(3, rkp.sortedColorSet.size)
		assertEquals(listOf("Red", "Green", "Blue"), rkp.sortedColorSet.map { it.name })
		// Default role colors should be in place after upgrade
		assertEquals(116, rkp.borderColor.r)
		assertEquals(192, rkp.waterColor.r)

		// And the upgraded RKP should convert cleanly into an AKP.
		val akp = RkpToAkpConverter.convert(rkp)
		assertEquals(3, akp.playerColors.size)
		assertEquals(116, akp.borders.r)
	}

	// --- Helpers ---

	private fun assertColorMatches(rkp: RkpColor, akp: AkpColor) {
		assertEquals(rkp.r, akp.r, "r mismatch for role '${akp.name}'")
		assertEquals(rkp.g, akp.g, "g mismatch for role '${akp.name}'")
		assertEquals(rkp.b, akp.b, "b mismatch for role '${akp.name}'")
		assertTrue(akp.a == 0xFF, "alpha must default to 0xFF on RKP → AKP conversion")
	}

	/** The standard sixteen-color palette, built by hand. */
	private fun standard16Rkp(): RkpPalette = RkpPalette(
		name = "Default-16",
		textColor = RkpColor(0, "Text", 116, 79, 40),
		borderColor = RkpColor(0, "Border", 116, 79, 40),
		territoryColor = RkpColor(0, "Territory", 224, 219, 227),
		landColor = RkpColor(0, "Land", 200, 183, 173),
		waterColor = RkpColor(0, "Water", 192, 163, 146),
		connectionColor = RkpColor(0, "Connection", 148, 125, 111),
		sortedColorSet = listOf(
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
		),
	)

	/** Mirrors `RkpPalette.original16()`. */
	private fun original16Rkp(): RkpPalette = standard16Rkp().copy(
		name = "Original-16",
		sortedColorSet = listOf(
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
		),
	)
}
