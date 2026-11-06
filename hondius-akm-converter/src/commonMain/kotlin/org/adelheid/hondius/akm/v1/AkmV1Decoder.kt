package org.adelheid.hondius.akm.v1

import org.adelheid.hondius.akm.AkmMap
import org.adelheid.hondius.akm.BlendMode
import org.adelheid.hondius.akm.ImageFormat
import org.adelheid.hondius.container.BinaryReader
import org.adelheid.hondius.container.ContainerCodec
import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException
import org.adelheid.hondius.container.Sha256
import kotlin.jvm.JvmStatic

/**
 * Reads a version 1 `.akm` file: a 16-byte header, chunks of a four-byte tag and a 32-bit length, text behind 16-bit
 * lengths, coordinates as 32-bit floats, and a SHA-256 over everything before the `CKSM` chunk. Chunks with other
 * tags are skipped, as version 1 prescribed.
 */
object AkmV1Decoder {
	private const val HEADER_SIZE = 16

	/**
	 * True when [data] opens with the map signature and names version 1: a file for [decode], where a file of the
	 * current version, which opens with the same signature, is for the current decoder.
	 */
	@JvmStatic
	fun isVersion1(data: ByteArray): Boolean {
		if (data.size < HEADER_SIZE) return false
		val signature = ContainerCodec.signature("AKM")
		if (!data.copyOfRange(0, signature.size).contentEquals(signature)) return false
		return BinaryReader(data.copyOfRange(signature.size, HEADER_SIZE)).readU16() == 1
	}

	@JvmStatic
	fun decode(data: ByteArray): AkmV1Map {
		if (data.size < HEADER_SIZE) throw FormatException(FormatError.TRUNCATED, "The data is shorter than a header")
		val reader = BinaryReader(data)
		if (!reader.readExact(8).contentEquals(ContainerCodec.signature("AKM"))) {
			throw FormatException(FormatError.BAD_SIGNATURE, "The data does not start with the map signature")
		}
		val major = reader.readU16()
		val minor = reader.readU16()
		if (major != 1) throw FormatException(FormatError.UNSUPPORTED_VERSION, "The file is version $major.$minor; this reader implements version 1")
		reader.readExact(4)

		var meta: AkmV1Map? = null
		var territories: List<AkmV1Territory>? = null
		var edges: List<AkmV1Edge>? = null
		val layers = ArrayList<AkmV1Layer>()
		var checked = false
		while (reader.remaining > 0 && !checked) {
			val chunkStart = reader.position
			val tag = reader.readExact(4).map { (it.toInt() and 0xFF).toChar() }.joinToString("")
			val payload = BinaryReader(reader.readExact(reader.readU32()))
			when (tag) {
				"META" -> meta = readMeta(payload)
				"GEOM" -> {
					// A UUID, a label length, two centroid floats and a region count
					val count = payload.requireCount(payload.readU32(), 26, "Territory")
					territories = List(count) { readTerritory(payload) }
				}
				"ADJC" -> {
					val count = payload.requireCount(payload.readU32(), 33, "Edge")
					edges = List(count) { AkmV1Edge(payload.readUuid(), payload.readUuid(), payload.readU8() and 1 != 0) }
				}
				"LAYR" -> layers.add(readLayer(payload))
				"CKSM" -> {
					if (payload.readU8() != 0) throw FormatException(FormatError.BAD_VALUE, "Unknown checksum algorithm")
					if (!payload.readExact(payload.remaining).contentEquals(Sha256.digest(data.copyOfRange(0, chunkStart)))) {
						throw FormatException(FormatError.BAD_CHECKSUM, "The checksum does not match the content")
					}
					checked = true
				}
			}
		}
		if (!checked) throw FormatException(FormatError.TRUNCATED, "The data ends without a checksum chunk")
		val header = meta ?: throw FormatException(FormatError.BAD_CHUNK_ORDER, "Chunk META is missing")
		return AkmV1Map(
			header.mapId, header.canvasWidth, header.canvasHeight, header.displayName, header.description, header.authors,
			header.createdAtEpochSeconds, header.editorVersion,
			territories ?: throw FormatException(FormatError.BAD_CHUNK_ORDER, "Chunk GEOM is missing"),
			edges ?: throw FormatException(FormatError.BAD_CHUNK_ORDER, "Chunk ADJC is missing"),
			layers,
		)
	}

	private fun readMeta(payload: BinaryReader): AkmV1Map {
		val id = payload.readUuid()
		val width = payload.readU32()
		val height = payload.readU32()
		val onCanvas = width in 1..AkmMap.MAX_CANVAS_DIMENSION && height in 1..AkmMap.MAX_CANVAS_DIMENSION &&
			width.toLong() * height <= AkmMap.MAX_CANVAS_PIXELS
		if (!onCanvas) throw FormatException(FormatError.LIMIT_EXCEEDED, "A canvas of $width by $height is outside what a map may have")
		val name = payload.readV1String()
		val description = payload.readV1String()
		val authorCount = payload.requireCount(payload.readU16(), 18, "Author")
		val authors = List(authorCount) { AkmV1Author(payload.readUuid(), payload.readV1String()) }
		return AkmV1Map(id, width, height, name, description, authors, payload.readI64(), payload.readV1String(), emptyList(), emptyList(), emptyList())
	}

	private fun readTerritory(payload: BinaryReader): AkmV1Territory {
		val id = payload.readUuid()
		val label = payload.readV1String()
		val centroidX = payload.readF32()
		val centroidY = payload.readF32()
		val regionCount = payload.requireCount(payload.readU16(), 2, "Region")
		val regions = List(regionCount) {
			// A point count and three points
			val ringCount = payload.requireCount(payload.readU16(), 28, "Ring")
			if (ringCount == 0) throw FormatException(FormatError.BAD_GEOMETRY, "A region has no ring")
			AkmV1Region(List(ringCount) {
				val pointCount = payload.requireCount(payload.readU32(), 8, "Point")
				if (pointCount < 3) throw FormatException(FormatError.BAD_GEOMETRY, "A ring has fewer than three points")
				AkmV1Ring(FloatArray(pointCount * 2) { payload.readF32() })
			})
		}
		return AkmV1Territory(id, label, centroidX, centroidY, regions)
	}

	private fun readLayer(payload: BinaryReader): AkmV1Layer {
		val name = payload.readV1String()
		val zOrder = payload.readU16()
		val blendValue = payload.readU8()
		val blendMode = BlendMode.fromWireValue(blendValue) ?: throw FormatException(FormatError.BAD_VALUE, "Unknown blend mode: $blendValue")
		val opacity = payload.readU8()
		val formatValue = payload.readU8()
		val format = ImageFormat.fromWireValue(formatValue) ?: throw FormatException(FormatError.BAD_VALUE, "Unknown image format: $formatValue")
		return AkmV1Layer(name, zOrder, blendMode, opacity, format, payload.readExact(payload.readU32()))
	}

	/** Version 1 text: a 16-bit byte length, then UTF-8. */
	private fun BinaryReader.readV1String(): String = readExact(readU16()).decodeToString()
}
