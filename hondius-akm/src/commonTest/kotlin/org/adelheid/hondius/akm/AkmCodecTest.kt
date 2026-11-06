package org.adelheid.hondius.akm

import org.adelheid.hondius.akm.codec.AkmDecoder
import org.adelheid.hondius.akm.codec.AkmEncoder
import org.adelheid.hondius.container.Author
import org.adelheid.hondius.container.BinaryWriter
import org.adelheid.hondius.container.Chunk
import org.adelheid.hondius.container.ContainerCodec
import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException
import org.adelheid.hondius.container.Metadata
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What the map tests share: small shapes, valid image files, and the check that a fault is refused for its reason. */
internal object Fixtures {
	fun assertFormatError(expected: FormatError, block: () -> Unit) {
		val failure = assertFailsWith<FormatException> { block() }
		assertEquals(expected, failure.error, failure.message)
	}

	fun id(number: Int): String = "00000000-0000-0000-0000-" + number.toString().padStart(12, '0')

	/** A square outline: it runs clockwise on screen, so its area is positive. */
	fun square(x: Int, y: Int, size: Int): AkmRing = AkmRing(intArrayOf(x, y, x + size, y, x + size, y + size, x, y + size))

	fun territory(number: Int, vararg rings: AkmRing): AkmTerritory = AkmTerritory(id(number), listOf(AkmRegion(rings.toList())))

	private fun pngChunk(type: String, length: Int): ByteArray =
		byteArrayOf(0, 0, 0, length.toByte()) + type.encodeToByteArray() + ByteArray(length) + ByteArray(4)

	/** The smallest thing with the structure of a PNG file, optionally with further chunks before its end. */
	fun png(vararg extraChunks: String): ByteArray {
		var bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + pngChunk("IHDR", 13) + pngChunk("IDAT", 2)
		for (type in extraChunks) bytes += pngChunk(type, 3)
		return bytes + pngChunk("IEND", 0)
	}

	/** The smallest thing with the structure of a WebP file, optionally with a further chunk. */
	fun webp(extraChunk: String? = null): ByteArray {
		var body = "WEBP".encodeToByteArray() + "VP8L".encodeToByteArray() + byteArrayOf(4, 0, 0, 0, 1, 2, 3, 4)
		if (extraChunk != null) body += extraChunk.encodeToByteArray() + byteArrayOf(2, 0, 0, 0, 9, 9)
		val size = body.size
		return "RIFF".encodeToByteArray() + byteArrayOf(size.toByte(), (size shr 8).toByte(), 0, 0) + body
	}

	fun layer(name: String, zOrder: Int, role: LayerRole = LayerRole.BELOW, image: AkmImage = AkmImage.Inline(png())): AkmLayer =
		AkmLayer(role, name, zOrder, 0, 0, 100, 80, ImageFormat.PNG, image)
}

class AkmCodecTest {
	private val signature = ContainerCodec.signature("AKM")

	private fun assertFormatError(expected: FormatError, block: () -> Unit) = Fixtures.assertFormatError(expected, block)

	private val mainland = AkmTerritory(
		Fixtures.id(1),
		listOf(AkmRegion(listOf(Fixtures.square(0, 0, 160), Fixtures.square(40, 40, 80).reversed()))),
		name = "Mainland",
		anchor = AkmPoint(20, 20),
	)
	private val neighbor = Fixtures.territory(2, Fixtures.square(160, 0, 160))
	private val islands = AkmTerritory(
		Fixtures.id(3),
		listOf(AkmRegion(listOf(Fixtures.square(400, 400, 100))), AkmRegion(listOf(Fixtures.square(600, 400, 50)))),
		name = "Îles",
	)

