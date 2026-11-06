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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The palette files in `spec/conformance`: every valid one must decode to the form written beside it, and every
 * invalid one must be refused for the reason written beside it. Any implementation of the format can run the same
 * files.
 *
 * The files are written from the cases below when the environment variable `HONDIUS_WRITE_CONFORMANCE` is `1`;
 * otherwise the test only reads them, so a change to the codec that alters a byte or an answer fails here.
 */
class ConformanceTest {
	private val folder = File("../spec/conformance/akp")
	private val write = System.getenv("HONDIUS_WRITE_CONFORMANCE") == "1"
	private val signature = ContainerCodec.signature("AKP")

	private val roles = listOf(
		AkpColor("borders", 116, 79, 40),
		AkpColor("connections", 148, 125, 111),
		AkpColor("labels", 116, 79, 40),
		AkpColor("land", 200, 183, 173),
		AkpColor("territories", 224, 219, 227),
		AkpColor("water", 192, 163, 146),
	)
	private val players = listOf(AkpColor("Salmon", 255, 140, 150), AkpColor("Teal", 0, 128, 128))

	private val minimal = AkpPalette(roles, listOf(players[0]))
	private val full = AkpPalette(
		roles + AkpColor("shore-line", 250, 250, 240, 128),
		players + AkpColor("Åsa's blue", 10, 20, 250, 200),
		Metadata(name = "Conformance", generator = "hondius-test/1.0", authors = listOf(Author.Account("adelkrieg.com", "00000000-0000-0000-0000-000000000099"))),
		listOf(Chunk("glow", Chunk.COPY_SAFE, byteArrayOf(1, 2, 3))),
	)

	@Test
	fun validPalettesDecodeToTheFormWrittenBesideThem() {
		val cases = listOf(Pair("minimal", minimal), Pair("full", full))
		for ((name, palette) in cases) {
			val file = File(folder, "valid/$name.akp")
			val expected = File(folder, "valid/$name.txt")
			val bytes = AkpEncoder.encode(palette)
			if (write) {
				file.parentFile.mkdirs()
				file.writeBytes(bytes)
				expected.writeText(describe(palette))
			}
			assertContentEquals(bytes, file.readBytes(), "$name: the codec no longer writes the file as it is in the repository")
			val decoded = AkpDecoder.decode(file.readBytes())
			assertEquals(expected.readText(), describe(decoded), name)
			assertContentEquals(file.readBytes(), AkpEncoder.encode(decoded), name)
		}
		assertEquals(cases.map { it.first }.toSet(), folderNames("valid"))
	}

	private fun colors(colors: List<AkpColor>, extra: ByteArray = ByteArray(0)): ByteArray {
		val out = BinaryWriter()
		out.writeUvarint(colors.size)
		for (color in colors) {
			out.writeString(color.name)
			out.writeByte(color.r)
			out.writeByte(color.g)
			out.writeByte(color.b)
			out.writeByte(color.a)
		}
		out.writeBytes(extra)
		return out.toByteArray()
	}

	private fun file(vararg chunks: Chunk): ByteArray = ContainerCodec.write(signature, Metadata(), chunks.toList())

	private fun invalidCases(): List<Triple<String, FormatError, ByteArray>> {
		val validRoles = Chunk("AKMC", Chunk.REQUIRED, colors(roles))
		val validPlayers = Chunk("PLYR", Chunk.REQUIRED, colors(players))
		val valid = AkpEncoder.encode(full)
		return listOf(
			Triple("map-signature", FormatError.BAD_SIGNATURE, valid.copyOf().also { it[3] = 'M'.code.toByte() }),
			Triple("version-1-header", FormatError.UNSUPPORTED_VERSION, valid.copyOf().also { it[9] = 1 }),
			Triple("content-changed-after-checksum", FormatError.BAD_CHECKSUM, valid.copyOf().also { it[60] = (it[60] + 1).toByte() }),
			Triple("no-player-chunk", FormatError.BAD_CHUNK_ORDER, file(validRoles)),
			Triple("players-before-roles", FormatError.BAD_CHUNK_ORDER, file(validPlayers, validRoles)),
			Triple("role-colors-not-sorted", FormatError.NOT_CANONICAL, file(Chunk("AKMC", Chunk.REQUIRED, colors(roles.reversed())), validPlayers)),
			Triple("role-color-twice", FormatError.DUPLICATE, file(Chunk("AKMC", Chunk.REQUIRED, colors(roles.take(1) + roles)), validPlayers)),
			Triple("required-role-missing", FormatError.BAD_VALUE, file(Chunk("AKMC", Chunk.REQUIRED, colors(roles.dropLast(1) + AkpColor("zebra", 1, 2, 3))), validPlayers)),
			Triple("role-name-with-capital", FormatError.BAD_VALUE, file(Chunk("AKMC", Chunk.REQUIRED, colors(listOf(AkpColor("Shore", 1, 2, 3)) + roles)), validPlayers)),
			Triple("no-player-colors", FormatError.BAD_VALUE, file(validRoles, Chunk("PLYR", Chunk.REQUIRED, colors(emptyList())))),
			Triple("player-color-without-name", FormatError.BAD_TEXT, file(validRoles, Chunk("PLYR", Chunk.REQUIRED, colors(listOf(AkpColor("", 1, 2, 3)))))),
			Triple("bytes-left-over-in-player-chunk", FormatError.BAD_STRUCTURE, file(validRoles, Chunk("PLYR", Chunk.REQUIRED, colors(players, byteArrayOf(0))))),
		)
	}

	@Test
	fun invalidPalettesAreRefusedForTheReasonWrittenBesideThem() {
		if (write) {
			File(folder, "invalid").mkdirs()
			for ((name, error, bytes) in invalidCases()) {
				File(folder, "invalid/$name.akp").writeBytes(bytes)
				File(folder, "invalid/$name.txt").writeText(error.name + "\n")
			}
		}
		val names = folderNames("invalid")
		assertEquals(invalidCases().map { it.first }.toSet(), names)
		for (name in names) {
			val expected = FormatError.valueOf(File(folder, "invalid/$name.txt").readText().trim())
			val failure = assertFailsWith<FormatException>(name) { AkpDecoder.decode(File(folder, "invalid/$name.akp").readBytes()) }
			assertEquals(expected, failure.error, "$name: ${failure.message}")
		}
	}

	private fun folderNames(kind: String): Set<String> =
		File(folder, kind).listFiles { file -> file.extension == "akp" }.orEmpty().map { it.nameWithoutExtension }.toSet()

	private fun describe(palette: AkpPalette): String {
		val out = StringBuilder()
		palette.metadata.name?.let { out.appendLine("name \"$it\"") }
		palette.metadata.generator?.let { out.appendLine("generator \"$it\"") }
		for (author in palette.metadata.authors) {
			out.appendLine(
				when (author) {
					is Author.Credit -> "author credit \"${author.name}\""
					is Author.Account -> "author account ${author.authority} ${author.id}"
				},
			)
		}
		for (color in palette.roleColors) out.appendLine("role ${color.name} ${color.r},${color.g},${color.b},${color.a}")
		for ((index, color) in palette.playerColors.withIndex()) out.appendLine("player $index \"${color.name}\" ${color.r},${color.g},${color.b},${color.a}")
		for (chunk in palette.carriedChunks) {
			out.appendLine("carried ${chunk.tag} flags ${chunk.flags} encoding ${chunk.encoding} payload ${chunk.payload.joinToString("") { "%02x".format(it) }}")
		}
		return out.toString()
	}
}
