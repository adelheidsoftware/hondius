package org.adelheid.hondius.akm.codec

import org.adelheid.hondius.akm.AkmConnection
import org.adelheid.hondius.akm.AkmImage
import org.adelheid.hondius.akm.AkmLayer
import org.adelheid.hondius.akm.AkmLine
import org.adelheid.hondius.akm.AkmMap
import org.adelheid.hondius.akm.AkmPoint
import org.adelheid.hondius.akm.AkmRegion
import org.adelheid.hondius.akm.AkmRing
import org.adelheid.hondius.akm.AkmTerritory
import org.adelheid.hondius.akm.BlendMode
import org.adelheid.hondius.akm.ImageFormat
import org.adelheid.hondius.akm.LayerRole
import org.adelheid.hondius.container.BinaryReader
import org.adelheid.hondius.container.ContainerCodec
import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException
import org.adelheid.hondius.container.Metadata
import org.adelheid.hondius.container.MetadataCodec
import org.adelheid.hondius.container.sortChunks
import kotlin.jvm.JvmStatic

/**
 * What a map file says about itself, read without decoding an outline or an image.
 *
 * @property versionMinor the minor version of the format the file was written in
 * @property layerCount how many raster layers the file holds
 */
class AkmSummary(
	val versionMinor: Int,
	val metadata: Metadata,
	val canvasWidth: Int,
	val canvasHeight: Int,
	val gridBits: Int,
	val territoryCount: Int,
	val layerCount: Int,
)

/**
 * Reads an `.akm` file into an [AkmMap]. It refuses, with a [FormatException], anything that is not a valid map in
 * its one encoding.
 */
object AkmDecoder {

	@JvmStatic
	fun decode(data: ByteArray): AkmMap {
		val file = ContainerCodec.read(data, AkmFormat.SIGNATURE)
		val chunks = sortChunks(file.chunks, AkmFormat.CHUNKS)
		val geometry = decodeGeometry(BinaryReader(chunks.single(AkmFormat.GEOM)!!.payload))
		val connections = decodeConnections(BinaryReader(chunks.single(AkmFormat.ADJC)!!.payload), geometry.territories.size)
		val shapes = chunks.single(AkmFormat.SHAP)?.let { decodeShapes(BinaryReader(it.payload), geometry.bounds) }
		val layers = chunks.all(AkmFormat.LAYR).map { decodeLayer(BinaryReader(it.payload)) }
		for (i in 1 until layers.size) {
			if (layers[i].zOrder < layers[i - 1].zOrder) {
				throw FormatException(FormatError.NOT_CANONICAL, "Layer '${layers[i].name}' comes after a layer with a higher z-order")
			}
		}
		return AkmMap(
			canvasWidth = geometry.width,
			canvasHeight = geometry.height,
			territories = geometry.territories,
			connections = connections,
			gridBits = geometry.gridBits,
			land = shapes?.first.orEmpty(),
			lines = shapes?.second.orEmpty(),
			layers = layers,
			metadata = file.metadata,
			carriedChunks = chunks.carried,
		)
	}

	/**
	 * Reads what a file says about itself: its metadata, canvas, grid, and how many territories and layers it has.
	 *
	 * This is for listing files cheaply. It checks the file's structure but neither its checksum nor its content;
	 * [decode] is what establishes that a file is a valid map.
	 */
	@JvmStatic
	fun summarize(data: ByteArray): AkmSummary {
		val (versionMinor, entries) = ContainerCodec.scan(data, AkmFormat.SIGNATURE)
		val meta = entries.first()
		val metadata = MetadataCodec.decode(data.copyOfRange(meta.payloadOffset, meta.payloadOffset + meta.payloadLength))
		val geometry = entries.firstOrNull { it.tag == AkmFormat.GEOM }
			?: throw FormatException(FormatError.BAD_CHUNK_ORDER, "Chunk ${AkmFormat.GEOM} is missing")
		// The four leading fields take ten bytes at most
		val head = BinaryReader(data.copyOfRange(geometry.payloadOffset, geometry.payloadOffset + minOf(geometry.payloadLength, 16)))
		val width = head.readCount("The canvas width", AkmMap.MAX_CANVAS_DIMENSION, 1)
		val height = head.readCount("The canvas height", AkmMap.MAX_CANVAS_DIMENSION, 1)
		val gridBits = head.readU8()
		val territories = head.readCount("The number of territories", AkmMap.MAX_TERRITORIES, 1)
		return AkmSummary(versionMinor, metadata, width, height, gridBits, territories, entries.count { it.tag == AkmFormat.LAYR })
	}

