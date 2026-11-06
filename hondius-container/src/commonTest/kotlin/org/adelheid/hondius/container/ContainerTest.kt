package org.adelheid.hondius.container

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runs [block] and checks that it refuses its input for the reason [expected]. */
fun assertFormatError(expected: FormatError, block: () -> Unit) {
	val failure = assertFailsWith<FormatException> { block() }
	assertEquals(expected, failure.error, failure.message)
}

/** Replaces the checksum of a complete file, so that a test can alter content and still reach the rule it targets. */
fun reseal(file: ByteArray): ByteArray {
	val content = file.copyOfRange(0, file.size - 43)
	return content + file.copyOfRange(file.size - 43, file.size - 32) + Sha256.digest(content)
}

class BinaryIoTest {
	private fun uvarint(value: Long): ByteArray = BinaryWriter().also { it.writeUvarint(value) }.toByteArray()

	@Test
	fun uvarintsUseTheFewestBytesAndRoundTrip() {
		val expectedSizes = mapOf(0L to 1, 127L to 1, 128L to 2, 16_383L to 2, 16_384L to 3, 2_097_151L to 3, 268_435_455L to 4, 268_435_456L to 5, UVARINT_MAX to 5)
		for ((value, size) in expectedSizes) {
			val bytes = uvarint(value)
			assertEquals(size, bytes.size, "size of $value")
			assertEquals(value, BinaryReader(bytes).readUvarint())
		}
	}

	@Test
	fun aUvarintLongerThanItsValueNeedsIsRefused() {
		assertFormatError(FormatError.NOT_CANONICAL) { BinaryReader(byteArrayOf(0x80.toByte(), 0x00)).readUvarint() }
		assertFormatError(FormatError.NOT_CANONICAL) { BinaryReader(byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0x00)).readUvarint() }
	}

	@Test
	fun aUvarintBeyondThirtyTwoBitsIsRefused() {
		val tooLarge = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x1F)
		assertFormatError(FormatError.BAD_VALUE) { BinaryReader(tooLarge).readUvarint() }
		val tooLong = ByteArray(6) { 0x80.toByte() }
		assertFormatError(FormatError.BAD_VALUE) { BinaryReader(tooLong).readUvarint() }
	}

	@Test
	fun svarintsRoundTripAcrossTheirWholeRange() {
		for (value in listOf(0, 1, -1, 63, -64, 64, -65, 1_000_000, -1_000_000, Int.MAX_VALUE, Int.MIN_VALUE)) {
			val bytes = BinaryWriter().also { it.writeSvarint(value) }.toByteArray()
			assertEquals(value, BinaryReader(bytes).readSvarint(), "value $value")
		}
		assertEquals(1, BinaryWriter().also { it.writeSvarint(-1) }.toByteArray().size)
	}

	@Test
	fun countsAreHeldToTheirBounds() {
		assertEquals(5, BinaryReader(uvarint(5)).readCount("A count", 10))
		assertFormatError(FormatError.LIMIT_EXCEEDED) { BinaryReader(uvarint(11)).readCount("A count", 10) }
		assertFormatError(FormatError.BAD_VALUE) { BinaryReader(uvarint(0)).readCount("A count", 10, 1) }
	}

	@Test
	fun stringsAreValidatedText() {
		val writer = BinaryWriter().also { it.writeString("Åland, 日本") }
		assertEquals("Åland, 日本", BinaryReader(writer.toByteArray()).readString("A name", 255))

		val malformed = byteArrayOf(2, 0xC3.toByte(), 0x28)
		assertFormatError(FormatError.BAD_TEXT) { BinaryReader(malformed).readString("A name", 255) }
		val control = byteArrayOf(3, 'a'.code.toByte(), 0x0A, 'b'.code.toByte())
		assertFormatError(FormatError.BAD_TEXT) { BinaryReader(control).readString("A name", 255) }
		val tooLong = BinaryWriter().also { it.writeString("abcdef") }.toByteArray()
		assertFormatError(FormatError.BAD_TEXT) { BinaryReader(tooLong).readString("A name", 5) }
		assertFormatError(FormatError.BAD_TEXT) { BinaryReader(byteArrayOf(0)).readString("A name", 5, 1) }
		assertFormatError(FormatError.TRUNCATED) { BinaryReader(byteArrayOf(4, 'a'.code.toByte())).readString("A name", 255) }
	}

	@Test
	fun idsRoundTripAndOnlyTheLowercaseFormIsValid() {
		val id = "0f8fad5b-d9cb-469f-a165-70867728950e"
		assertEquals(id, Uuids.toText(Uuids.toBytes(id)))
		assertEquals(16, Uuids.toBytes(id).size)
		for (bad in listOf("", "0F8FAD5B-D9CB-469F-A165-70867728950E", "0f8fad5bd9cb469fa16570867728950e", "0f8fad5b-d9cb-469f-a165-70867728950g")) {
			assertTrue(!Uuids.isValid(bad), bad)
			assertFormatError(FormatError.BAD_VALUE) { Uuids.toBytes(bad) }
		}
	}

	@Test
	fun leftoverBytesAreRefused() {
		val reader = BinaryReader(byteArrayOf(1, 2))
		reader.readByte()
		assertFormatError(FormatError.BAD_STRUCTURE) { reader.expectEnd("A payload") }
	}
}