	/** A 100 by 80 pixel map on the default grid that uses every part of the format. */
	private fun fullMap(): AkmMap = AkmMap(
		canvasWidth = 100,
		canvasHeight = 80,
		territories = listOf(mainland, neighbor, islands),
		connections = listOf(AkmConnection(2, 0, bidirectional = false), AkmConnection(1, 0)),
		land = listOf(AkmRegion(listOf(Fixtures.square(800, 800, 200)))),
		lines = listOf(AkmLine(intArrayOf(320, 80, 360, 200, 400, 400))),
		layers = listOf(
			Fixtures.layer("frame", 40, LayerRole.ABOVE, AkmImage.External(ByteArray(32) { it.toByte() }, 123_456L, listOf("art/frame.png"))),
			Fixtures.layer("terrain", 5),
			AkmLayer(LayerRole.LABEL_ART, "labels", 30, 10, 10, 50, 40, ImageFormat.WEBP, AkmImage.Inline(Fixtures.webp()), BlendMode.MULTIPLY, 200),
		),
		metadata = Metadata(name = "Test map", generator = "test/1.0", authors = listOf(Author.Account("adelkrieg.com", Fixtures.id(99)))),
		carriedChunks = listOf(Chunk("tags", Chunk.COPY_SAFE, byteArrayOf(1, 2))),
	)

	private fun minimalMap(): AkmMap = AkmMap(10, 10, listOf(Fixtures.territory(1, Fixtures.square(0, 0, 10))))

	@Test
	fun aMapRoundTripsAndHasOneEncoding() {
		val map = fullMap()
		val bytes = AkmEncoder.encode(map)
		val decoded = AkmDecoder.decode(bytes)
		assertEquals(map, decoded)
		assertContentEquals(bytes, AkmEncoder.encode(decoded))
	}

	@Test
	fun connectionsAndLayersAreHeldInFileOrder() {
		val map = fullMap()
		assertEquals(listOf(AkmConnection(0, 1, true), AkmConnection(2, 0, false)), map.connections)
		assertEquals(listOf("terrain", "labels", "frame"), map.layers.map { it.name })
		assertEquals(1, map.territoryIndex(Fixtures.id(2)))
		assertEquals(16, map.gridScale)
	}

	@Test
	fun aMinimalMapIsOneHundredTwentyThreeBytes() {
		val bytes = AkmEncoder.encode(minimalMap())
		// header 16, META 10 + 0, GEOM 10 + 33, ADJC 10 + 1, CKSM 10 + 33
		assertEquals(123, bytes.size)
		assertContentEquals(byteArrayOf(0x83.toByte(), 0x41, 0x4B, 0x4D, 0x0D, 0x0A, 0x1A, 0x0A, 0, 2, 0, 0, 0, 0, 0, 0), bytes.copyOfRange(0, 16))
		assertEquals(minimalMap(), AkmDecoder.decode(bytes))
	}

	@Test
	fun aSummaryReadsWhatAFileSaysAboutItselfWithoutItsContent() {
		val summary = AkmDecoder.summarize(AkmEncoder.encode(fullMap()))
		assertEquals("Test map", summary.metadata.name)
		assertEquals(100, summary.canvasWidth)
		assertEquals(80, summary.canvasHeight)
		assertEquals(4, summary.gridBits)
		assertEquals(3, summary.territoryCount)
		assertEquals(3, summary.layerCount)
		assertEquals(0, summary.versionMinor)
	}

