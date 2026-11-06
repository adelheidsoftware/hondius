package org.adelheid.hondius.akm.v1

import org.adelheid.hondius.akm.AkmConnection
import org.adelheid.hondius.akm.AkmImage
import org.adelheid.hondius.akm.AkmPoint
import org.adelheid.hondius.akm.BlendMode
import org.adelheid.hondius.akm.ImageFormat
import org.adelheid.hondius.akm.LayerRole
import org.adelheid.hondius.akm.codec.AkmDecoder
import org.adelheid.hondius.akm.codec.AkmEncoder
import org.adelheid.hondius.akm.image.encodePng
import org.adelheid.hondius.akm.image.imageBufferOf
import org.adelheid.hondius.container.BinaryWriter
import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException
import org.adelheid.hondius.container.Sha256
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Writes version 1 `.akm` bytes, which nothing but these tests still needs to produce. */
internal object AkmV1TestEncoder {
	fun encode(map: AkmV1Map): ByteArray {
		val out = BinaryWriter()
		out.writeBytes(byteArrayOf(0x83.toByte(), 0x41, 0x4B, 0x4D, 0x0D, 0x0A, 0x1A, 0x0A))
		out.writeU16(1)
		out.writeU16(0)
		out.writeU32(0)

		val meta = BinaryWriter()
		meta.writeUuid(map.mapId)
		meta.writeU32(map.canvasWidth)
		meta.writeU32(map.canvasHeight)
		meta.writeV1String(map.displayName)
		meta.writeV1String(map.description)
		meta.writeU16(map.authors.size)
		for (author in map.authors) {
			meta.writeUuid(author.authorId)
			meta.writeV1String(author.name)
		}
		meta.writeI64(map.createdAtEpochSeconds)
		meta.writeV1String(map.editorVersion)
		chunk(out, "META", meta.toByteArray())

		val geometry = BinaryWriter()
		geometry.writeU32(map.territories.size)
		for (territory in map.territories) {
			geometry.writeUuid(territory.territoryId)
			geometry.writeV1String(territory.label)
			geometry.writeF32(territory.centroidX)
			geometry.writeF32(territory.centroidY)
			geometry.writeU16(territory.regions.size)
			for (region in territory.regions) {
				geometry.writeU16(region.rings.size)
				for (ring in region.rings) {
					geometry.writeU32(ring.pointCount)
					for (value in ring.points) geometry.writeF32(value)
				}
			}
		}
		chunk(out, "GEOM", geometry.toByteArray())

		val adjacency = BinaryWriter()
		adjacency.writeU32(map.edges.size)
		for (edge in map.edges) {
			adjacency.writeUuid(edge.sourceId)
			adjacency.writeUuid(edge.targetId)
			adjacency.writeByte(if (edge.bidirectional) 1 else 0)
		}
		chunk(out, "ADJC", adjacency.toByteArray())

		for (layer in map.layers) {
			val payload = BinaryWriter()
			payload.writeV1String(layer.name)
			payload.writeU16(layer.zOrder)
			payload.writeByte(layer.blendMode.wireValue)
			payload.writeByte(layer.opacity)
			payload.writeByte(layer.imageFormat.wireValue)
			payload.writeU32(layer.imageData.size)
			payload.writeBytes(layer.imageData)
			chunk(out, "LAYR", payload.toByteArray())
		}
		chunk(out, "CKSM", byteArrayOf(0) + Sha256.digest(out.toByteArray()))
		return out.toByteArray()
	}

	private fun chunk(out: BinaryWriter, tag: String, payload: ByteArray) {
		out.writeBytes(tag.encodeToByteArray())
		out.writeU32(payload.size)
		out.writeBytes(payload)
	}

	private fun BinaryWriter.writeV1String(value: String) {
		val bytes = value.encodeToByteArray()
		writeU16(bytes.size)
		writeBytes(bytes)
	}
}

class AkmV1ConverterTest {
	private fun id(number: Int): String = "00000000-0000-0000-0000-" + number.toString().padStart(12, '0')

	/** Every value is a multiple of 1/128, which a 32-bit float holds exactly on every target. */
	private fun ring(vararg values: Float): AkmV1Ring = AkmV1Ring(values)

	private fun territory(number: Int, label: String, vararg rings: AkmV1Ring): AkmV1Territory =
		AkmV1Territory(id(number), label, 5.015625f, 4.984375f, listOf(AkmV1Region(rings.toList())))

	private val mask = encodePng(imageBufferOf(8, 8, IntArray(64) { if (it % 2 == 0) 0xFF336699.toInt() else 0 }))

	private val v1 = AkmV1Map(
		mapId = id(900),
		canvasWidth = 100,
		canvasHeight = 80,
		displayName = "Old map",
		description = "Written before the grid",
		authors = listOf(AkmV1Author(id(901), "Tester")),
		createdAtEpochSeconds = 1_700_000_000L,
		editorVersion = "rkm-converter/1.0",
		territories = listOf(
			// An outline, and a hole that runs the same way as the outline, which version 1 never checked
			territory(1, "West", ring(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f), ring(2f, 2f, 8f, 2f, 8f, 8f, 2f, 8f)),
			// Shares the edge x = 10 with West; its second point repeats the first once rounded
			territory(2, "East", ring(10f, 0f, 10.0078125f, 0.0078125f, 20f, 0f, 20f, 10f, 10f, 10f)),
			// Smaller than a grid unit in every direction: nothing is left of it on the grid
			territory(3, "Speck", ring(50f, 50f, 50.0078125f, 50f, 50.0078125f, 50.0078125f)),
		),
		edges = listOf(
			AkmV1Edge(id(1), id(2), bidirectional = false),
			AkmV1Edge(id(2), id(1), bidirectional = false),
			AkmV1Edge(id(2), id(3), bidirectional = true),
		),
		layers = listOf(
			AkmV1Layer("labels", 30, BlendMode.NORMAL, 255, ImageFormat.PNG, mask),
			AkmV1Layer("water", 5, BlendMode.MULTIPLY, 128, ImageFormat.PNG, mask),
		),
	)