class MetadataTest {
	private val alice = Author.Account("adelkrieg.com", "0f8fad5b-d9cb-469f-a165-70867728950e")
	private val bob = Author.Account("example.org", "7c9e6679-7425-40de-944b-e07fc1f90ae7")
	private val carol = Author.Account("adelkrieg.com", "3f2504e0-4f89-41d3-9a0c-0305e82c3301")

	@Test
	fun emptyMetadataIsAnEmptyPayload() {
		assertEquals(0, MetadataCodec.encode(Metadata()).size)
		assertEquals(Metadata(), MetadataCodec.decode(ByteArray(0)))
	}

	@Test
	fun everyFieldRoundTrips() {
		val metadata = Metadata(
			name = "Mediterranean",
			generator = "mercator/1.4.0",
			authors = listOf(alice, Author.Credit("An old friend"), bob, carol),
			otherFields = listOf(MetadataField(9, byteArrayOf(1, 2, 3)), MetadataField(40, ByteArray(0))),
		)
		val bytes = MetadataCodec.encode(metadata)
		assertEquals(metadata, MetadataCodec.decode(bytes))
		assertContentEquals(bytes, MetadataCodec.encode(MetadataCodec.decode(bytes)))
		assertEquals(listOf("adelkrieg.com", "example.org"), metadata.authorities())
	}

	@Test
	fun anAccountAuthorHoldsNoName() {
		val bytes = MetadataCodec.encode(Metadata(authors = listOf(alice)))
		// field id, field length, authority count, authority, author count, kind, authority index, sixteen id bytes
		assertEquals(1 + 1 + 1 + (1 + 13) + 1 + 1 + 1 + 16, bytes.size)
	}

	@Test
	fun invalidValuesAreRefusedWhenTheModelIsBuilt() {
		assertFormatError(FormatError.BAD_TEXT) { Metadata(name = "") }
		assertFormatError(FormatError.BAD_TEXT) { Metadata(name = "x".repeat(256)) }
		assertFormatError(FormatError.BAD_TEXT) { Metadata(generator = "tab\there") }
		assertFormatError(FormatError.DUPLICATE) { Metadata(authors = listOf(alice, alice)) }
		assertFormatError(FormatError.BAD_VALUE) { Metadata(authors = listOf(Author.Account("Adelkrieg.com", alice.id))) }
		assertFormatError(FormatError.BAD_VALUE) { Metadata(authors = listOf(Author.Account("adelkrieg.com", "not-an-id"))) }
		assertFormatError(FormatError.BAD_TEXT) { Metadata(authors = listOf(Author.Credit(""))) }
		assertFormatError(FormatError.LIMIT_EXCEEDED) { Metadata(authors = List(65) { Author.Credit("Author $it") }) }
		assertFormatError(FormatError.NOT_CANONICAL) { Metadata(otherFields = listOf(MetadataField(3, ByteArray(0)))) }
	}

	private fun field(id: Int, value: ByteArray): ByteArray =
		BinaryWriter().also { it.writeUvarint(id); it.writeUvarint(value.size); it.writeBytes(value) }.toByteArray()

	private fun authors(build: BinaryWriter.() -> Unit): ByteArray = field(3, BinaryWriter().also(build).toByteArray())