	@Test
	fun aMapThatBreaksARuleCannotBeBuilt() {
		val one = Fixtures.territory(1, Fixtures.square(0, 0, 160))
		val two = Fixtures.territory(2, Fixtures.square(160, 0, 160))
		fun map(territories: List<AkmTerritory> = listOf(one, two), connections: List<AkmConnection> = emptyList(), layers: List<AkmLayer> = emptyList()) =
			AkmMap(100, 80, territories, connections, layers = layers)

		assertFormatError(FormatError.BAD_VALUE) { AkmMap(0, 80, listOf(one)) }
		assertFormatError(FormatError.LIMIT_EXCEEDED) { AkmMap(16_385, 80, listOf(one)) }
		assertFormatError(FormatError.LIMIT_EXCEEDED) { AkmMap(8_000, 8_000, listOf(one)) }
		assertFormatError(FormatError.BAD_VALUE) { AkmMap(100, 80, listOf(one), gridBits = 9) }
		assertFormatError(FormatError.BAD_VALUE) { map(emptyList()) }
		assertFormatError(FormatError.DUPLICATE) { map(listOf(one, one)) }
		assertFormatError(FormatError.BAD_VALUE) { map(listOf(AkmTerritory("NOT-AN-ID", one.regions))) }
		assertFormatError(FormatError.BAD_TEXT) { map(listOf(one.copy(name = "line\nbreak"))) }
		assertFormatError(FormatError.BAD_GEOMETRY) { map(listOf(one.copy(anchor = AkmPoint(1601, 0)))) }
		assertFormatError(FormatError.BAD_GEOMETRY) { map(listOf(Fixtures.territory(1, Fixtures.square(1500, 0, 160)))) }
		assertFormatError(FormatError.BAD_GEOMETRY) { map(listOf(Fixtures.territory(1, Fixtures.square(0, 0, 160).reversed()))) }
		assertFormatError(FormatError.BAD_GEOMETRY) { map(listOf(Fixtures.territory(1, Fixtures.square(0, 0, 160), Fixtures.square(40, 40, 80)))) }
		assertFormatError(FormatError.BAD_GEOMETRY) { map(listOf(Fixtures.territory(1, AkmRing(intArrayOf(0, 0, 100, 0, 200, 0))))) }
		assertFormatError(FormatError.BAD_GEOMETRY) { map(listOf(Fixtures.territory(1, AkmRing(intArrayOf(0, 0, 100, 0, 100, 0, 100, 100))))) }
		assertFormatError(FormatError.BAD_GEOMETRY) { AkmRing(intArrayOf(0, 0, 1, 1)) }
		assertFormatError(FormatError.BAD_GEOMETRY) { AkmMap(100, 80, listOf(one), lines = listOf(AkmLine(intArrayOf(0, 0, 0, 0)))) }

		assertFormatError(FormatError.BAD_REFERENCE) { map(connections = listOf(AkmConnection(0, 2))) }
		assertFormatError(FormatError.BAD_VALUE) { map(connections = listOf(AkmConnection(1, 1))) }
		assertFormatError(FormatError.DUPLICATE) { map(connections = listOf(AkmConnection(0, 1), AkmConnection(1, 0))) }
		assertFormatError(FormatError.DUPLICATE) { map(connections = listOf(AkmConnection(0, 1, false), AkmConnection(1, 0, false))) }

		assertFormatError(FormatError.BAD_VALUE) { map(layers = listOf(Fixtures.layer("a", 70_000))) }
		assertFormatError(FormatError.BAD_VALUE) { map(layers = listOf(Fixtures.layer("a", 5).copy(width = 101))) }
		assertFormatError(FormatError.BAD_VALUE) { map(layers = listOf(Fixtures.layer("a", 5).copy(opacity = 256))) }
		assertFormatError(FormatError.BAD_VALUE) { map(layers = listOf(Fixtures.layer("over", 5, LayerRole.ABOVE), Fixtures.layer("under", 5))) }
		assertFormatError(FormatError.BAD_VALUE) { map(layers = listOf(Fixtures.layer("a", 5, image = AkmImage.External(ByteArray(31), 10L)))) }
		assertFormatError(FormatError.BAD_VALUE) { map(layers = listOf(Fixtures.layer("a", 5, image = AkmImage.External(ByteArray(32), 0L)))) }
		assertFormatError(FormatError.LIMIT_EXCEEDED) { map(layers = listOf(Fixtures.layer("a", 5, image = AkmImage.External(ByteArray(32), 10L, List(5) { "hint$it" })))) }
	}

	@Test
	fun anImageMayHoldOnlyWhatIsNeededToDecodeIt() {
		fun build(format: ImageFormat, data: ByteArray) = AkmMap(
			100, 80, listOf(Fixtures.territory(1, Fixtures.square(0, 0, 160))),
			layers = listOf(AkmLayer(LayerRole.BELOW, "art", 5, 0, 0, 100, 80, format, AkmImage.Inline(data))),
		)
		build(ImageFormat.PNG, Fixtures.png("tRNS", "sRGB"))
		build(ImageFormat.WEBP, Fixtures.webp())
		for (chunk in listOf("tEXt", "iTXt", "eXIf", "tIME", "iCCP", "acTL")) {
			assertFormatError(FormatError.BAD_IMAGE) { build(ImageFormat.PNG, Fixtures.png(chunk)) }
		}
		assertFormatError(FormatError.BAD_IMAGE) { build(ImageFormat.PNG, Fixtures.png() + byteArrayOf(1, 2, 3)) }
		assertFormatError(FormatError.BAD_IMAGE) { build(ImageFormat.PNG, Fixtures.webp()) }
		assertFormatError(FormatError.BAD_IMAGE) { build(ImageFormat.PNG, ByteArray(0)) }
		for (chunk in listOf("EXIF", "XMP ", "ICCP", "ANIM")) {
			assertFormatError(FormatError.BAD_IMAGE) { build(ImageFormat.WEBP, Fixtures.webp(chunk)) }
		}
		assertFormatError(FormatError.BAD_IMAGE) { build(ImageFormat.AVIF, Fixtures.png()) }
		val avif = byteArrayOf(0, 0, 0, 20) + "ftypavif".encodeToByteArray() + ByteArray(4) + "mif1".encodeToByteArray()
		build(ImageFormat.AVIF, avif)
		val sequence = byteArrayOf(0, 0, 0, 20) + "ftypavis".encodeToByteArray() + ByteArray(4) + "avif".encodeToByteArray()
		assertFormatError(FormatError.BAD_IMAGE) { build(ImageFormat.AVIF, sequence) }
	}

