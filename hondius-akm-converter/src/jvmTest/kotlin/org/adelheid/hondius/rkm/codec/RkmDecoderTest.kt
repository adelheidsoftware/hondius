package org.adelheid.hondius.rkm.codec

import org.adelheid.hondius.rkm.RkmMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RKM decoder tests using the built-in `north-america.rkm` and `mediterranean.rkm` fixtures.
 *
 * Lives in `jvmTest` because resource loading is straightforward there. Once the conversion
 * pipeline is ported, we will move equivalent tests into `commonTest` once resource loading
 * is set up across all targets.
 */
class RkmDecoderTest {

	@Test
	fun decodesNorthAmericaFixture() {
		val rkm = loadFixture("/rkm/north-america.rkm")
		assertEquals("north-america", rkm.codename)
		assertEquals("North America", rkm.displayName)
		assertTrue(rkm.author.isNotBlank(), "author should be present")
		assertTrue(rkm.territories.size >= 10, "expected a non-trivial territory count, got ${rkm.territories.size}")
		assertTrue(rkm.borders.isNotEmpty(), "expected at least one border")
		assertTrue(rkm.basePng.isNotEmpty(), "basePng must not be empty")
		assertTrue(rkm.textPng.isNotEmpty(), "textPng must not be empty")
		// PNG signature: 89 50 4E 47 0D 0A 1A 0A
		assertPngHeader(rkm.basePng, "basePng")
		assertPngHeader(rkm.textPng, "textPng")
	}

	@Test
	fun decodesMediterraneanFixture() {
		val rkm = loadFixture("/rkm/mediterranean.rkm")
		assertEquals("mediterranean", rkm.codename)
		assertEquals("Mediterranean", rkm.displayName)
		assertTrue(rkm.author.isNotBlank(), "author should be present")
		assertTrue(rkm.territories.size >= 5, "expected a non-trivial territory count, got ${rkm.territories.size}")
		assertTrue(rkm.borders.isNotEmpty(), "expected at least one border")
		assertPngHeader(rkm.basePng, "basePng")
		assertPngHeader(rkm.textPng, "textPng")
	}

	@Test
	fun everyTerritoryHasAtLeastOneNucleus() {
		val rkm = loadFixture("/rkm/north-america.rkm")
		rkm.territories.forEach { t ->
			assertTrue(t.nuclei.isNotEmpty(), "territory '${t.name}' must have at least one nucleus")
		}
	}

	@Test
	fun everyBorderReferencesExistingTerritories() {
		val rkm = loadFixture("/rkm/north-america.rkm")
		val ids = rkm.territories.map { it.identity }.toSet()
		rkm.borders.forEach { b ->
			assertTrue(b.source in ids, "border source '${b.source}' must reference an existing territory")
			assertTrue(b.target in ids, "border target '${b.target}' must reference an existing territory")
		}
	}

	@Test
	fun corruptedChecksumIsRejected() {
		val bytes = loadBytes("/rkm/north-america.rkm")
		// Flip a byte deep inside the file (not in the header) so the SHA-512 no longer matches.
		// Position 100 lands inside an early chunk's payload for the fixtures we have.
		bytes[100] = (bytes[100].toInt() xor 0xFF).toByte()
		assertFailsWith<IllegalStateException> { RkmDecoder.decode(bytes) }
	}

	@Test
	fun invalidSignatureIsRejected() {
		val bytes = loadBytes("/rkm/north-america.rkm")
		bytes[0] = 0x00
		assertFailsWith<IllegalStateException> { RkmDecoder.decode(bytes) }
	}

	@Test
	fun truncatedFileIsRejected() {
		assertFailsWith<IllegalStateException> { RkmDecoder.decode(ByteArray(20)) }
	}

	private fun loadFixture(path: String): RkmMap = RkmDecoder.decode(loadBytes(path))

	private fun loadBytes(path: String): ByteArray {
		val stream = requireNotNull(this::class.java.getResourceAsStream(path)) {
			"fixture $path must be on the test classpath"
		}
		return stream.use { it.readAllBytes() }
	}

	private fun assertPngHeader(bytes: ByteArray, name: String) {
		assertNotNull(bytes, "$name should not be null")
		assertTrue(bytes.size >= 8, "$name too short to contain a PNG header")
		val expected = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
		val header = bytes.copyOfRange(0, 8)
		assertTrue(header.contentEquals(expected), "$name does not start with a PNG signature")
	}
}