	@Test
	fun fieldsOutOfOrderOrRepeatedAreRefused() {
		val name = field(1, "A".encodeToByteArray())
		val generator = field(2, "g/1".encodeToByteArray())
		assertFormatError(FormatError.NOT_CANONICAL) { MetadataCodec.decode(generator + name) }
		assertFormatError(FormatError.DUPLICATE) { MetadataCodec.decode(name + name) }
		assertFormatError(FormatError.BAD_TEXT) { MetadataCodec.decode(field(1, ByteArray(0))) }
		assertFormatError(FormatError.TRUNCATED) { MetadataCodec.decode(byteArrayOf(1, 5, 'A'.code.toByte())) }
	}

	@Test
	fun malformedAuthorListsAreRefused() {
		val id = Uuids.toBytes(alice.id)
		assertFormatError(FormatError.BAD_VALUE) {
			MetadataCodec.decode(authors { writeUvarint(0); writeUvarint(0) })
		}
		assertFormatError(FormatError.NOT_CANONICAL) {
			MetadataCodec.decode(authors { writeUvarint(1); writeString("unused.example"); writeUvarint(1); writeByte(0); writeString("Someone") })
		}
		assertFormatError(FormatError.BAD_REFERENCE) {
			MetadataCodec.decode(authors { writeUvarint(0); writeUvarint(1); writeByte(1); writeUvarint(0); writeBytes(id) })
		}
		assertFormatError(FormatError.NOT_CANONICAL) {
			MetadataCodec.decode(authors {
				writeUvarint(2); writeString("first.example"); writeString("second.example")
				writeUvarint(2); writeByte(1); writeUvarint(1); writeBytes(id); writeByte(1); writeUvarint(0); writeBytes(id)
			})
		}
		assertFormatError(FormatError.BAD_VALUE) {
			MetadataCodec.decode(authors { writeUvarint(0); writeUvarint(1); writeByte(7) })
		}
		assertFormatError(FormatError.DUPLICATE) {
			MetadataCodec.decode(authors { writeUvarint(0); writeUvarint(2); writeByte(0); writeString("Twin"); writeByte(0); writeString("Twin") })
		}
		assertFormatError(FormatError.BAD_STRUCTURE) {
			MetadataCodec.decode(authors { writeUvarint(0); writeUvarint(1); writeByte(0); writeString("Someone"); writeByte(0) })
		}
	}
}

class ContainerCodecTest {
	private val signature = ContainerCodec.signature("AKM")
	private val metadata = Metadata(name = "Test")
	private val body = Chunk("GEOM", Chunk.REQUIRED, byteArrayOf(1, 2, 3))
	private val extra = Chunk("xtra", Chunk.COPY_SAFE, byteArrayOf(9))

	private fun file(vararg chunks: Chunk): ByteArray = ContainerCodec.write(signature, metadata, chunks.toList())

	@Test
	fun aFileRoundTrips() {
		val bytes = file(body, extra)
		val read = ContainerCodec.read(bytes, signature)
		assertEquals(0, read.versionMinor)
		assertEquals(metadata, read.metadata)
		assertEquals(listOf(body, extra), read.chunks)
		assertContentEquals(bytes, ContainerCodec.write(signature, read.metadata, read.chunks))
	}

	@Test
	fun aFileCanBeWrittenUnderTheMinorVersionItCameWith() {
		val later = ContainerCodec.write(signature, metadata, listOf(body, extra), versionMinor = 3)
		val read = ContainerCodec.read(later, signature)
		assertEquals(3, read.versionMinor)
		assertEquals(listOf(body, extra), read.chunks)
	}

	/** Counts what is read from it, so a test can tell that payloads were passed over. */
	private class CountingSource(data: ByteArray) : ByteSource {
		private val inner = ByteArraySource(data)
		var bytesRead = 0
			private set

		override val remaining: Long get() = inner.remaining
		override fun read(count: Int): ByteArray = inner.read(count).also { bytesRead += count }
		override fun skip(count: Long) = inner.skip(count)
	}