	/** The largest x and y a coordinate may have, and the points read so far against the file-wide limit. */
	private class Bounds(val maxX: Int, val maxY: Int) {
		var points = 0L
	}

	private class Geometry(val width: Int, val height: Int, val gridBits: Int, val territories: List<AkmTerritory>, val bounds: Bounds)

	private fun decodeGeometry(reader: BinaryReader): Geometry {
		val width = reader.readCount("The canvas width", AkmMap.MAX_CANVAS_DIMENSION, 1)
		val height = reader.readCount("The canvas height", AkmMap.MAX_CANVAS_DIMENSION, 1)
		val gridBits = reader.readU8()
		if (gridBits > AkmMap.MAX_GRID_BITS) throw FormatException(FormatError.BAD_VALUE, "The grid is 0 to ${AkmMap.MAX_GRID_BITS} bits, got $gridBits")
		val bounds = Bounds(width shl gridBits, height shl gridBits)
		val count = reader.readCount("The number of territories", AkmMap.MAX_TERRITORIES, 1)
		val territories = ArrayList<AkmTerritory>(minOf(count, reader.remaining))
		repeat(count) { index ->
			val id = reader.readUuid()
			val name = reader.readString("The name of territory $index", AkmMap.MAX_NAME_BYTES)
			val flags = reader.readU8()
			if (flags and AkmFormat.TERRITORY_HAS_ANCHOR.inv() != 0) {
				throw FormatException(FormatError.BAD_STRUCTURE, "Territory $index sets a reserved flag bit")
			}
			val anchor = if (flags and AkmFormat.TERRITORY_HAS_ANCHOR != 0) {
				val x = reader.readUvarint()
				val y = reader.readUvarint()
				if (x > bounds.maxX || y > bounds.maxY) throw FormatException(FormatError.BAD_GEOMETRY, "Territory $index has its anchor off the canvas")
				AkmPoint(x.toInt(), y.toInt())
			} else {
				null
			}
			val regionCount = reader.readCount("The number of regions in territory $index", AkmMap.MAX_REGIONS, 1)
			val regions = ArrayList<AkmRegion>(minOf(regionCount, reader.remaining))
			repeat(regionCount) { regions.add(readRegion(reader, bounds, "Territory $index")) }
			territories.add(AkmTerritory(id, regions, name, anchor))
		}
		reader.expectEnd("The geometry chunk")
		return Geometry(width, height, gridBits, territories, bounds)
	}

	private fun decodeConnections(reader: BinaryReader, territoryCount: Int): List<AkmConnection> {
		val count = reader.readCount("The number of connections", AkmMap.MAX_CONNECTIONS)
		val connections = ArrayList<AkmConnection>(minOf(count, reader.remaining))
		var previousA = -1L
		var previousB = -1L
		repeat(count) {
			val a = reader.readUvarint()
			val b = reader.readUvarint()
			val flags = reader.readU8()
			if (flags and AkmFormat.CONNECTION_BIDIRECTIONAL.inv() != 0) {
				throw FormatException(FormatError.BAD_STRUCTURE, "A connection sets a reserved flag bit")
			}
			val bidirectional = flags and AkmFormat.CONNECTION_BIDIRECTIONAL != 0
			if (a >= territoryCount || b >= territoryCount) {
				throw FormatException(FormatError.BAD_REFERENCE, "A connection names territory $a or $b of $territoryCount")
			}
			if (a == b) throw FormatException(FormatError.BAD_VALUE, "Territory $a is connected to itself")
			if (bidirectional && a > b) {
				throw FormatException(FormatError.NOT_CANONICAL, "The bidirectional connection of territories $a and $b is not written with the lower one first")
			}
			if (a == previousA && b == previousB) throw FormatException(FormatError.DUPLICATE, "The connection from territory $a to $b occurs twice")
			if (a < previousA || (a == previousA && b < previousB)) {
				throw FormatException(FormatError.NOT_CANONICAL, "The connections are not sorted by their first territory, then their second")
			}
			previousA = a
			previousB = b
			connections.add(AkmConnection(a.toInt(), b.toInt(), bidirectional))
		}
		reader.expectEnd("The connection chunk")
		return connections
	}

	private fun decodeShapes(reader: BinaryReader, bounds: Bounds): Pair<List<AkmRegion>, List<AkmLine>> {
		val landCount = reader.readCount("The number of land shapes", AkmMap.MAX_SHAPES)
		val land = ArrayList<AkmRegion>(minOf(landCount, reader.remaining))
		repeat(landCount) { index -> land.add(readRegion(reader, bounds, "Land shape $index")) }
		val lineCount = reader.readCount("The number of lines", AkmMap.MAX_SHAPES)
		val lines = ArrayList<AkmLine>(minOf(lineCount, reader.remaining))
		repeat(lineCount) { index ->
			val points = reader.readCount("The number of points in line $index", AkmMap.MAX_POINTS_PER_LIST, 2)
			lines.add(AkmLine(readPoints(reader, points, bounds, "Line $index")))
		}
		reader.expectEnd("The shape chunk")
		if (landCount == 0 && lineCount == 0) {
			throw FormatException(FormatError.NOT_CANONICAL, "A shape chunk with nothing in it is left out, not written")
		}
		return Pair(land, lines)
	}

