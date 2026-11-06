package org.adelheid.hondius.akm

import org.adelheid.hondius.akm.codec.AkmDecoder
import org.adelheid.hondius.akm.codec.AkmEncoder
import org.adelheid.hondius.container.Author
import org.adelheid.hondius.container.BinaryWriter
import org.adelheid.hondius.container.Chunk
import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException
import org.adelheid.hondius.container.Metadata
import org.adelheid.hondius.container.MetadataCodec
import org.adelheid.hondius.container.MetadataField
import org.adelheid.hondius.container.Sha256
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The map files in `spec/conformance`: every valid one must decode to the form written beside it, and every invalid
 * one must be refused for the reason written beside it. Any implementation of the format can run the same files.
 *
 * The files are written from the cases below when the environment variable `HONDIUS_WRITE_CONFORMANCE` is `1`;
 * otherwise the test only reads them, so a change to the codec that alters a byte or an answer fails here.
 */
class ConformanceTest {
	private val folder = File("../spec/conformance/akm")
	private val write = System.getenv("HONDIUS_WRITE_CONFORMANCE") == "1"

	// --- Valid maps ---

	private val minimal = AkmMap(10, 10, listOf(Fixtures.territory(1, Fixtures.square(0, 0, 10))))

	private val full = AkmMap(
		canvasWidth = 100,
		canvasHeight = 80,
		territories = listOf(
			AkmTerritory(
				Fixtures.id(1),
				listOf(AkmRegion(listOf(Fixtures.square(0, 0, 160), Fixtures.square(40, 40, 80).reversed()))),
				name = "Mainland",
				anchor = AkmPoint(20, 20),
			),
			Fixtures.territory(2, Fixtures.square(160, 0, 160)),
			AkmTerritory(
				Fixtures.id(3),
				listOf(AkmRegion(listOf(Fixtures.square(400, 400, 100))), AkmRegion(listOf(Fixtures.square(600, 400, 50)))),
				name = "Îles",
			),
		),
		connections = listOf(AkmConnection(0, 1), AkmConnection(2, 0, bidirectional = false)),
		land = listOf(AkmRegion(listOf(Fixtures.square(800, 800, 200)))),
		lines = listOf(AkmLine(intArrayOf(320, 80, 360, 200, 400, 400))),
		layers = listOf(
			Fixtures.layer("terrain", 5),
			AkmLayer(LayerRole.LABEL_ART, "labels", 30, 10, 10, 50, 40, ImageFormat.WEBP, AkmImage.Inline(Fixtures.webp()), BlendMode.MULTIPLY, 200),
			Fixtures.layer("frame", 40, LayerRole.ABOVE, AkmImage.External(ByteArray(32) { it.toByte() }, 123_456L, listOf("art/frame.png"))),
		),
		metadata = Metadata(
			name = "Conformance",
			generator = "hondius-test/1.0",
			authors = listOf(Author.Account("adelkrieg.com", Fixtures.id(99)), Author.Credit("A friend")),
		),
	)

	/** What a reader of version 2.0 keeps of a file from a later minor version: a field and a chunk it does not know. */
	private val later = AkmMap(
		10, 10, listOf(Fixtures.territory(1, Fixtures.square(0, 0, 10))),
		metadata = Metadata(name = "Later", otherFields = listOf(MetadataField(9, byteArrayOf(1, 2, 3)))),
		carriedChunks = listOf(Chunk("tags", Chunk.COPY_SAFE, byteArrayOf(4, 5))),
	)

	private fun laterBytes(): ByteArray = reseal(AkmEncoder.encode(later).also { it[11] = 3 })

	@Test
	fun validMapsDecodeToTheFormWrittenBesideThem() {
		val cases = listOf(Triple("minimal", minimal, AkmEncoder.encode(minimal)), Triple("full", full, AkmEncoder.encode(full)), Triple("later-minor-version", later, laterBytes()))
		for ((name, map, bytes) in cases) {
			val file = File(folder, "valid/$name.akm")
			val expected = File(folder, "valid/$name.txt")
			if (write) {
				file.parentFile.mkdirs()
				file.writeBytes(bytes)
				expected.writeText(describe(map))
			}
			assertContentEquals(bytes, file.readBytes(), "$name: the codec no longer writes the file as it is in the repository")
			val decoded = AkmDecoder.decode(file.readBytes())
			assertEquals(expected.readText(), describe(decoded), name)
			// A file of this version is the one encoding of its content
			if (name != "later-minor-version") assertContentEquals(file.readBytes(), AkmEncoder.encode(decoded), name)
		}
		assertEquals(cases.map { it.first }.toSet(), folderNames("valid", "akm"))
	}

