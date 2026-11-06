package org.adelheid.hondius.akp.codec

import org.adelheid.hondius.akp.AkpColor
import org.adelheid.hondius.akp.AkpPalette
import org.adelheid.hondius.container.BinaryReader
import org.adelheid.hondius.container.Chunk
import org.adelheid.hondius.container.ChunkSpec
import org.adelheid.hondius.container.ContainerCodec
import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException
import org.adelheid.hondius.container.sortChunks
import kotlin.jvm.JvmStatic

/** What the encoder and the decoder share about the palette format. */
internal object AkpFormat {
	val SIGNATURE: ByteArray = ContainerCodec.signature("AKP")

	const val AKMC = "AKMC"
	const val PLYR = "PLYR"

	/** The format's chunks after `META`, in the order the specification lists them. */
	val CHUNKS = listOf(
		ChunkSpec(AKMC, Chunk.REQUIRED, mustOccur = true),
		ChunkSpec(PLYR, Chunk.REQUIRED, mustOccur = true),
	)
}

/**
 * Reads an `.akp` file into an [AkpPalette]. It refuses, with a [FormatException], anything that is not a valid
 * palette in its one encoding.
 */
object AkpDecoder {

	@JvmStatic
	fun decode(data: ByteArray): AkpPalette {
		val file = ContainerCodec.read(data, AkpFormat.SIGNATURE)
		val chunks = sortChunks(file.chunks, AkpFormat.CHUNKS)
		val roles = decodeRoleColors(chunks.single(AkpFormat.AKMC)!!.payload)
		val players = decodePlayerColors(chunks.single(AkpFormat.PLYR)!!.payload)
		return AkpPalette(roles, players, file.metadata, chunks.carried)
	}

	private fun decodeRoleColors(payload: ByteArray): List<AkpColor> {
		val reader = BinaryReader(payload)
		val count = reader.readCount("The number of role colors", AkpPalette.MAX_ROLE_COLORS, AkpPalette.REQUIRED_ROLES.size)
		val colors = ArrayList<AkpColor>(minOf(count, reader.remaining))
		var previous: String? = null
		repeat(count) {
			val color = readColor(reader, "A role name", AkpPalette.MAX_ROLE_NAME_BYTES)
			if (previous != null && color.name <= previous) {
				val error = if (color.name == previous) FormatError.DUPLICATE else FormatError.NOT_CANONICAL
				throw FormatException(error, "Role color '${color.name}' does not follow '$previous' in ascending order")
			}
			previous = color.name
			colors.add(color)
		}
		reader.expectEnd("The role color chunk")
		return colors
	}

	private fun decodePlayerColors(payload: ByteArray): List<AkpColor> {
		val reader = BinaryReader(payload)
		val count = reader.readCount("The number of player colors", AkpPalette.MAX_PLAYER_COLORS, 1)
		val colors = ArrayList<AkpColor>(minOf(count, reader.remaining))
		repeat(count) { colors.add(readColor(reader, "A player color's name", AkpPalette.MAX_PLAYER_NAME_BYTES)) }
		reader.expectEnd("The player color chunk")
		return colors
	}

	private fun readColor(reader: BinaryReader, what: String, maxNameBytes: Int): AkpColor {
		val name = reader.readString(what, maxNameBytes, 1)
		return AkpColor(name, reader.readU8(), reader.readU8(), reader.readU8(), reader.readU8())
	}
}