	@Test
	fun aVersionOneFileReadsBackAsItWasWritten() {
		val decoded = AkmV1Decoder.decode(AkmV1TestEncoder.encode(v1))
		assertEquals(v1.mapId, decoded.mapId)
		assertEquals(v1.description, decoded.description)
		assertEquals(v1.authors, decoded.authors)
		assertEquals(v1.createdAtEpochSeconds, decoded.createdAtEpochSeconds)
		assertEquals(v1.territories, decoded.territories)
		assertEquals(v1.edges, decoded.edges)
		assertEquals(listOf("labels", "water"), decoded.layers.map { it.name })
		assertContentEquals(mask, decoded.layers[0].imageData)
	}

	@Test
	fun outlinesGoOntoTheGridAndTurnTheRightWay() {
		val map = AkmV1Converter.convert(AkmV1TestEncoder.encode(v1))
		assertEquals(listOf("West", "East"), map.territories.map { it.name })
		assertEquals(listOf(id(1), id(2)), map.territories.map { it.id })

		val west = map.territories[0].regions[0]
		assertContentEquals(intArrayOf(0, 0, 160, 0, 160, 160, 0, 160), west.outline.points)
		assertTrue(west.outline.signedArea2() > 0)
		assertEquals(1, west.holes.size)
		assertTrue(west.holes[0].signedArea2() < 0, "the hole is turned to run against its outline")

		// The point that rounded onto its predecessor is gone
		assertContentEquals(intArrayOf(160, 0, 320, 0, 320, 160, 160, 160), map.territories[1].regions[0].outline.points)
		assertEquals(AkmPoint(80, 80), map.territories[0].anchor)
	}

	@Test
	fun twoOneWayEdgesBecomeOneConnectionAndEdgesOfDroppedTerritoriesGo() {
		val map = AkmV1Converter.convert(v1)
		assertEquals(listOf(AkmConnection(0, 1, bidirectional = true)), map.connections)
	}

	@Test
	fun aVersionOneFileIsToldFromACurrentOne() {
		val bytes = AkmV1TestEncoder.encode(v1)
		assertTrue(AkmV1Decoder.isVersion1(bytes))
		assertTrue(!AkmV1Decoder.isVersion1(AkmEncoder.encode(AkmV1Converter.convert(bytes))))
		assertTrue(!AkmV1Decoder.isVersion1(bytes.copyOfRange(0, 12)))
		assertTrue(!AkmV1Decoder.isVersion1(ByteArray(64)))
	}

	@Test
	fun layersKeepTheirSettingsAndGetARole() {
		val map = AkmV1Converter.convert(v1)
		assertEquals(listOf("water", "labels"), map.layers.map { it.name })
		val water = map.layers[0]
		assertEquals(LayerRole.BELOW, water.role)
		assertEquals(BlendMode.MULTIPLY, water.blendMode)
		assertEquals(128, water.opacity)
		assertEquals(listOf(0, 0, 100, 80), listOf(water.x, water.y, water.width, water.height))
		assertEquals(LayerRole.LABEL_ART, map.layers[1].role)
		// Byte 25 of a PNG is its color type; 3 is a palette
		assertEquals(3, (water.image as AkmImage.Inline).data[25].toInt())
	}

	@Test
	fun theNameAndToolStampCarryOverAndNothingElseOfTheHeader() {
		val map = AkmV1Converter.convert(v1)
		assertEquals("Old map", map.metadata.name)
		assertEquals("rkm-converter/1.0", map.metadata.generator)
		assertEquals(emptyList(), map.metadata.authors)
		assertEquals(map, AkmDecoder.decode(AkmEncoder.encode(map)))
	}

	@Test
	fun aDamagedOrForeignFileIsRefused() {
		val bytes = AkmV1TestEncoder.encode(v1)
		// Offset 30 lies inside the map id, so the structure still parses and only the digest disagrees
		val damaged = bytes.copyOf().also { it[30] = (it[30] + 1).toByte() }
		assertEquals(FormatError.BAD_CHECKSUM, assertFailsWith<FormatException> { AkmV1Decoder.decode(damaged) }.error)
		assertEquals(FormatError.UNSUPPORTED_VERSION, assertFailsWith<FormatException> { AkmDecoder.decode(bytes) }.error)
		val current = AkmEncoder.encode(AkmV1Converter.convert(v1))
		assertEquals(FormatError.UNSUPPORTED_VERSION, assertFailsWith<FormatException> { AkmV1Decoder.decode(current) }.error)
		assertEquals(FormatError.TRUNCATED, assertFailsWith<FormatException> { AkmV1Decoder.decode(bytes.copyOfRange(0, bytes.size - 10)) }.error)
	}
}