	@Test
	fun aFileReadFrontToBackListsItsChunksAndReadsOnlyWhatIsAskedFor() {
		val data = file(body, extra)
		val source = CountingSource(data)
		val (minor, chunks) = ContainerCodec.scan(source, signature) { tag, _ -> if (tag == "GEOM") 2 else 0 }
		assertEquals(0, minor)
		assertEquals(ContainerCodec.scan(data, signature).second.map { it.tag }, chunks.map { it.tag })
		assertEquals(listOf("META", "GEOM", "xtra", "CKSM"), chunks.map { it.tag })
		assertEquals(3, chunks[1].payloadLength)
		assertContentEquals(byteArrayOf(1, 2), chunks[1].head)
		assertEquals(0, chunks[2].head.size)
		assertEquals(Chunk.COPY_SAFE, chunks[2].flags)
		// The header, four chunk headers and two payload bytes
		assertEquals(16 + 4 * 10 + 2, source.bytesRead)
		assertEquals(0L, source.remaining)

		// Asking for more than a payload holds reads all of it
		val whole = ContainerCodec.scan(ByteArraySource(data), signature) { _, _ -> Int.MAX_VALUE }.second
		assertContentEquals(body.payload, whole[1].head)
	}

	@Test
	fun aFileReadFrontToBackIsRefusedForTheSameFaults() {
		val data = file(body)
		fun scan(bytes: ByteArray) = ContainerCodec.scan(ByteArraySource(bytes), signature) { _, length -> length }
		assertFormatError(FormatError.TRUNCATED) { scan(data.copyOfRange(0, 10)) }
		assertFormatError(FormatError.TRUNCATED) { scan(data.copyOfRange(0, data.size - 1)) }
		assertFormatError(FormatError.TRUNCATED) { scan(data.copyOfRange(0, data.size - 43)) }
		assertFormatError(FormatError.BAD_STRUCTURE) { scan(data + byteArrayOf(0)) }
		assertFormatError(FormatError.BAD_SIGNATURE) { scan(ContainerCodec.write(ContainerCodec.signature("AKP"), metadata, listOf(body))) }
		assertFormatError(FormatError.UNSUPPORTED_VERSION) { scan(data.copyOf().also { it[9] = 3 }) }
	}

	@Test
	fun theHeaderIsSixteenBytesInTheVersionOneLayout() {
		val bytes = file(body)
		assertContentEquals(byteArrayOf(0x83.toByte(), 0x41, 0x4B, 0x4D, 0x0D, 0x0A, 0x1A, 0x0A, 0, 2, 0, 0, 0, 0, 0, 0), bytes.copyOfRange(0, 16))
	}

	@Test
	fun scanningListsEveryChunkWithoutReadingPayloads() {
		val (minor, entries) = ContainerCodec.scan(file(body, extra), signature)
		assertEquals(0, minor)
		assertEquals(listOf("META", "GEOM", "xtra", "CKSM"), entries.map { it.tag })
		assertEquals(3, entries[1].payloadLength)
	}

	@Test
	fun theHeaderIsChecked() {
		val bytes = file(body)
		assertFormatError(FormatError.TRUNCATED) { ContainerCodec.read(bytes.copyOfRange(0, 10), signature) }
		assertFormatError(FormatError.BAD_SIGNATURE) { ContainerCodec.read(bytes, ContainerCodec.signature("AKP")) }
		assertFormatError(FormatError.UNSUPPORTED_VERSION) { ContainerCodec.read(bytes.copyOf().also { it[9] = 1 }, signature) }
		assertFormatError(FormatError.UNSUPPORTED_VERSION) { ContainerCodec.read(bytes.copyOf().also { it[9] = 3 }, signature) }
		assertFormatError(FormatError.BAD_STRUCTURE) { ContainerCodec.read(reseal(bytes.copyOf().also { it[15] = 1 }), signature) }
	}

	@Test
	fun aHigherMinorVersionIsAccepted() {
		val bytes = reseal(file(body).also { it[11] = 7 })
		assertEquals(7, ContainerCodec.read(bytes, signature).versionMinor)
	}

	@Test
	fun damageIsCaughtByTheChecksum() {
		val bytes = file(body)
		val damaged = bytes.copyOf().also { it[30] = (it[30] + 1).toByte() }
		assertFormatError(FormatError.BAD_CHECKSUM) { ContainerCodec.read(damaged, signature) }
	}

