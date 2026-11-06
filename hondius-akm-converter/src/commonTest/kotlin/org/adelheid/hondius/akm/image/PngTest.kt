package org.adelheid.hondius.akm.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the PNG encoder writes, on every target: a palette where one fits, and nothing but pixels. */
class PngTest {
	// In a PNG, the header chunk's bit depth and color type are the bytes at offsets 24 and 25
	private fun bitDepth(png: ByteArray): Int = png[24].toInt()
	private fun colorType(png: ByteArray): Int = png[25].toInt()

	private fun chunkTypes(png: ByteArray): List<String> {
		val types = ArrayList<String>()
		var position = 8
		while (position < png.size) {
			val length = ((png[position].toInt() and 0xFF) shl 24) or ((png[position + 1].toInt() and 0xFF) shl 16) or
				((png[position + 2].toInt() and 0xFF) shl 8) or (png[position + 3].toInt() and 0xFF)
			types.add((4 until 8).map { (png[position + it].toInt() and 0xFF).toChar() }.joinToString(""))
			position += 12 + length
		}
		return types
	}

	private val opaque = 0xFF000000.toInt()

	@Test
	fun aOneColorMaskIsWrittenWithAOneBitPalette() {
		val pixels = IntArray(67 * 5) { if (it % 3 == 0) opaque or 0x112233 else 0 }
		val png = encodePng(imageBufferOf(67, 5, pixels))
		assertEquals(3, colorType(png))
		assertEquals(1, bitDepth(png))
		assertContentEquals(pixels, decodePng(png).toArgbArray())
	}

	@Test
	fun upToSixteenColorsTakeFourBitsAndUpToTwoHundredFiftySixTakeEight() {
		val sixteen = IntArray(40 * 3) { opaque or ((it % 16) * 0x101010) }
		val small = encodePng(imageBufferOf(40, 3, sixteen))
		assertEquals(3, colorType(small))
		assertEquals(4, bitDepth(small))
		assertContentEquals(sixteen, decodePng(small).toArgbArray())

		val many = IntArray(32 * 8) { opaque or (it * 0x010203) }
		val large = encodePng(imageBufferOf(32, 8, many))
		assertEquals(3, colorType(large))
		assertEquals(8, bitDepth(large))
		assertContentEquals(many, decodePng(large).toArgbArray())
	}

	@Test
	fun moreThanTwoHundredFiftySixColorsAreWrittenAsRgba() {
		val pixels = IntArray(20 * 20) { opaque or (it * 0x010307) }
		val png = encodePng(imageBufferOf(20, 20, pixels))
		assertEquals(6, colorType(png))
		assertEquals(8, bitDepth(png))
		assertContentEquals(pixels, decodePng(png).toArgbArray())
	}

	@Test
	fun fullyTransparentPixelsAreOneColorWhateverTheirRgb() {
		val pixels = IntArray(16) { if (it < 8) it * 0x111111 else opaque or 0xABCDEF }
		val png = encodePng(imageBufferOf(4, 4, pixels))
		assertEquals(1, bitDepth(png))
		val decoded = decodePng(png).toArgbArray()
		assertContentEquals(IntArray(16) { if (it < 8) 0 else opaque or 0xABCDEF }, decoded)
	}

	@Test
	fun onlyTheChunksThatHoldPixelsAreWritten() {
		val allowed = setOf("IHDR", "PLTE", "tRNS", "IDAT", "IEND")
		val mask = encodePng(imageBufferOf(8, 8, IntArray(64) { if (it % 2 == 0) opaque else 0 }))
		val photo = encodePng(imageBufferOf(20, 20, IntArray(400) { opaque or (it * 0x010307) }))
		for (png in listOf(mask, photo)) {
			val types = chunkTypes(png)
			assertTrue(types.all { it in allowed }, "unexpected chunk among $types")
			assertEquals("IHDR", types.first())
			assertEquals("IEND", types.last())
		}
	}
}
