package org.adelheid.hondius.akp.codec

import org.adelheid.hondius.akp.AkpColor
import org.adelheid.hondius.akp.AkpPalette
import org.adelheid.hondius.container.BinaryWriter
import org.adelheid.hondius.container.Chunk
import org.adelheid.hondius.container.ContainerCodec
import org.adelheid.hondius.container.Metadata
import kotlin.jvm.JvmStatic

/** Writes an [AkpPalette] as an `.akp` file. A palette has exactly one encoding. */
object AkpEncoder {

	@JvmStatic
	fun encode(palette: AkpPalette): ByteArray {
		val chunks = listOf(
			Chunk(AkpFormat.AKMC, Chunk.REQUIRED, encodeColors(palette.roleColors)),
			Chunk(AkpFormat.PLYR, Chunk.REQUIRED, encodeColors(palette.playerColors)),
		) + palette.carriedChunks
		return ContainerCodec.write(AkpFormat.SIGNATURE, palette.metadata, chunks)
	}

	/**
	 * Returns [file] with its metadata replaced by [metadata] and everything else as it was.
	 *
	 * This is for a host that stores palette files and says who their authors are when it serves one. The file's
	 * checksum is verified and a new one is written; the colors are not decoded. [file] is expected to be a palette
	 * that [AkpDecoder.decode] accepted when it was stored.
	 */
	@JvmStatic
	fun withMetadata(file: ByteArray, metadata: Metadata): ByteArray {
		val container = ContainerCodec.read(file, AkpFormat.SIGNATURE)
		return ContainerCodec.write(AkpFormat.SIGNATURE, metadata, container.chunks, container.versionMinor)
	}

	private fun encodeColors(colors: List<AkpColor>): ByteArray {
		val out = BinaryWriter()
		out.writeUvarint(colors.size)
		for (color in colors) {
			out.writeString(color.name)
			out.writeByte(color.r)
			out.writeByte(color.g)
			out.writeByte(color.b)
			out.writeByte(color.a)
		}
		return out.toByteArray()
	}
}
