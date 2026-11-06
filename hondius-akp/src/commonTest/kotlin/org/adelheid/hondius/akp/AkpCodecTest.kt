package org.adelheid.hondius.akp

import org.adelheid.hondius.akp.codec.AkpDecoder
import org.adelheid.hondius.akp.codec.AkpEncoder
import org.adelheid.hondius.container.Author
import org.adelheid.hondius.container.BinaryWriter
import org.adelheid.hondius.container.Chunk
import org.adelheid.hondius.container.ContainerCodec
import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException
import org.adelheid.hondius.container.Metadata
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AkpCodecTest {
	private val signature = ContainerCodec.signature("AKP")

	private val roles = listOf(
		AkpColor("water", 192, 163, 146),
		AkpColor("land", 200, 183, 173),
		AkpColor("connections", 148, 125, 111),
		AkpColor("territories", 224, 219, 227),
		AkpColor("borders", 116, 79, 40),
		AkpColor("labels", 116, 79, 40, 200),
	)
	private val players = listOf(AkpColor("Salmon", 255, 140, 150), AkpColor("Teal", 0, 128, 128), AkpColor("Åsa's blue", 10, 20, 250, 128))

	private fun assertFormatError(expected: FormatError, block: () -> Unit) {
		val failure = assertFailsWith<FormatException> { block() }
		assertEquals(expected, failure.error, failure.message)
	}

	/** A color chunk payload written exactly as given, so a test can break one rule at a time. */
	private fun colors(vararg colors: AkpColor): ByteArray {
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

	private fun file(roleChunk: ByteArray, playerChunk: ByteArray, vararg more: Chunk): ByteArray =
		ContainerCodec.write(
			signature,
			Metadata(),
			listOf(Chunk("AKMC", Chunk.REQUIRED, roleChunk), Chunk("PLYR", Chunk.REQUIRED, playerChunk)) + more,
		)

	private val sortedRoles = roles.sortedBy { it.name }.toTypedArray()

	@Test
	fun aPaletteRoundTripsAndHasOneEncoding() {
		val palette = AkpPalette(
			roles,
			players,
			Metadata(name = "Dusk", generator = "test/1.0", authors = listOf(Author.Account("adelkrieg.com", "0f8fad5b-d9cb-469f-a165-70867728950e"))),
		)
		val bytes = AkpEncoder.encode(palette)
		val decoded = AkpDecoder.decode(bytes)
		assertEquals(palette, decoded)
		assertContentEquals(bytes, AkpEncoder.encode(decoded))
		// The same palette built with its role colors in another order is the same bytes
		assertContentEquals(bytes, AkpEncoder.encode(AkpPalette(roles.reversed(), players, palette.metadata)))
	}

	@Test
	fun theFileOpensWithThePaletteSignatureAndVersionTwo() {
		val bytes = AkpEncoder.encode(AkpPalette(roles, players))
		assertContentEquals(byteArrayOf(0x83.toByte(), 0x41, 0x4B, 0x50, 0x0D, 0x0A, 0x1A, 0x0A, 0, 2, 0, 0), bytes.copyOfRange(0, 12))
	}

	@Test
	fun roleColorsAreHeldSortedAndFoundByName() {
		val palette = AkpPalette(roles, players)
		assertEquals(listOf("borders", "connections", "labels", "land", "territories", "water"), palette.roleColors.map { it.name })
		assertEquals(192, palette.water.r)
		assertEquals(200, palette.labels.a)
		assertNull(palette.role("shore"))
	}

	@Test
	fun aRoleOfALaterVersionAndACarriedChunkSurviveARoundTrip() {
		val carried = Chunk("glow", Chunk.COPY_SAFE, byteArrayOf(1, 2, 3))
		val palette = AkpPalette(roles + AkpColor("shore-line", 250, 250, 240), players, carriedChunks = listOf(carried))
		val decoded = AkpDecoder.decode(AkpEncoder.encode(palette))
		assertEquals(AkpColor("shore-line", 250, 250, 240), decoded.role("shore-line"))
		assertEquals(listOf(carried), decoded.carriedChunks)
	}

	@Test
	fun aPaletteThatBreaksARuleCannotBeBuilt() {
		assertFormatError(FormatError.BAD_VALUE) { AkpPalette(roles.dropLast(1), players) }
		assertFormatError(FormatError.DUPLICATE) { AkpPalette(roles + roles[0], players) }
		assertFormatError(FormatError.BAD_VALUE) { AkpPalette(roles + AkpColor("Shore Line", 1, 2, 3), players) }
		assertFormatError(FormatError.BAD_VALUE) { AkpPalette(roles, emptyList()) }
		assertFormatError(FormatError.LIMIT_EXCEEDED) { AkpPalette(roles, List(1025) { AkpColor("Color $it", 1, 2, 3) }) }
		assertFormatError(FormatError.BAD_TEXT) { AkpPalette(roles, listOf(AkpColor("", 1, 2, 3))) }
		assertFormatError(FormatError.BAD_VALUE) { AkpColor("Loud", 256, 0, 0) }
	}

	@Test
	fun aSingleColorIsEnoughForTheFormat() {
		val palette = AkpPalette(roles, listOf(players[0]))
		assertEquals(palette, AkpDecoder.decode(AkpEncoder.encode(palette)))
	}

	@Test
	fun roleColorsOutOfOrderOrRepeatedAreRefused() {
		assertFormatError(FormatError.NOT_CANONICAL) { AkpDecoder.decode(file(colors(*roles.toTypedArray()), colors(*players.toTypedArray()))) }
		val repeated = sortedRoles.toMutableList().also { it.add(1, it[0]) }.toTypedArray()
		assertFormatError(FormatError.DUPLICATE) { AkpDecoder.decode(file(colors(*repeated), colors(*players.toTypedArray()))) }
	}

	@Test
	fun aFileLackingARequiredRoleIsRefused() {
		val fiveRoles = sortedRoles.drop(1).toTypedArray()
		assertFormatError(FormatError.BAD_VALUE) { AkpDecoder.decode(file(colors(*fiveRoles), colors(*players.toTypedArray()))) }
		val sixWithoutWater = (sortedRoles.filter { it.name != "water" } + AkpColor("zebra", 1, 2, 3)).toTypedArray()
		assertFormatError(FormatError.BAD_VALUE) { AkpDecoder.decode(file(colors(*sixWithoutWater), colors(*players.toTypedArray()))) }
	}

	@Test
	fun malformedColorChunksAreRefused() {
		val validRoles = colors(*sortedRoles)
		val validPlayers = colors(*players.toTypedArray())
		assertFormatError(FormatError.BAD_STRUCTURE) { AkpDecoder.decode(file(validRoles + byteArrayOf(0), validPlayers)) }
		assertFormatError(FormatError.BAD_STRUCTURE) { AkpDecoder.decode(file(validRoles, validPlayers + byteArrayOf(0))) }
		assertFormatError(FormatError.BAD_VALUE) { AkpDecoder.decode(file(validRoles, colors())) }
		assertFormatError(FormatError.TRUNCATED) { AkpDecoder.decode(file(validRoles, validPlayers.copyOfRange(0, validPlayers.size - 2))) }
		assertFormatError(FormatError.BAD_TEXT) { AkpDecoder.decode(file(validRoles, colors(AkpColor("", 1, 2, 3)))) }
	}

	@Test
	fun theChunkRulesOfTheFormatAreEnforced() {
		val validRoles = Chunk("AKMC", Chunk.REQUIRED, colors(*sortedRoles))
		val validPlayers = Chunk("PLYR", Chunk.REQUIRED, colors(*players.toTypedArray()))
		fun decode(vararg chunks: Chunk) = AkpDecoder.decode(ContainerCodec.write(signature, Metadata(), chunks.toList()))

		assertFormatError(FormatError.BAD_CHUNK_ORDER) { decode(validRoles) }
		assertFormatError(FormatError.BAD_CHUNK_ORDER) { decode(validPlayers, validRoles) }
		assertFormatError(FormatError.BAD_CHUNK_ORDER) { decode(validRoles, validRoles, validPlayers) }
		assertFormatError(FormatError.UNKNOWN_REQUIRED_CHUNK) { decode(validRoles, validPlayers, Chunk("NEWR", Chunk.REQUIRED, ByteArray(0))) }
		assertFormatError(FormatError.BAD_STRUCTURE) { decode(Chunk("AKMC", 0, validRoles.payload), validPlayers) }
	}

	@Test
	fun aMapOrAVersionOneFileIsNotAPalette() {
		val bytes = AkpEncoder.encode(AkpPalette(roles, players))
		assertFormatError(FormatError.BAD_SIGNATURE) { AkpDecoder.decode(bytes.copyOf().also { it[3] = 'M'.code.toByte() }) }
		assertFormatError(FormatError.UNSUPPORTED_VERSION) { AkpDecoder.decode(bytes.copyOf().also { it[9] = 1 }) }
		assertFormatError(FormatError.TRUNCATED) { AkpDecoder.decode(ByteArray(4)) }
	}
}