	// --- Bytes that break a rule. Each test replaces one chunk of a valid file with a payload written by hand. ---

	private val validChunks: List<Chunk> = ContainerCodec.read(AkmEncoder.encode(fullMap()), signature).chunks

	private fun decodeWith(tag: String, payload: ByteArray): AkmMap {
		val chunks = validChunks.map { if (it.tag == tag) Chunk(tag, it.flags, payload) else it }.distinctBy { if (it.tag == "LAYR") it else it.tag }
		return AkmDecoder.decode(ContainerCodec.write(signature, Metadata(), chunks))
	}

	private fun connections(vararg triples: Int): ByteArray {
		val out = BinaryWriter()
		out.writeUvarint(triples.size / 3)
		for (i in triples.indices step 3) {
			out.writeUvarint(triples[i])
			out.writeUvarint(triples[i + 1])
			out.writeByte(triples[i + 2])
		}
		return out.toByteArray()
	}

	@Test
	fun connectionsNotInTheirOneFormAreRefused() {
		assertEquals(2, decodeWith("ADJC", connections(0, 1, 1, 2, 0, 0)).connections.size)
		assertFormatError(FormatError.NOT_CANONICAL) { decodeWith("ADJC", connections(2, 0, 0, 0, 1, 1)) }
		assertFormatError(FormatError.NOT_CANONICAL) { decodeWith("ADJC", connections(1, 0, 1)) }
		assertFormatError(FormatError.DUPLICATE) { decodeWith("ADJC", connections(0, 1, 1, 0, 1, 1)) }
		assertFormatError(FormatError.DUPLICATE) { decodeWith("ADJC", connections(0, 1, 0, 1, 0, 0)) }
		assertFormatError(FormatError.BAD_REFERENCE) { decodeWith("ADJC", connections(0, 3, 1)) }
		assertFormatError(FormatError.BAD_VALUE) { decodeWith("ADJC", connections(1, 1, 1)) }
		assertFormatError(FormatError.BAD_STRUCTURE) { decodeWith("ADJC", connections(0, 1, 3)) }
		assertFormatError(FormatError.BAD_STRUCTURE) { decodeWith("ADJC", connections(0, 1, 1) + byteArrayOf(0)) }
		assertFormatError(FormatError.TRUNCATED) { decodeWith("ADJC", byteArrayOf(5, 0, 1)) }
	}

	/** A geometry chunk with one territory whose single ring is written by [ring]. */
	private fun geometry(grid: Int = 4, territories: Int = 1, flags: Int = 0, ring: BinaryWriter.() -> Unit): ByteArray {
		val out = BinaryWriter()
		out.writeUvarint(100)
		out.writeUvarint(80)
		out.writeByte(grid)
		out.writeUvarint(territories)
		if (territories > 0) {
			out.writeUuid(Fixtures.id(1))
			out.writeString("")
			out.writeByte(flags)
			out.writeUvarint(1)
			out.writeUvarint(1)
			out.ring()
		}
		return out.toByteArray()
	}

	private fun BinaryWriter.points(vararg values: Int) {
		writeUvarint(values.size / 2)
		writeUvarint(values[0])
		writeUvarint(values[1])
		for (i in 2 until values.size) writeSvarint(values[i])
	}