	// --- Invalid maps: each breaks one rule ---

	private val signature = byteArrayOf(0x83.toByte(), 0x41, 0x4B, 0x4D, 0x0D, 0x0A, 0x1A, 0x0A)

	/** A file from raw chunks, `META` included, so that a case can hold what the encoder would never write. */
	private fun file(vararg chunks: Chunk, meta: ByteArray = ByteArray(0)): ByteArray {
		val out = BinaryWriter()
		out.writeBytes(signature)
		out.writeU16(2)
		out.writeU16(0)
		out.writeU32(0)
		for (chunk in listOf(Chunk("META", Chunk.REQUIRED, meta)) + chunks) writeChunk(out, chunk)
		writeChunk(out, Chunk("CKSM", Chunk.REQUIRED, byteArrayOf(0) + Sha256.digest(out.toByteArray())))
		return out.toByteArray()
	}

	private fun writeChunk(out: BinaryWriter, chunk: Chunk) {
		out.writeBytes(chunk.tag.encodeToByteArray())
		out.writeByte(chunk.flags)
		out.writeByte(chunk.encoding)
		out.writeU32(chunk.payload.size)
		out.writeBytes(chunk.payload)
	}

	private fun reseal(bytes: ByteArray): ByteArray {
		val content = bytes.copyOfRange(0, bytes.size - 43)
		return content + bytes.copyOfRange(bytes.size - 43, bytes.size - 32) + Sha256.digest(content)
	}

	private fun payload(build: BinaryWriter.() -> Unit): ByteArray = BinaryWriter().also(build).toByteArray()

	/** A geometry chunk for a 100 by 80 canvas on the default grid, its territories written by [territories]. */
	private fun geometry(count: Int = 1, territories: BinaryWriter.() -> Unit): Chunk = Chunk("GEOM", Chunk.REQUIRED, payload {
		writeUvarint(100)
		writeUvarint(80)
		writeByte(4)
		writeUvarint(count)
		territories()
	})

	/** One territory with one region of one ring; the points are the first point, then steps. */
	private fun BinaryWriter.territory(number: Int, name: String = "", vararg points: Int) {
		writeUuid(Fixtures.id(number))
		writeString(name)
		writeByte(0)
		writeUvarint(1)
		writeUvarint(1)
		writeUvarint(points.size / 2)
		writeUvarint(points[0])
		writeUvarint(points[1])
		for (i in 2 until points.size) writeSvarint(points[i])
	}

	private val squareAtOrigin = intArrayOf(0, 0, 160, 0, 0, 160, -160, 0)
	private val squareBeside = intArrayOf(160, 0, 160, 0, 0, 160, -160, 0)
	private val oneSquare = geometry { territory(1, "", *squareAtOrigin) }
	private val twoSquares = geometry(2) {
		territory(1, "", *squareAtOrigin)
		territory(2, "", *squareBeside)
	}

	private fun connections(vararg triples: Int): Chunk = Chunk("ADJC", Chunk.REQUIRED, payload {
		writeUvarint(triples.size / 3)
		for (i in triples.indices step 3) {
			writeUvarint(triples[i])
			writeUvarint(triples[i + 1])
			writeByte(triples[i + 2])
		}
	})

	private val noConnections = connections()

	private fun layer(zOrder: Int, role: Int = 0, png: ByteArray = Fixtures.png()): Chunk = Chunk("LAYR", 0, payload {
		writeByte(role)
		writeString("art")
		writeU16(zOrder)
		writeByte(0)
		writeByte(255)
		writeUvarint(0)
		writeUvarint(0)
		writeUvarint(100)
		writeUvarint(80)
		writeByte(0)
		writeByte(0)
		writeUvarint(png.size)
		writeBytes(png)
	})

