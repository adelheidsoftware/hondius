package org.adelheid.hondius.container

import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/** A file taken apart: its minor version, its metadata, and the chunks between `META` and `CKSM`. */
class ContainerFile(val versionMinor: Int, val metadata: Metadata, val chunks: List<Chunk>)

/** Where one chunk lies in a file, without its payload copied out. */
class ChunkEntry(val tag: String, val flags: Int, val encoding: Int, val payloadOffset: Int, val payloadLength: Int)

/**
 * One chunk as it was met in a [ByteSource]: its header, and the start of its payload as far as it was asked for.
 *
 * @property head the leading bytes of the payload that were read; empty when none were asked for
 */
class ScannedChunk(val tag: String, val flags: Int, val encoding: Int, val payloadLength: Int, val head: ByteArray)

/**
 * The outer structure both formats share: the header, the chunk sequence with `META` first, and the checksum last.
 * The format codecs build on it and add their own chunks.
 */
object ContainerCodec {
	const val VERSION_MAJOR: Int = 2
	const val VERSION_MINOR: Int = 0

	const val META: String = "META"
	const val CKSM: String = "CKSM"

	private const val HEADER_SIZE = 16
	private const val CHUNK_HEADER_SIZE = 10
	private const val ALGORITHM_SHA256 = 0