	@Test
	fun theChunkSequenceIsChecked() {
		val bytes = file(body)
		assertFormatError(FormatError.TRUNCATED) { ContainerCodec.read(bytes.copyOfRange(0, bytes.size - 43), signature) }
		assertFormatError(FormatError.TRUNCATED) { ContainerCodec.read(bytes.copyOfRange(0, bytes.size - 5), signature) }
		assertFormatError(FormatError.BAD_STRUCTURE) { ContainerCodec.read(bytes + byteArrayOf(0), signature) }
		assertFormatError(FormatError.BAD_STRUCTURE) { ContainerCodec.read(bytes + bytes.copyOfRange(bytes.size - 43, bytes.size), signature) }

		// The first chunk's tag starts right after the header: make it something other than META
		val renamed = reseal(bytes.copyOf().also { it[16] = 'N'.code.toByte() })
		assertFormatError(FormatError.BAD_CHUNK_ORDER) { ContainerCodec.read(renamed, signature) }

		// The flags byte of the first chunk follows its four tag bytes
		val reservedFlag = reseal(bytes.copyOf().also { it[20] = 5 })
		assertFormatError(FormatError.BAD_STRUCTURE) { ContainerCodec.read(reservedFlag, signature) }
		val requiredAndCopySafe = reseal(bytes.copyOf().also { it[20] = 3 })
		assertFormatError(FormatError.BAD_STRUCTURE) { ContainerCodec.read(requiredAndCopySafe, signature) }
	}

	@Test
	fun chunksAreBuiltOnlyInTheirDefinedShape() {
		assertFormatError(FormatError.BAD_STRUCTURE) { Chunk("TOOLONG", 0, ByteArray(0)) }
		assertFormatError(FormatError.BAD_STRUCTURE) { Chunk("A B ", 0, ByteArray(0)) }
		assertFormatError(FormatError.BAD_STRUCTURE) { Chunk("GEOM", 4, ByteArray(0)) }
		assertFormatError(FormatError.BAD_STRUCTURE) { Chunk("GEOM", 3, ByteArray(0)) }
	}

	private val specs = listOf(
		ChunkSpec("GEOM", Chunk.REQUIRED, mustOccur = true),
		ChunkSpec("SHAP", 0, mustOccur = false),
		ChunkSpec("LAYR", 0, mustOccur = false, mayRepeat = true),
	)

	@Test
	fun aFormatsChunksAreSortedFromTheOnesToCarry() {
		val shape = Chunk("SHAP", 0, byteArrayOf(4))
		val first = Chunk("LAYR", 0, byteArrayOf(5))
		val second = Chunk("LAYR", 0, byteArrayOf(6))
		val compressed = Chunk("SHAP", 0, byteArrayOf(7), encoding = 1)
		val sorted = sortChunks(listOf(body, shape, first, second, extra), specs)
		assertEquals(body, sorted.single("GEOM"))
		assertEquals(listOf(first, second), sorted.all("LAYR"))
		assertEquals(listOf(extra), sorted.carried)

		// An optional chunk in an encoding the reader does not know is carried, not read
		val carriedEncoding = sortChunks(listOf(body, compressed), specs)
		assertEquals(null, carriedEncoding.single("SHAP"))
		assertEquals(listOf(compressed), carriedEncoding.carried)
	}

	@Test
	fun aFormatsChunkRulesAreEnforced() {
		val shape = Chunk("SHAP", 0, byteArrayOf(4))
		val layer = Chunk("LAYR", 0, byteArrayOf(5))
		assertFormatError(FormatError.BAD_CHUNK_ORDER) { sortChunks(listOf(shape), specs) }
		assertFormatError(FormatError.BAD_CHUNK_ORDER) { sortChunks(listOf(body, layer, shape), specs) }
		assertFormatError(FormatError.BAD_CHUNK_ORDER) { sortChunks(listOf(body, body), specs) }
		assertFormatError(FormatError.BAD_CHUNK_ORDER) { sortChunks(listOf(body, shape, shape), specs) }
		assertFormatError(FormatError.UNKNOWN_REQUIRED_CHUNK) { sortChunks(listOf(body, Chunk("NEWR", Chunk.REQUIRED, ByteArray(0))), specs) }
		assertFormatError(FormatError.UNKNOWN_REQUIRED_CHUNK) { sortChunks(listOf(Chunk("GEOM", Chunk.REQUIRED, ByteArray(0), encoding = 2)), specs) }
		assertFormatError(FormatError.BAD_STRUCTURE) { sortChunks(listOf(body, Chunk("SHAP", Chunk.COPY_SAFE, ByteArray(0))), specs) }
	}
}
