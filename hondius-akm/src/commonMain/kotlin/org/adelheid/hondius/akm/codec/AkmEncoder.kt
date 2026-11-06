package org.adelheid.hondius.akm.codec

import org.adelheid.hondius.akm.AkmImage
import org.adelheid.hondius.akm.AkmLayer
import org.adelheid.hondius.akm.AkmMap
import org.adelheid.hondius.akm.AkmRegion
import org.adelheid.hondius.container.BinaryWriter
import org.adelheid.hondius.container.Chunk
import org.adelheid.hondius.container.ChunkSpec
import org.adelheid.hondius.container.ContainerCodec
import kotlin.jvm.JvmStatic

/** What the encoder and the decoder share about the map format. */
internal object AkmFormat {
	val SIGNATURE: ByteArray = ContainerCodec.signature("AKM")

	const val GEOM = "GEOM"
	const val ADJC = "ADJC"
	const val SHAP = "SHAP"
	const val LAYR = "LAYR"

	/** The format's chunks after `META`, in the order the specification lists them. */
	val CHUNKS = listOf(
		ChunkSpec(GEOM, Chunk.REQUIRED, mustOccur = true),
		ChunkSpec(ADJC, Chunk.REQUIRED, mustOccur = true),
		ChunkSpec(SHAP, 0, mustOccur = false),
		ChunkSpec(LAYR, 0, mustOccur = false, mayRepeat = true),
	)

	const val TERRITORY_HAS_ANCHOR = 1
	const val CONNECTION_BIDIRECTIONAL = 1
	const val SOURCE_INLINE = 0
	const val SOURCE_EXTERNAL = 1
}

/** Writes an [AkmMap] as an `.akm` file. A map has exactly one encoding. */
object AkmEncoder {

	@JvmStatic
	fun encode(map: AkmMap): ByteArray {
		val chunks = ArrayList<Chunk>()
		chunks.add(Chunk(AkmFormat.GEOM, Chunk.REQUIRED, encodeGeometry(map)))
		chunks.add(Chunk(AkmFormat.ADJC, Chunk.REQUIRED, encodeConnections(map)))
		if (map.land.isNotEmpty() || map.lines.isNotEmpty()) chunks.add(Chunk(AkmFormat.SHAP, 0, encodeShapes(map)))
		for (layer in map.layers) chunks.add(Chunk(AkmFormat.LAYR, 0, encodeLayer(layer)))
		chunks.addAll(map.carriedChunks)
		return ContainerCodec.write(AkmFormat.SIGNATURE, map.metadata, chunks)
	}

	private fun encodeGeometry(map: AkmMap): ByteArray {
		val out = BinaryWriter(4096)
		out.writeUvarint(map.canvasWidth)
		out.writeUvarint(map.canvasHeight)
		out.writeByte(map.gridBits)
		out.writeUvarint(map.territories.size)
		for (territory in map.territories) {
			out.writeUuid(territory.id)
			out.writeString(territory.name)
			val anchor = territory.anchor
			out.writeByte(if (anchor != null) AkmFormat.TERRITORY_HAS_ANCHOR else 0)
			if (anchor != null) {
				out.writeUvarint(anchor.x)
				out.writeUvarint(anchor.y)
			}
			out.writeUvarint(territory.regions.size)
			for (region in territory.regions) writeRegion(out, region)
		}
		return out.toByteArray()
	}

	private fun encodeConnections(map: AkmMap): ByteArray {
		val out = BinaryWriter()
		out.writeUvarint(map.connections.size)
		for (connection in map.connections) {
			out.writeUvarint(connection.a)
			out.writeUvarint(connection.b)
			out.writeByte(if (connection.bidirectional) AkmFormat.CONNECTION_BIDIRECTIONAL else 0)
		}
		return out.toByteArray()
	}

	private fun encodeShapes(map: AkmMap): ByteArray {
		val out = BinaryWriter()
		out.writeUvarint(map.land.size)
		for (region in map.land) writeRegion(out, region)
		out.writeUvarint(map.lines.size)
		for (line in map.lines) {
			out.writeUvarint(line.pointCount)
			writePoints(out, line.points)
		}
		return out.toByteArray()
	}

	private fun encodeLayer(layer: AkmLayer): ByteArray {
		val image = layer.image
		val out = BinaryWriter(if (image is AkmImage.Inline) image.data.size + 64 else 128)
		out.writeByte(layer.role.wireValue)
		out.writeString(layer.name)
		out.writeU16(layer.zOrder)
		out.writeByte(layer.blendMode.wireValue)
		out.writeByte(layer.opacity)
		out.writeUvarint(layer.x)
		out.writeUvarint(layer.y)
		out.writeUvarint(layer.width)
		out.writeUvarint(layer.height)
		out.writeByte(layer.format.wireValue)
		when (image) {
			is AkmImage.Inline -> {
				out.writeByte(AkmFormat.SOURCE_INLINE)
				out.writeUvarint(image.data.size)
				out.writeBytes(image.data)
			}
			is AkmImage.External -> {
				out.writeByte(AkmFormat.SOURCE_EXTERNAL)
				out.writeBytes(image.sha256)
				out.writeUvarint(image.length)
				out.writeUvarint(image.hints.size)
				for (hint in image.hints) out.writeString(hint)
			}
		}
		return out.toByteArray()
	}

	private fun writeRegion(out: BinaryWriter, region: AkmRegion) {
		out.writeUvarint(region.rings.size)
		for (ring in region.rings) {
			out.writeUvarint(ring.pointCount)
			writePoints(out, ring.points)
		}
	}

	/** The first point as it is, every further point as its step from the one before. */
	private fun writePoints(out: BinaryWriter, points: IntArray) {
		out.writeUvarint(points[0])
		out.writeUvarint(points[1])
		for (i in 2 until points.size step 2) {
			out.writeSvarint(points[i] - points[i - 2])
			out.writeSvarint(points[i + 1] - points[i - 1])
		}
	}
}
