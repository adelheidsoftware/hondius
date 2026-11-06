package org.adelheid.hondius.rkp.codec

import org.adelheid.hondius.rkp.RkpPalette
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip tests for the RKP encoder + decoder pair plus the built-in palette factories.
 *
 * They make sure the encoder and the decoder agree with each other.
 */
class RkpEncoderTest {

	@Test
	fun standard16RoundTrip() = assertRoundTrip(RkpPalette.standard16(), expectedSize = 16)

	@Test
	fun original16RoundTrip() = assertRoundTrip(RkpPalette.original16(), expectedSize = 16)

	@Test
	fun desatur8RoundTrip() = assertRoundTrip(RkpPalette.desatur8(), expectedSize = 8)

	@Test
	fun pollen8RoundTrip() = assertRoundTrip(RkpPalette.pollen8(), expectedSize = 8)

	@Test
	fun gothic6RoundTrip() = assertRoundTrip(RkpPalette.gothic6(), expectedSize = 6)

	@Test
	fun encoderIsDeterministic() {
		val palette = RkpPalette.standard16()
		assertContentEquals(RkpEncoder.encode(palette), RkpEncoder.encode(palette))
	}

	@Test
	fun encodedJsonContainsExpectedFields() {
		val json = RkpEncoder.encodeToString(RkpPalette.standard16())
		// Sanity: the structural field names of the `.rkp` layout are present.
		assertTrue("\"name\"" in json)
		assertTrue("\"textColor\"" in json)
		assertTrue("\"borderColor\"" in json)
		assertTrue("\"territoryColor\"" in json)
		assertTrue("\"landColor\"" in json)
		assertTrue("\"waterColor\"" in json)
		assertTrue("\"connectionColor\"" in json)
		assertTrue("\"sortedColorSet\"" in json)
		assertTrue("\"Salmon\"" in json)
		assertTrue("\"Cobalt\"" in json)
	}

	private fun assertRoundTrip(palette: RkpPalette, expectedSize: Int) {
		assertEquals(expectedSize, palette.size, "palette has expected color count")
		val encoded = RkpEncoder.encode(palette)
		val decoded = RkpDecoder.decode(encoded)

		assertEquals(palette.name, decoded.name)
		assertEquals(palette.size, decoded.size)
		assertEquals(palette.sortedColorSet, decoded.sortedColorSet)
		assertEquals(palette.textColor, decoded.textColor)
		assertEquals(palette.borderColor, decoded.borderColor)
		assertEquals(palette.territoryColor, decoded.territoryColor)
		assertEquals(palette.landColor, decoded.landColor)
		assertEquals(palette.waterColor, decoded.waterColor)
		assertEquals(palette.connectionColor, decoded.connectionColor)

		// Re-encoding the decoded result must be byte-stable.
		assertContentEquals(encoded, RkpEncoder.encode(decoded))
	}
}
