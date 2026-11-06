package org.adelheid.hondius.akp.v1

import org.adelheid.hondius.akp.AkpColor
import org.adelheid.hondius.akp.codec.AkpDecoder
import org.adelheid.hondius.akp.codec.AkpEncoder
import org.adelheid.hondius.container.BinaryWriter
import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException
import org.adelheid.hondius.container.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Writes version 1 `.akp` bytes, which nothing but these tests still needs to produce. */
internal object AkpV1TestEncoder {
	fun encode(palette: AkpV1Palette): ByteArray {
		val out = BinaryWriter()
		out.writeBytes(byteArrayOf(0x83.toByte(), 0x41, 0x4B, 0x50, 0x0D, 0x0A, 0x1A, 0x0A))
		out.writeU16(1)
		out.writeU16(0)
		out.writeU32(0)

		val meta = BinaryWriter()
		meta.writeUuid(palette.paletteId)
		meta.writeV1String(palette.displayName)
		meta.writeV1String(palette.description)
		meta.writeU16(palette.authors.size)
		for (author in palette.authors) {
			meta.writeUuid(author.authorId)
			meta.writeV1String(author.name)
		}
		meta.writeI64(palette.createdAtEpochSeconds)
		meta.writeV1String(palette.editorVersion)
		chunk(out, "META", meta.toByteArray())
		chunk(out, "AKMC", colors(palette.roleColors))
		chunk(out, "PLYR", colors(palette.playerColors))
		chunk(out, "CKSM", byteArrayOf(0) + Sha256.digest(out.toByteArray()))
		return out.toByteArray()
	}

	private fun colors(colors: List<AkpColor>): ByteArray {
		val out = BinaryWriter()
		out.writeU16(colors.size)
		for (color in colors) {
			out.writeV1String(color.name)
			out.writeByte(color.r)
			out.writeByte(color.g)
			out.writeByte(color.b)
			out.writeByte(color.a)
		}
		return out.toByteArray()
	}

	private fun chunk(out: BinaryWriter, tag: String, payload: ByteArray) {
		out.writeBytes(tag.encodeToByteArray())
		out.writeU32(payload.size)
		out.writeBytes(payload)
	}

	private fun BinaryWriter.writeV1String(value: String) {
		val bytes = value.encodeToByteArray()
		writeU16(bytes.size)
		writeBytes(bytes)
	}
}

class AkpV1ConverterTest {
	private val v1 = AkpV1Palette(
		paletteId = "11111111-2222-3333-4444-555555555555",
		displayName = "Dusk",
		description = "Evening colors",
		authors = listOf(AkpV1Author("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "Tester")),
		createdAtEpochSeconds = 1_700_000_000L,
		editorVersion = "mercator/0.9.0",
		// Version 1 kept these in the order they were written, which need not be sorted
		roleColors = listOf("water", "land", "connections", "territories", "borders", "labels").mapIndexed { index, role ->
			AkpColor(role, 10 * index, 20, 30)
		},
		playerColors = listOf(AkpColor("Salmon", 255, 140, 150), AkpColor("Teal", 0, 128, 128, 200)),
	)

	@Test
	fun aVersionOneFileReadsBackAsItWasWritten() {
		assertEquals(v1, AkpV1Decoder.decode(AkpV1TestEncoder.encode(v1)))
	}

	@Test
	fun conversionKeepsTheColorsAndTheNameAndDropsTheRest() {
		val palette = AkpV1Converter.convert(AkpV1TestEncoder.encode(v1))
		assertEquals(v1.playerColors, palette.playerColors)
		assertEquals(v1.roleColors.sortedBy { it.name }, palette.roleColors)
		assertEquals("Dusk", palette.metadata.name)
		assertEquals("mercator/0.9.0", palette.metadata.generator)
		assertEquals(emptyList(), palette.metadata.authors)
		assertEquals(palette, AkpDecoder.decode(AkpEncoder.encode(palette)))
	}

	@Test
	fun aBlankToolStampIsLeftOut() {
		assertNull(AkpV1Converter.convert(v1.copy(editorVersion = "")).metadata.generator)
	}

	@Test
	fun aDamagedOrForeignFileIsRefused() {
		val bytes = AkpV1TestEncoder.encode(v1)
		// Offset 30 lies inside the palette id, so the structure still parses and only the digest disagrees
		val damaged = bytes.copyOf().also { it[30] = (it[30] + 1).toByte() }
		assertEquals(FormatError.BAD_CHECKSUM, assertFailsWith<FormatException> { AkpV1Decoder.decode(damaged) }.error)
		val current = AkpEncoder.encode(AkpV1Converter.convert(v1))
		assertEquals(FormatError.UNSUPPORTED_VERSION, assertFailsWith<FormatException> { AkpV1Decoder.decode(current) }.error)
		assertEquals(FormatError.UNSUPPORTED_VERSION, assertFailsWith<FormatException> { AkpDecoder.decode(bytes) }.error)
		assertEquals(FormatError.TRUNCATED, assertFailsWith<FormatException> { AkpV1Decoder.decode(bytes.copyOfRange(0, bytes.size - 10)) }.error)
	}
}