	private fun decodeGeometry(payload: ByteArray): AkmMap =
		AkmDecoder.decode(ContainerCodec.write(signature, Metadata(), listOf(Chunk("GEOM", Chunk.REQUIRED, payload), Chunk("ADJC", Chunk.REQUIRED, byteArrayOf(0)))))

	@Test
	fun outlinesThatAreNotValidAreRefused() {
		assertEquals(4, decodeGeometry(geometry { points(0, 0, 10, 0, 0, 10, -10, 0) }).territories[0].regions[0].outline.pointCount)
		assertFormatError(FormatError.BAD_GEOMETRY) { decodeGeometry(geometry { points(0, 0, 10, 0, 0, 0, 0, 10) }) }
		assertFormatError(FormatError.BAD_GEOMETRY) { decodeGeometry(geometry { points(0, 0, 10, 0, 0, 10, -20, 0) }) }
		assertFormatError(FormatError.BAD_GEOMETRY) { decodeGeometry(geometry { points(1590, 0, 20, 0, 0, 10, -20, 0) }) }
		assertFormatError(FormatError.BAD_GEOMETRY) { decodeGeometry(geometry { points(0, 0, 0, 10, 10, 0, 0, -10) }) }
		assertFormatError(FormatError.BAD_VALUE) { decodeGeometry(geometry { points(0, 0, 10, 0) }) }
		assertFormatError(FormatError.BAD_VALUE) { decodeGeometry(geometry(grid = 9) { points(0, 0, 10, 0, 0, 10, -10, 0) }) }
		assertFormatError(FormatError.BAD_VALUE) { decodeGeometry(geometry(territories = 0) { }) }
		assertFormatError(FormatError.BAD_STRUCTURE) { decodeGeometry(geometry(flags = 2) { points(0, 0, 10, 0, 0, 10, -10, 0) }) }
		assertFormatError(FormatError.BAD_STRUCTURE) { decodeGeometry(geometry { points(0, 0, 10, 0, 0, 10, -10, 0); writeByte(0) }) }
		assertFormatError(FormatError.TRUNCATED) { decodeGeometry(geometry { writeUvarint(4); writeUvarint(0) }) }
		assertFormatError(FormatError.TRUNCATED) { decodeGeometry(geometry { writeUvarint(1_000_000); writeUvarint(0) }) }
		assertFormatError(FormatError.LIMIT_EXCEEDED) { decodeGeometry(geometry { writeUvarint(1_000_001) }) }
	}

	@Test
	fun shapeAndLayerChunksNotInTheirOneFormAreRefused() {
		assertFormatError(FormatError.NOT_CANONICAL) { decodeWith("SHAP", byteArrayOf(0, 0)) }

		val layers = validChunks.filter { it.tag == "LAYR" }
		val others = validChunks.filter { it.tag != "LAYR" }
		assertFormatError(FormatError.NOT_CANONICAL) {
			AkmDecoder.decode(ContainerCodec.write(signature, Metadata(), others.dropLast(1) + layers.reversed() + others.last()))
		}
		fun withLayer(change: (ByteArray) -> Unit): ByteArray {
			val payload = layers[0].payload.copyOf().also(change)
			return ContainerCodec.write(signature, Metadata(), others.dropLast(1) + Chunk("LAYR", 0, payload))
		}
		// A layer chunk opens with its role; its name 'terrain' follows, then z-order, blend mode and opacity
		assertFormatError(FormatError.BAD_VALUE) { AkmDecoder.decode(withLayer { it[0] = 3 }) }
		assertFormatError(FormatError.BAD_VALUE) { AkmDecoder.decode(withLayer { it[11] = 9 }) }
		assertFormatError(FormatError.BAD_STRUCTURE) { AkmDecoder.decode(ContainerCodec.write(signature, Metadata(), others.dropLast(1) + Chunk("LAYR", 0, layers[0].payload + byteArrayOf(0)))) }
	}

