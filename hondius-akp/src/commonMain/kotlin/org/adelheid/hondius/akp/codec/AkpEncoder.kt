package org.adelheid.hondius.akp.codec

import org.adelheid.hondius.akp.AkpColor
import org.adelheid.hondius.akp.AkpPalette
import org.adelheid.hondius.container.BinaryWriter
import org.adelheid.hondius.container.Chunk
import org.adelheid.hondius.container.ContainerCodec
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
