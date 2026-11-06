package org.adelheid.hondius.rkm.codec

import org.adelheid.hondius.rkm.RkmMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the RKM encoder. Image data is kept as an opaque [ByteArray] and never
 * re-encoded, so a decode → encode round-trip on a real fixture produces byte-identical
 * output, which the tests below assert. An encoder that passed the images through
 * `ImageIO.write` could not: it may choose a different PNG compression level than the
 * original file used.
 */
class RkmEncoderTest {

	@Test
	fun northAmericaRoundTripIsByteStable() {
		val original = loadBytes("/rkm/north-america.rkm")
		val decoded = RkmDecoder.decode(original)
		val reencoded = RkmEncoder.encode(decoded)
		assertContentEquals(original, reencoded, "decode → encode should be byte-stable")
	}

	@Test
	fun mediterraneanRoundTripIsByteStable() {
		val original = loadBytes("/rkm/mediterranean.rkm")
		val decoded = RkmDecoder.decode(original)
		val reencoded = RkmEncoder.encode(decoded)
		assertContentEquals(original, reencoded, "decode → encode should be byte-stable")
	}

	@Test
	fun encodedFixtureRedecodesToEqualMap() {
		val decoded = RkmDecoder.decode(loadBytes("/rkm/north-america.rkm"))
		val reencoded = RkmEncoder.encode(decoded)
		val redecoded = RkmDecoder.decode(reencoded)

		assertEquals(decoded.codename, redecoded.codename)
		assertEquals(decoded.displayName, redecoded.displayName)
		assertEquals(decoded.author, redecoded.author)
		assertEquals(decoded.territories.size, redecoded.territories.size)
		assertEquals(decoded.borders.size, redecoded.borders.size)
		assertEquals(decoded.territories, redecoded.territories)
		assertEquals(decoded.borders, redecoded.borders)
		assertContentEquals(decoded.basePng, redecoded.basePng)
		assertContentEquals(decoded.textPng, redecoded.textPng)
	}

	@Test
	fun encodedFixtureStartsWithCorrectSignature() {
		val bytes = RkmEncoder.encode(RkmDecoder.decode(loadBytes("/rkm/north-america.rkm")))
		val expectedSig = byteArrayOf(0x83.toByte(), 0x52, 0x4B, 0x4D, 0x0D, 0x0A, 0x1A, 0x0A)
		assertContentEquals(expectedSig, bytes.copyOfRange(0, 8))
	}

	@Test
	fun encodedFixtureEndsWithSha512Field() {
		val bytes = RkmEncoder.encode(RkmDecoder.decode(loadBytes("/rkm/north-america.rkm")))
		// CKSM trailer: 4-byte "CKSM" tag + 4-byte length (= 64) + 64-byte SHA-512.
		val cksmOffset = bytes.size - 72
		val cksmTag = byteArrayOf('C'.code.toByte(), 'K'.code.toByte(), 'S'.code.toByte(), 'M'.code.toByte())
		assertContentEquals(cksmTag, bytes.copyOfRange(cksmOffset, cksmOffset + 4))
		// Length (u32 big-endian) = 64
		val length = ((bytes[cksmOffset + 4].toInt() and 0xFF) shl 24) or
			((bytes[cksmOffset + 5].toInt() and 0xFF) shl 16) or
			((bytes[cksmOffset + 6].toInt() and 0xFF) shl 8) or
			(bytes[cksmOffset + 7].toInt() and 0xFF)
		assertEquals(64, length, "CKSM length must be 64 (SHA-512 digest size)")
		assertTrue(bytes.size == cksmOffset + 8 + 64, "no trailing bytes after CKSM")
	}

	private fun loadBytes(path: String): ByteArray {
		val stream = requireNotNull(this::class.java.getResourceAsStream(path)) {
			"fixture $path must be on the test classpath"
		}
		return stream.use { it.readAllBytes() }
	}
}