	private fun decodeLayer(reader: BinaryReader): AkmLayer {
		val roleValue = reader.readU8()
		val role = LayerRole.fromWireValue(roleValue) ?: throw FormatException(FormatError.BAD_VALUE, "Unknown layer role: $roleValue")
		val name = reader.readString("A layer name", AkmMap.MAX_NAME_BYTES)
		val zOrder = reader.readU16()
		val blendValue = reader.readU8()
		val blendMode = BlendMode.fromWireValue(blendValue) ?: throw FormatException(FormatError.BAD_VALUE, "Unknown blend mode: $blendValue")
		val opacity = reader.readU8()
		val x = reader.readCount("A layer's x", AkmMap.MAX_CANVAS_DIMENSION)
		val y = reader.readCount("A layer's y", AkmMap.MAX_CANVAS_DIMENSION)
		val width = reader.readCount("A layer's width", AkmMap.MAX_CANVAS_DIMENSION, 1)
		val height = reader.readCount("A layer's height", AkmMap.MAX_CANVAS_DIMENSION, 1)
		val formatValue = reader.readU8()
		val format = ImageFormat.fromWireValue(formatValue) ?: throw FormatException(FormatError.BAD_VALUE, "Unknown image format: $formatValue")
		val image = when (val source = reader.readU8()) {
			AkmFormat.SOURCE_INLINE -> {
				val length = reader.readCount("The length of an inline image", Int.MAX_VALUE, 1)
				AkmImage.Inline(reader.readExact(length))
			}
			AkmFormat.SOURCE_EXTERNAL -> {
				val digest = reader.readExact(32)
				val length = reader.readUvarint()
				if (length < 1) throw FormatException(FormatError.BAD_VALUE, "An external image has no length")
				val hintCount = reader.readCount("The number of hints", AkmMap.MAX_HINTS)
				val hints = List(hintCount) { reader.readString("An image hint", AkmMap.MAX_HINT_BYTES, 1) }
				AkmImage.External(digest, length, hints)
			}
			else -> throw FormatException(FormatError.BAD_VALUE, "Unknown image source: $source")
		}
		reader.expectEnd("A layer chunk")
		return AkmLayer(role, name, zOrder, x, y, width, height, format, image, blendMode, opacity)
	}

	private fun readRegion(reader: BinaryReader, bounds: Bounds, what: String): AkmRegion {
		val ringCount = reader.readCount("The number of rings in a region", AkmMap.MAX_RINGS, 1)
		val rings = ArrayList<AkmRing>(minOf(ringCount, reader.remaining))
		repeat(ringCount) {
			val points = reader.readCount("The number of points in a ring", AkmMap.MAX_POINTS_PER_LIST, 3)
			rings.add(AkmRing(readPoints(reader, points, bounds, what)))
		}
		return AkmRegion(rings)
	}

	/**
	 * Reads [count] points: the first as it is, every further one as its step from the one before. Coordinates are
	 * held to the canvas as they are read, so no sum can run away.
	 */
	private fun readPoints(reader: BinaryReader, count: Int, bounds: Bounds, what: String): IntArray {
		bounds.points += count
		if (bounds.points > AkmMap.MAX_POINTS) {
			throw FormatException(FormatError.LIMIT_EXCEEDED, "More than ${AkmMap.MAX_POINTS} points in the file")
		}
		val points = IntArray(reader.requireCount(count, 2, "Point") * 2)
		var x = reader.readUvarint()
		var y = reader.readUvarint()
		for (i in 0 until count) {
			if (i > 0) {
				val stepX = reader.readSvarint()
				val stepY = reader.readSvarint()
				if (stepX == 0 && stepY == 0) throw FormatException(FormatError.BAD_GEOMETRY, "$what repeats a point")
				x += stepX
				y += stepY
			}
			if (x < 0 || x > bounds.maxX || y < 0 || y > bounds.maxY) {
				throw FormatException(FormatError.BAD_GEOMETRY, "$what has a point off the canvas: $x, $y")
			}
			points[i * 2] = x.toInt()
			points[i * 2 + 1] = y.toInt()
		}
		return points
	}
}
