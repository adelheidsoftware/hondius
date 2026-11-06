package org.adelheid.hondius.akm.converter

import org.adelheid.hondius.akm.AkmBorders
import org.adelheid.hondius.akm.AkmImage
import org.adelheid.hondius.akm.AkmMap
import org.adelheid.hondius.akm.LayerRole
import org.adelheid.hondius.akm.codec.AkmDecoder
import org.adelheid.hondius.akm.codec.AkmEncoder
import org.adelheid.hondius.container.Author
import org.adelheid.hondius.container.Metadata
import org.adelheid.hondius.rkm.RkmMap
import org.adelheid.hondius.rkm.codec.RkmDecoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end RKM → AKM conversion tests, plus AKM round-trip on the produced bytes.
 *
 * Lives in `jvmTest` because it reads its `.rkm` fixtures from the test resources.
 */
class RkmToAkmConverterTest {

	private val converter = RkmToAkmConverter()

	@Test
	fun convertsNorthAmericaAndRoundTrips() {
		val rkm = loadRkm("/rkm/north-america.rkm")
		val akm = converter.convert(rkm)

		assertSensibleAkm(rkm, akm, expectedName = "North America")

		val bytes = AkmEncoder.encode(akm)
		val decoded = AkmDecoder.decode(bytes)
		assertEquals(akm, decoded)
		assertContentEquals(bytes, AkmEncoder.encode(decoded), "a map has one encoding")
	}

	@Test
	fun convertsMediterraneanAndRoundTrips() {
		val rkm = loadRkm("/rkm/mediterranean.rkm")
		val akm = converter.convert(rkm)

		assertSensibleAkm(rkm, akm, expectedName = "Mediterranean")

		val bytes = AkmEncoder.encode(akm)
		assertEquals(akm, AkmDecoder.decode(bytes))
	}

	@Test
	fun theCallerCanSayWhatTheMapSaysAboutItself() {
		val rkm = loadRkm("/rkm/north-america.rkm")
		val author = Author.Account("example.org", "00000000-0000-0000-0000-000000000001")
		val akm = converter.convert(rkm, Metadata(name = "Renamed", authors = listOf(author)))
		assertEquals("Renamed", akm.metadata.name)
		assertEquals(listOf<Author>(author), akm.metadata.authors)
	}

	@Test
	fun convertedConnectionsAreAllBidirectional() {
		val akm = converter.convert(loadRkm("/rkm/north-america.rkm"))
		assertTrue(akm.connections.isNotEmpty(), "expected at least one connection")
		akm.connections.forEach { assertTrue(it.bidirectional, "RKM-derived connection should be bidirectional: $it") }
	}

	@Test
	fun convertedAnchorsLandInsideCanvas() {
		val akm = converter.convert(loadRkm("/rkm/north-america.rkm"))
		for (territory in akm.territories) {
			val anchor = assertNotNull(territory.anchor, "territory ${territory.name} should have an anchor")
			assertTrue(anchor.x in 0..akm.canvasWidth * akm.gridScale, "anchor x of ${territory.name} off the canvas: ${anchor.x}")
			assertTrue(anchor.y in 0..akm.canvasHeight * akm.gridScale, "anchor y of ${territory.name} off the canvas: ${anchor.y}")
		}
	}

	@Test
	fun convertedNeighborsShareTheirBordersVertexForVertex() {
		// A small fraction of RKM adjacencies (~10%) are game-logic connections across water, where the
		// territories have no border in common, so the threshold is 90% rather than 100%.
		for (fixture in listOf("/rkm/north-america.rkm", "/rkm/mediterranean.rkm")) {
			val akm = converter.convert(loadRkm(fixture))
			val bordering = AkmBorders.shared(akm).map { Pair(it.territoryA, it.territoryB) }.toSet()
			val connected = akm.connections.count { Pair(minOf(it.a, it.b), maxOf(it.a, it.b)) in bordering }
			val ratio = connected.toDouble() / akm.connections.size
			assertTrue(ratio >= 0.9, "$fixture: only $connected of ${akm.connections.size} connected territories share a border")
			assertEquals(emptyList(), AkmBorders.contacts(akm), "$fixture: neighbors touch without sharing a vertex")
		}
	}

	@Test
	fun convertedLayersAreSplitByColorAndStoredWithAPalette() {
		val akm = converter.convert(loadRkm("/rkm/north-america.rkm"))

		val names = akm.layers.map { it.name }
		for (expected in listOf("water", "territories", "borders", "labels")) {
			assertTrue(expected in names, "expected a '$expected' layer")
		}
		for (layer in akm.layers) {
			assertEquals(AkmMap.STANDARD_LAYERS.getValue(layer.name), layer.zOrder, "z-order of ${layer.name}")
			assertEquals(if (layer.name == "labels") LayerRole.LABEL_ART else LayerRole.BELOW, layer.role, "role of ${layer.name}")
			val png = (layer.image as AkmImage.Inline).data
			// Byte 25 of a PNG is its color type; 3 is a palette
			assertEquals(3, png[25].toInt(), "layer ${layer.name} should be stored with a palette")
		}
	}

	private fun assertSensibleAkm(rkm: RkmMap, akm: AkmMap, expectedName: String) {
		assertEquals(expectedName, akm.metadata.name)
		assertEquals(RkmToAkmConverter.GENERATOR, akm.metadata.generator)
		assertEquals(listOf<Author>(Author.Credit(rkm.author)), akm.metadata.authors)
		assertEquals(AkmMap.DEFAULT_GRID_BITS, akm.gridBits)
		assertTrue(akm.layers.isNotEmpty(), "expected at least one layer")
		// The territory count equals RKM's unless conversion drops degenerate territories. Allow a small slack.
		assertTrue(
			akm.territories.size in (rkm.territories.size - 2)..rkm.territories.size,
			"AKM territories (${akm.territories.size}) should match RKM territories (${rkm.territories.size})",
		)
	}

	private fun loadRkm(path: String): RkmMap {
		val stream = requireNotNull(this::class.java.getResourceAsStream(path)) {
			"fixture $path must be on the test classpath"
		}
		val bytes = stream.use { it.readAllBytes() }
		return RkmDecoder.decode(bytes)
	}
}