	/** The signature of the format named by [letters]: `0x83`, the three letters, `\r\n\x1a\n`. */
	@JvmStatic
	fun signature(letters: String): ByteArray {
		require(letters.length == 3 && letters.all { it in 'A'..'Z' }) { "A signature names its format in three capital letters" }
		return byteArrayOf(0x83.toByte(), letters[0].code.toByte(), letters[1].code.toByte(), letters[2].code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
	}

	/**
	 * Takes [data] apart, having checked the header, every chunk header, that `META` is first, that `CKSM` is last
	 * with nothing after it, and that the digest matches.
	 */
	@JvmStatic
	fun read(data: ByteArray, signature: ByteArray): ContainerFile {
		val (versionMinor, entries) = scan(data, signature)
		val checksum = entries.last()
		val reader = BinaryReader(data.copyOfRange(checksum.payloadOffset, checksum.payloadOffset + checksum.payloadLength))
		if (checksum.flags != Chunk.REQUIRED || checksum.encoding != Chunk.ENCODING_STORED || checksum.payloadLength != 1 + Sha256.SIZE) {
			throw FormatException(FormatError.BAD_STRUCTURE, "The checksum chunk is not in its defined form")
		}
		val algorithm = reader.readU8()
		if (algorithm != ALGORITHM_SHA256) throw FormatException(FormatError.BAD_VALUE, "Unknown checksum algorithm: $algorithm")
		val digest = Sha256.digest(data.copyOfRange(0, checksum.payloadOffset - CHUNK_HEADER_SIZE))
		if (!digest.contentEquals(reader.readExact(Sha256.SIZE))) {
			throw FormatException(FormatError.BAD_CHECKSUM, "The checksum does not match the content")
		}

		val meta = entries.first()
		if (meta.flags != Chunk.REQUIRED || meta.encoding != Chunk.ENCODING_STORED) {
			throw FormatException(FormatError.BAD_STRUCTURE, "The META chunk is not in its defined form")
		}
		val metadata = MetadataCodec.decode(data.copyOfRange(meta.payloadOffset, meta.payloadOffset + meta.payloadLength))
		val chunks = entries.subList(1, entries.size - 1).map { entry ->
			Chunk(entry.tag, entry.flags, data.copyOfRange(entry.payloadOffset, entry.payloadOffset + entry.payloadLength), entry.encoding)
		}
		return ContainerFile(versionMinor, metadata, chunks)
	}

	/**
	 * Lists the chunks of [data] in file order, `META` first and `CKSM` last, without copying a payload and without
	 * computing the digest. For looking into a file cheaply; [read] is what establishes that a file is intact.
	 *
	 * @return the file's minor version and its chunk entries
	 */
	@JvmStatic
	fun scan(data: ByteArray, signature: ByteArray): Pair<Int, List<ChunkEntry>> {
		if (data.size < HEADER_SIZE) throw FormatException(FormatError.TRUNCATED, "The data is shorter than a header")
		val minor = readHeader(data.copyOfRange(0, HEADER_SIZE), signature)

		val entries = ArrayList<ChunkEntry>()
		var position = HEADER_SIZE
		while (position < data.size) {
			if (entries.isNotEmpty() && entries.last().tag == CKSM) {
				throw FormatException(FormatError.BAD_STRUCTURE, "Data follows the checksum chunk")
			}
			if (data.size - position < CHUNK_HEADER_SIZE) throw FormatException(FormatError.TRUNCATED, "The data ends inside a chunk header")
			val head = readChunkHeader(data.copyOfRange(position, position + CHUNK_HEADER_SIZE))
			position += CHUNK_HEADER_SIZE
			if (head.payloadLength < 0 || head.payloadLength > data.size - position) {
				throw FormatException(FormatError.TRUNCATED, "Chunk ${head.tag} declares more bytes than the data holds")
			}
			if (head.tag == META && entries.isNotEmpty()) throw FormatException(FormatError.BAD_CHUNK_ORDER, "META occurs after the first chunk")
			entries.add(ChunkEntry(head.tag, head.flags, head.encoding, position, head.payloadLength))
			position += head.payloadLength
		}
		checkEnds(entries.firstOrNull()?.tag, entries.lastOrNull()?.tag)
		return Pair(minor, entries)
	}

	/**
	 * Lists the chunks of a file read from [source], `META` first and `CKSM` last, the way [scan] does for bytes in
	 * memory. Of each payload it reads only the leading bytes that [headLength] asks for and passes over the rest, so
	 * a large file is looked into without being loaded.
	 *
	 * @param headLength given a chunk's tag and the length of its payload, how many leading bytes of the payload to
	 *   read; more than the payload holds means all of it
	 * @return the file's minor version and its chunks
	 */
	@JvmStatic
	fun scan(source: ByteSource, signature: ByteArray, headLength: (tag: String, payloadLength: Int) -> Int): Pair<Int, List<ScannedChunk>> {
		if (source.remaining < HEADER_SIZE) throw FormatException(FormatError.TRUNCATED, "The data is shorter than a header")
		val minor = readHeader(source.read(HEADER_SIZE), signature)

		val chunks = ArrayList<ScannedChunk>()
		while (source.remaining > 0) {
			if (chunks.isNotEmpty() && chunks.last().tag == CKSM) {
				throw FormatException(FormatError.BAD_STRUCTURE, "Data follows the checksum chunk")
			}
			if (source.remaining < CHUNK_HEADER_SIZE) throw FormatException(FormatError.TRUNCATED, "The data ends inside a chunk header")
			val head = readChunkHeader(source.read(CHUNK_HEADER_SIZE))
			if (head.payloadLength < 0 || head.payloadLength > source.remaining) {
				throw FormatException(FormatError.TRUNCATED, "Chunk ${head.tag} declares more bytes than the data holds")
			}
			if (head.tag == META && chunks.isNotEmpty()) throw FormatException(FormatError.BAD_CHUNK_ORDER, "META occurs after the first chunk")
			val wanted = headLength(head.tag, head.payloadLength).coerceIn(0, head.payloadLength)
			val bytes = if (wanted > 0) source.read(wanted) else EMPTY
			source.skip((head.payloadLength - wanted).toLong())
			chunks.add(ScannedChunk(head.tag, head.flags, head.encoding, head.payloadLength, bytes))
		}
		checkEnds(chunks.firstOrNull()?.tag, chunks.lastOrNull()?.tag)
		return Pair(minor, chunks)
	}

	/** Checks the 16-byte header against [signature] and returns the minor version. */
	private fun readHeader(header: ByteArray, signature: ByteArray): Int {
		for (index in signature.indices) {
			if (header[index] != signature[index]) throw FormatException(FormatError.BAD_SIGNATURE, "The data does not start with the format's signature")
		}
		val reader = BinaryReader(header.copyOfRange(signature.size, HEADER_SIZE))
		val major = reader.readU16()
		val minor = reader.readU16()
		if (major != VERSION_MAJOR) {
			throw FormatException(FormatError.UNSUPPORTED_VERSION, "The file is version $major.$minor; this reader implements version $VERSION_MAJOR")
		}
		if (reader.readU32() != 0) throw FormatException(FormatError.BAD_STRUCTURE, "The reserved header field is not zero")
		return minor
	}

	private class ChunkHeader(val tag: String, val flags: Int, val encoding: Int, val payloadLength: Int)

	private fun readChunkHeader(bytes: ByteArray): ChunkHeader {
		val reader = BinaryReader(bytes)
		val tag = reader.readExact(4).map { (it.toInt() and 0xFF).toChar() }.joinToString("")
		val flags = reader.readU8()
		val encoding = reader.readU8()
		val length = reader.readU32()
		// Constructing the chunk header as a Chunk applies the tag and flag rules in one place
		Chunk(tag, flags, EMPTY, encoding)
		return ChunkHeader(tag, flags, encoding, length)
	}

	private fun checkEnds(firstTag: String?, lastTag: String?) {
		if (firstTag != META) throw FormatException(FormatError.BAD_CHUNK_ORDER, "The first chunk is not META")
		if (lastTag != CKSM) throw FormatException(FormatError.TRUNCATED, "The data ends without a checksum chunk")
	}

	/**
	 * Puts a file together: the header, `META` holding [metadata], [chunks] in the order given, and the checksum.
	 * The caller passes its format's chunks in the format's order, followed by the ones it carries along.
	 *
	 * @param versionMinor the minor version the header names. A file that is written again with only its metadata
	 *   changed keeps the one it had.
	 */
	@JvmStatic
	@JvmOverloads
	fun write(signature: ByteArray, metadata: Metadata, chunks: List<Chunk>, versionMinor: Int = VERSION_MINOR): ByteArray {
		require(versionMinor in 0..0xFFFF) { "A minor version is 0 to 65535, got $versionMinor" }
		val out = BinaryWriter(1024)
		out.writeBytes(signature)
		out.writeU16(VERSION_MAJOR)
		out.writeU16(versionMinor)
		out.writeU32(0)
		writeChunk(out, Chunk(META, Chunk.REQUIRED, MetadataCodec.encode(metadata)))
		for (chunk in chunks) {
			require(chunk.tag != META && chunk.tag != CKSM) { "${chunk.tag} is written by the container itself" }
			writeChunk(out, chunk)
		}
		val digest = Sha256.digest(out.toByteArray())
		writeChunk(out, Chunk(CKSM, Chunk.REQUIRED, byteArrayOf(ALGORITHM_SHA256.toByte()) + digest))
		return out.toByteArray()
	}

	private fun writeChunk(out: BinaryWriter, chunk: Chunk) {
		for (char in chunk.tag) out.writeByte(char.code)
		out.writeByte(chunk.flags)
		out.writeByte(chunk.encoding)
		out.writeU32(chunk.payload.size)
		out.writeBytes(chunk.payload)
	}

	private val EMPTY = ByteArray(0)
}