	@Test
	fun theChunkRulesOfTheFormatAreEnforced() {
		val geometry = validChunks.first { it.tag == "GEOM" }
		val adjacency = validChunks.first { it.tag == "ADJC" }
		fun decode(vararg chunks: Chunk) = AkmDecoder.decode(ContainerCodec.write(signature, Metadata(), chunks.toList()))

		assertFormatError(FormatError.BAD_CHUNK_ORDER) { decode(geometry) }
		assertFormatError(FormatError.BAD_CHUNK_ORDER) { decode(adjacency, geometry) }
		assertFormatError(FormatError.UNKNOWN_REQUIRED_CHUNK) { decode(geometry, adjacency, Chunk("RULE", Chunk.REQUIRED, ByteArray(0))) }
		assertFormatError(FormatError.BAD_STRUCTURE) { decode(geometry, Chunk("ADJC", 0, adjacency.payload)) }
		val carried = Chunk("note", 0, byteArrayOf(7))
		assertEquals(listOf(carried), decode(geometry, adjacency, carried).carriedChunks)
	}

	@Test
	fun aPaletteOrAVersionOneFileIsNotAMap() {
		val bytes = AkmEncoder.encode(minimalMap())
		assertFormatError(FormatError.BAD_SIGNATURE) { AkmDecoder.decode(bytes.copyOf().also { it[3] = 'P'.code.toByte() }) }
		assertFormatError(FormatError.UNSUPPORTED_VERSION) { AkmDecoder.decode(bytes.copyOf().also { it[9] = 1 }) }
		assertFormatError(FormatError.BAD_CHECKSUM) { AkmDecoder.decode(bytes.copyOf().also { it[60] = (it[60] + 1).toByte() }) }
		assertFormatError(FormatError.TRUNCATED) { AkmDecoder.decode(bytes.copyOfRange(0, 50)) }
	}
}

class AkmBordersTest {
	@Test
	fun neighborsWrittenWithTheSameVerticesShareTheirBorder() {
		val left = Fixtures.territory(1, Fixtures.square(0, 0, 160))
		// The right square is written with an extra vertex on its far side, which changes nothing about the shared edge
		val right = Fixtures.territory(2, AkmRing(intArrayOf(160, 0, 320, 0, 320, 80, 320, 160, 160, 160)))
		val map = AkmMap(100, 80, listOf(left, right))
		val shared = AkmBorders.shared(map)
		assertEquals(1, shared.size)
		assertEquals(0, shared[0].territoryA)
		assertEquals(1, shared[0].territoryB)
		assertContentEquals(intArrayOf(160, 0, 160, 160), shared[0].points)
		assertEquals(emptyList(), AkmBorders.contacts(map))
	}

	@Test
	fun aBorderOfSeveralEdgesIsOneStretch() {
		val left = Fixtures.territory(1, AkmRing(intArrayOf(0, 0, 160, 0, 170, 80, 160, 160, 0, 160)))
		val right = Fixtures.territory(2, AkmRing(intArrayOf(160, 0, 320, 0, 320, 160, 160, 160, 170, 80)))
		val shared = AkmBorders.shared(AkmMap(100, 80, listOf(left, right)))
		assertEquals(1, shared.size)
		assertContentEquals(intArrayOf(160, 0, 170, 80, 160, 160), shared[0].points)
	}

	@Test
	fun aTerritoryInsideAnotherSharesItsWholeOutline() {
		val outer = AkmTerritory(Fixtures.id(1), listOf(AkmRegion(listOf(Fixtures.square(0, 0, 320), Fixtures.square(80, 80, 160).reversed()))))
		val inner = Fixtures.territory(2, Fixtures.square(80, 80, 160))
		val shared = AkmBorders.shared(AkmMap(100, 80, listOf(outer, inner)))
		assertEquals(1, shared.size)
		assertEquals(5, shared[0].pointCount)
		assertEquals(shared[0].points[0], shared[0].points[8])
		assertEquals(shared[0].points[1], shared[0].points[9])
	}

	@Test
	fun aVertexOnANeighborsEdgeIsReportedAsAContact() {
		val left = Fixtures.territory(1, Fixtures.square(0, 0, 160))
		// The right square touches the left one along x = 160 but splits that side at a point the left one lacks
		val right = Fixtures.territory(2, AkmRing(intArrayOf(160, 0, 320, 0, 320, 160, 160, 160, 160, 80)))
		val map = AkmMap(100, 80, listOf(left, right))
		assertEquals(emptyList(), AkmBorders.shared(map).map { it.pointCount })
		assertEquals(listOf(BorderContact(1, 0, 160, 80)), AkmBorders.contacts(map))
	}
}
