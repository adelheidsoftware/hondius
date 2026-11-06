package org.adelheid.hondius.rkm.codec

import org.adelheid.hondius.akm.image.encodePng
import org.adelheid.hondius.akm.image.imageBufferOf
import org.adelheid.hondius.rkm.RkmBorder
import org.adelheid.hondius.rkm.RkmMap
import org.adelheid.hondius.rkm.RkmNucleus
import org.adelheid.hondius.rkm.RkmTerritory
import org.adelheid.hondius.rkm.RkmTerritoryIdentity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Cross-platform round-trip test for the RKM encoder. Runs on jvm, jsNode, and (via the
 * compileWasmJs path) wasmJs. Uses a synthetic fixture so no resource loading is needed.
 */
class RkmEncoderRoundTripTest {

	@Test
	fun encodeDecodeRoundTripOnSyntheticMap() {
		val original = syntheticMap()
		val bytes = RkmEncoder.encode(original)
		val decoded = RkmDecoder.decode(bytes)

		assertEquals(original.codename, decoded.codename)
		assertEquals(original.displayName, decoded.displayName)
		assertEquals(original.author, decoded.author)
		assertEquals(original.territories, decoded.territories)
		assertEquals(original.borders, decoded.borders)
		assertContentEquals(original.basePng, decoded.basePng)
		assertContentEquals(original.textPng, decoded.textPng)
	}

	@Test
	fun encodingIsDeterministicForSameInput() {
		val map = syntheticMap()
		assertContentEquals(RkmEncoder.encode(map), RkmEncoder.encode(map))
	}

	@Test
	fun encodedFileStartsWithCorrectRkmSignature() {
		val bytes = RkmEncoder.encode(syntheticMap())
		val expectedSig = byteArrayOf(0x83.toByte(), 0x52, 0x4B, 0x4D, 0x0D, 0x0A, 0x1A, 0x0A)
		assertContentEquals(expectedSig, bytes.copyOfRange(0, 8))
	}

	@Test
	fun roundTripPreservesMultipleNucleiAndExclaves() {
		// Build a territory with two nuclei (exclave) to confirm both are preserved.
		val exclaveTerritory = RkmTerritory(
			identity = RkmTerritoryIdentity("with-exclave"),
			nuclei = linkedSetOf(RkmNucleus(10, 10), RkmNucleus(80, 80)),
		)
		val singleTerritory = RkmTerritory(
			identity = RkmTerritoryIdentity("plain"),
			nuclei = linkedSetOf(RkmNucleus(50, 50)),
		)
		val map = RkmMap(
			codename = "exclave-test",
			displayName = "Exclave Test",
			author = "Tester",
			territories = linkedSetOf(exclaveTerritory, singleTerritory),
			borders = linkedSetOf(
				RkmBorder(exclaveTerritory.identity, singleTerritory.identity),
			),
			basePng = tinyPng(0xFFAAAAAA.toInt()),
			textPng = tinyPng(0x00000000),
		)

		val decoded = RkmDecoder.decode(RkmEncoder.encode(map))
		assertEquals(2, decoded.territories.size)
		val withExclave = decoded.territories.first { it.identity.rawValue == "with-exclave" }
		assertEquals(2, withExclave.nuclei.size)
		assertTrue(withExclave.nuclei.contains(RkmNucleus(10, 10)))
		assertTrue(withExclave.nuclei.contains(RkmNucleus(80, 80)))
	}

	@Test
	fun corruptedChecksumIsRejectedOnDecode() {
		val bytes = RkmEncoder.encode(syntheticMap())
		// Flip a byte inside the MCNM payload so the SHA-512 no longer matches.
		bytes[24] = (bytes[24].toInt() xor 0xFF).toByte()
		assertFailsWith<IllegalStateException> { RkmDecoder.decode(bytes) }
	}

	// --- Fixtures ---

	private fun syntheticMap(): RkmMap {
		val t1 = RkmTerritory(RkmTerritoryIdentity("alpha"), linkedSetOf(RkmNucleus(5, 5)))
		val t2 = RkmTerritory(RkmTerritoryIdentity("beta"), linkedSetOf(RkmNucleus(15, 5), RkmNucleus(20, 20)))
		val t3 = RkmTerritory(RkmTerritoryIdentity("gamma"), linkedSetOf(RkmNucleus(25, 25)))

		return RkmMap(
			codename = "synthetic",
			displayName = "Synthetic Test Map",
			author = "Aaron",
			territories = linkedSetOf(t1, t2, t3),
			borders = linkedSetOf(
				RkmBorder(t1.identity, t2.identity),
				RkmBorder(t2.identity, t3.identity),
			),
			basePng = tinyPng(0xFF802040.toInt()),
			textPng = tinyPng(0x80FFFFFF.toInt()),
		)
	}

	/** Produces a 2x2 PNG via the shared [encodePng], so this test works on every target. */
	private fun tinyPng(argb: Int): ByteArray {
		val pixels = IntArray(4) { argb }
		return encodePng(imageBufferOf(2, 2, pixels))
	}
}