	private fun invalidCases(): List<Triple<String, FormatError, ByteArray>> {
		val valid = AkmEncoder.encode(full)
		return listOf(
			Triple("palette-signature", FormatError.BAD_SIGNATURE, valid.copyOf().also { it[3] = 'P'.code.toByte() }),
			Triple("version-1-header", FormatError.UNSUPPORTED_VERSION, valid.copyOf().also { it[9] = 1 }),
			Triple("reserved-header-field-set", FormatError.BAD_STRUCTURE, reseal(valid.copyOf().also { it[15] = 1 })),
			Triple("cut-short", FormatError.TRUNCATED, valid.copyOfRange(0, valid.size / 2)),
			Triple("content-changed-after-checksum", FormatError.BAD_CHECKSUM, valid.copyOf().also { it[70] = (it[70] + 1).toByte() }),
			Triple("data-after-checksum", FormatError.BAD_STRUCTURE, valid + byteArrayOf(0)),
			Triple("chunk-with-reserved-flag", FormatError.BAD_STRUCTURE, reseal(valid.copyOf().also { it[20] = 5 })),
			Triple("no-connection-chunk", FormatError.BAD_CHUNK_ORDER, file(oneSquare)),
			Triple("connections-before-geometry", FormatError.BAD_CHUNK_ORDER, file(noConnections, oneSquare)),
			Triple("unknown-required-chunk", FormatError.UNKNOWN_REQUIRED_CHUNK, file(oneSquare, noConnections, Chunk("RULE", Chunk.REQUIRED, ByteArray(0)))),
			Triple("integer-longer-than-needed", FormatError.NOT_CANONICAL, file(Chunk("GEOM", Chunk.REQUIRED, byteArrayOf(0xE4.toByte(), 0x00) + oneSquare.payload.copyOfRange(1, oneSquare.payload.size)), noConnections)),
			Triple("territory-id-twice", FormatError.DUPLICATE, file(geometry(2) { territory(1, "", *squareAtOrigin); territory(1, "", *squareBeside) }, noConnections)),
			Triple("territory-name-with-line-break", FormatError.BAD_TEXT, file(geometry { territory(1, "North\nSouth", *squareAtOrigin) }, noConnections)),
			Triple("outline-running-backward", FormatError.BAD_GEOMETRY, file(geometry { territory(1, "", 0, 0, 0, 160, 160, 0, 0, -160) }, noConnections)),
			Triple("outline-off-the-canvas", FormatError.BAD_GEOMETRY, file(geometry { territory(1, "", 1500, 0, 160, 0, 0, 160, -160, 0) }, noConnections)),
			Triple("outline-repeating-a-point", FormatError.BAD_GEOMETRY, file(geometry { territory(1, "", 0, 0, 160, 0, 0, 0, 0, 160) }, noConnections)),
			Triple("connection-to-a-missing-territory", FormatError.BAD_REFERENCE, file(twoSquares, connections(0, 2, 1))),
			Triple("connections-not-sorted", FormatError.NOT_CANONICAL, file(geometry(3) { territory(1, "", *squareAtOrigin); territory(2, "", *squareBeside); territory(3, "", 400, 400, 100, 0, 0, 100, -100, 0) }, connections(1, 2, 1, 0, 1, 1))),
			Triple("two-way-connection-high-first", FormatError.NOT_CANONICAL, file(twoSquares, connections(1, 0, 1))),
			Triple("both-directions-as-two-connections", FormatError.DUPLICATE, file(twoSquares, connections(0, 1, 0, 1, 0, 0))),
			Triple("empty-shape-chunk", FormatError.NOT_CANONICAL, file(oneSquare, noConnections, Chunk("SHAP", 0, byteArrayOf(0, 0)))),
			Triple("layers-out-of-z-order", FormatError.NOT_CANONICAL, file(oneSquare, noConnections, layer(20), layer(10))),
			Triple("layer-below-not-under-layer-above", FormatError.BAD_VALUE, file(oneSquare, noConnections, layer(10, role = 1), layer(10, role = 0))),
			Triple("png-with-a-text-chunk", FormatError.BAD_IMAGE, file(oneSquare, noConnections, layer(10, png = Fixtures.png("tEXt")))),
			Triple("authority-no-author-uses", FormatError.NOT_CANONICAL, file(oneSquare, noConnections, meta = payload {
				writeUvarint(3)
				val authors = payload { writeUvarint(1); writeString("unused.example"); writeUvarint(1); writeByte(0); writeString("Someone") }
				writeUvarint(authors.size)
				writeBytes(authors)
			})),
		)
	}

