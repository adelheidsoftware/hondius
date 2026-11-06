package org.adelheid.hondius.akp.v1

import org.adelheid.hondius.akp.AkpColor
import org.adelheid.hondius.container.BinaryReader
import org.adelheid.hondius.container.ContainerCodec
import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException
import org.adelheid.hondius.container.Sha256
import kotlin.jvm.JvmStatic

/**
 * Reads a version 1 `.akp` file: a 16-byte header, chunks of a four-byte tag and a 32-bit length, text behind 16-bit
 * lengths, and a SHA-256 over everything before the `CKSM` chunk. Chunks with other tags are skipped, as version 1
 * prescribed.
 */
object AkpV1Decoder {
	private const val HEADER_SIZE = 16

	/**
	 * True when [data] opens with the palette signature and names version 1: a file for [decode], where a file of the
	 * current version, which opens with the same signature, is for the current decoder.
	 */
	@JvmStatic
	fun isVersion1(data: ByteArray): Boolean {
		if (data.size < HEADER_SIZE) return false
		val signature = ContainerCodec.signature("AKP")
		if (!data.copyOfRange(0, signature.size).contentEquals(signature)) return false
		return BinaryReader(data.copyOfRange(signature.size, HEADER_SIZE)).readU16() == 1
	}

	@JvmStatic
	fun decode(data: ByteArray): AkpV1Palette {
		if (data.size < HEADER_SIZE) throw FormatException(FormatError.TRUNCATED, "The data is shorter than a header")
		val reader = BinaryReader(data)
		if (!reader.readExact(8).contentEquals(ContainerCodec.signature("AKP"))) {
			throw FormatException(FormatError.BAD_SIGNATURE, "The data does not start with the palette signature")
		}
		val major = reader.readU16()
		val minor = reader.readU16()
		if (major != 1) throw FormatException(FormatError.UNSUPPORTED_VERSION, "The file is version $major.$minor; this reader implements version 1")
		reader.readExact(4)

		var palette: AkpV1Palette? = null
		var roleColors: List<AkpColor>? = null
		var playerColors: List<AkpColor>? = null
		var checked = false
		while (reader.remaining > 0) {
			val chunkStart = reader.position
			val tag = reader.readExact(4).map { (it.toInt() and 0xFF).toChar() }.joinToString("")
			val length = reader.readU32()
			val payload = BinaryReader(reader.readExact(length))
			when (tag) {
				"META" -> {
					val id = payload.readUuid()
					val name = payload.readV1String()
					val description = payload.readV1String()
					val authorCount = payload.requireCount(payload.readU16(), 18, "Author")
					val authors = List(authorCount) { AkpV1Author(payload.readUuid(), payload.readV1String()) }
					palette = AkpV1Palette(id, name, description, authors, payload.readI64(), payload.readV1String(), emptyList(), emptyList())
				}
				"AKMC" -> roleColors = readColors(payload)
				"PLYR" -> playerColors = readColors(payload)
				"CKSM" -> {
					if (payload.readU8() != 0) throw FormatException(FormatError.BAD_VALUE, "Unknown checksum algorithm")
					if (!payload.readExact(payload.remaining).contentEquals(Sha256.digest(data.copyOfRange(0, chunkStart)))) {
						throw FormatException(FormatError.BAD_CHECKSUM, "The checksum does not match the content")
					}
					checked = true
				}
			}
			if (checked) break
		}
		if (!checked) throw FormatException(FormatError.TRUNCATED, "The data ends without a checksum chunk")
		val meta = palette ?: throw FormatException(FormatError.BAD_CHUNK_ORDER, "Chunk META is missing")
		return meta.copy(
			roleColors = roleColors ?: throw FormatException(FormatError.BAD_CHUNK_ORDER, "Chunk AKMC is missing"),
			playerColors = playerColors ?: throw FormatException(FormatError.BAD_CHUNK_ORDER, "Chunk PLYR is missing"),
		)
	}

	private fun readColors(payload: BinaryReader): List<AkpColor> {
		val count = payload.requireCount(payload.readU16(), 6, "Color")
		return List(count) { AkpColor(payload.readV1String(), payload.readU8(), payload.readU8(), payload.readU8(), payload.readU8()) }
	}

	/** Version 1 text: a 16-bit byte length, then UTF-8. */
	private fun BinaryReader.readV1String(): String = readExact(readU16()).decodeToString()
}