	@Test
	fun invalidMapsAreRefusedForTheReasonWrittenBesideThem() {
		if (write) {
			File(folder, "invalid").mkdirs()
			for ((name, error, bytes) in invalidCases()) {
				File(folder, "invalid/$name.akm").writeBytes(bytes)
				File(folder, "invalid/$name.txt").writeText(error.name + "\n")
			}
		}
		val names = folderNames("invalid", "akm")
		assertEquals(invalidCases().map { it.first }.toSet(), names)
		for (name in names) {
			val expected = FormatError.valueOf(File(folder, "invalid/$name.txt").readText().trim())
			val failure = assertFailsWith<FormatException>(name) { AkmDecoder.decode(File(folder, "invalid/$name.akm").readBytes()) }
			assertEquals(expected, failure.error, "$name: ${failure.message}")
		}
		assertTrue(names.size >= 25)
	}

	private fun folderNames(kind: String, extension: String): Set<String> =
		File(folder, kind).listFiles { file -> file.extension == extension }.orEmpty().map { it.nameWithoutExtension }.toSet()

	// --- The decoded form, as text ---

	private fun describe(map: AkmMap): String {
		val out = StringBuilder()
		out.appendLine("canvas ${map.canvasWidth} ${map.canvasHeight} grid ${map.gridBits}")
		out.append(describe(map.metadata))
		for ((index, territory) in map.territories.withIndex()) {
			val anchor = territory.anchor?.let { " anchor ${it.x},${it.y}" } ?: ""
			out.appendLine("territory $index id ${territory.id} name \"${territory.name}\"$anchor")
			for (region in territory.regions) describe(out, region, "  ")
		}
		for (connection in map.connections) {
			out.appendLine("connection ${connection.a} ${connection.b} ${if (connection.bidirectional) "both-ways" else "one-way"}")
		}
		for (region in map.land) {
			out.appendLine("land")
			describe(out, region, "  ")
		}
		for (line in map.lines) out.appendLine("line ${points(line.points)}")
		for (layer in map.layers) {
			val image = when (val image = layer.image) {
				is AkmImage.Inline -> "inline ${image.data.size} bytes sha256 ${hex(Sha256.digest(image.data))}"
				is AkmImage.External -> "external ${image.length} bytes sha256 ${hex(image.sha256)} hints ${image.hints}"
			}
			out.appendLine(
				"layer \"${layer.name}\" role ${layer.role} z ${layer.zOrder} blend ${layer.blendMode} opacity ${layer.opacity} " +
					"at ${layer.x},${layer.y} size ${layer.width}x${layer.height} ${layer.format} $image",
			)
		}
		for (chunk in map.carriedChunks) out.appendLine("carried ${chunk.tag} flags ${chunk.flags} encoding ${chunk.encoding} payload ${hex(chunk.payload)}")
		return out.toString()
	}

	private fun describe(out: StringBuilder, region: AkmRegion, indent: String) {
		out.appendLine("${indent}region")
		for (ring in region.rings) out.appendLine("$indent  ring ${points(ring.points)}")
	}

	private fun describe(metadata: Metadata): String {
		val out = StringBuilder()
		metadata.name?.let { out.appendLine("name \"$it\"") }
		metadata.generator?.let { out.appendLine("generator \"$it\"") }
		for (author in metadata.authors) {
			out.appendLine(
				when (author) {
					is Author.Credit -> "author credit \"${author.name}\""
					is Author.Account -> "author account ${author.authority} ${author.id}"
				},
			)
		}
		for (field in metadata.otherFields) out.appendLine("metadata-field ${field.id} ${hex(field.value)}")
		// The same bytes the file holds, so that a reader's own metadata codec can be checked against them
		out.appendLine("metadata-bytes ${hex(MetadataCodec.encode(metadata))}")
		return out.toString()
	}

	private fun points(points: IntArray): String = (points.indices step 2).joinToString(" ") { "${points[it]},${points[it + 1]}" }

	private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
