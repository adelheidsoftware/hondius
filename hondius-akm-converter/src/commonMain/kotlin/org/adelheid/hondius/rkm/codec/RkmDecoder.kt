package org.adelheid.hondius.rkm.codec

import org.adelheid.hondius.rkm.RkmBorder
import org.adelheid.hondius.rkm.RkmField
import org.adelheid.hondius.rkm.RkmMap
import org.adelheid.hondius.rkm.RkmNucleus
import org.adelheid.hondius.rkm.RkmTerritory
import org.adelheid.hondius.rkm.RkmTerritoryIdentity
import org.adelheid.hondius.container.Sha512
import kotlin.jvm.JvmStatic

/**
 * Decodes `.rkm` (legacy raster map) binary files into [RkmMap] instances.
 *
 * The format is chunk-based: each chunk has a 4-byte tag, 4-byte big-endian length,
 * and payload data. Unknown chunks are silently skipped. The final `CKSM` chunk
 * holds a SHA-512 hash of everything preceding it and is verified at decode time.
 *
 * PNG image data is kept as opaque [ByteArray] — decoding into pixel buffers is
 * deferred to the conversion pipeline, which uses platform-specific PNG codecs.
 */
object RkmDecoder {

	/** RKM file signature: `0x83 'R' 'K' 'M' 0x0D 0x0A 0x1A 0x0A` */
	private val SIGNATURE = byteArrayOf(
		0x83.toByte(), 0x52, 0x4B, 0x4D,
		0x0D, 0x0A, 0x1A, 0x0A,
	)

	// Header (8) + at least one field tag (4) + length (4) + 64 bytes for SHA-512 = 80 min.
	private const val MIN_FILE_SIZE = 8 + 4 + 4 + 64

	@JvmStatic
	fun decode(data: ByteArray): RkmMap {
		check(data.size >= MIN_FILE_SIZE) { "file length is too short to be a valid .rkm file" }

		val reader = BinaryReader(data)
		val sig = reader.readExact(8)
		check(sig.contentEquals(SIGNATURE)) { "file signature is invalid" }

		var codename: String? = null
		var displayName: String? = null
		var author: String? = null
		var territories: Set<RkmTerritory>? = null
		var borders: Set<RkmBorder>? = null
		var basePng: ByteArray? = null
		var textPng: ByteArray? = null

		var foundChecksum = false

		while (reader.remaining > 0) {
			val chunkStart = reader.position
			val tag = reader.readExact(4)
			val fieldLength = reader.readU32()

			check(fieldLength >= 1) { "field length cannot be negative or zero" }
			check(fieldLength <= reader.remaining) { "field length is longer than the remaining bytes" }

			val payload = reader.readExact(fieldLength)

			when (RkmField.of(tag)) {
				RkmField.UNKNOWN -> { /* forward-compatible skip */ }
				RkmField.MAP_CODE_NAME -> codename = payload.decodeToString()
				RkmField.MAP_DISPLAY_NAME -> displayName = payload.decodeToString()
				RkmField.MAP_AUTHOR_NAME -> author = payload.decodeToString()
				RkmField.VERTICES -> territories = decodeVertices(payload)
				RkmField.EDGES -> borders = decodeEdges(payload)
				RkmField.MAP_IMAGE_BASE -> basePng = payload
				RkmField.MAP_IMAGE_TEXT -> textPng = payload
				RkmField.CHECKSUM -> {
					val expected = Sha512.digest(data.copyOfRange(0, chunkStart))
					check(payload.contentEquals(expected)) { "invalid checksum" }
					foundChecksum = true
				}
			}

			if (RkmField.of(tag) == RkmField.CHECKSUM) break
		}

		check(foundChecksum) { "Missing CKSM chunk" }
		checkNotNull(codename) { "Missing MCNM chunk" }
		checkNotNull(displayName) { "Missing MDNM chunk" }
		checkNotNull(author) { "Missing MATN chunk" }
		checkNotNull(territories) { "Missing VTCS chunk" }
		checkNotNull(borders) { "Missing EDGS chunk" }
		checkNotNull(basePng) { "Missing MIBS chunk" }
		checkNotNull(textPng) { "Missing MITX chunk" }

		return RkmMap(codename, displayName, author, territories, borders, basePng, textPng)
	}

	private fun decodeVertices(data: ByteArray): Set<RkmTerritory> {
		val r = BinaryReader(data)
		// 4-byte id length + 4-byte nucleus count at minimum
		val vertexCount = r.requireCount(r.readU32(), 8, "Vertex")
		val result = LinkedHashSet<RkmTerritory>(vertexCount)
		for (v in 0 until vertexCount) {
			val idLen = r.readU32()
			val id = r.readExact(idLen).decodeToString()
			// two 4-byte coordinates per nucleus
			val nucleusCount = r.requireCount(r.readU32(), 8, "Nucleus")
			val nuclei = LinkedHashSet<RkmNucleus>(nucleusCount)
			for (n in 0 until nucleusCount) {
				val x = r.readU32()
				val y = r.readU32()
				nuclei.add(RkmNucleus(x, y))
			}
			result.add(RkmTerritory(RkmTerritoryIdentity(id), nuclei))
		}
		return result
	}

	private fun decodeEdges(data: ByteArray): Set<RkmBorder> {
		val r = BinaryReader(data)
		// two 4-byte id length prefixes per edge
		val edgeCount = r.requireCount(r.readU32(), 8, "Edge")
		val result = LinkedHashSet<RkmBorder>(edgeCount)
		for (e in 0 until edgeCount) {
			val srcLen = r.readU32()
			val srcId = r.readExact(srcLen).decodeToString()
			val tgtLen = r.readU32()
			val tgtId = r.readExact(tgtLen).decodeToString()
			result.add(RkmBorder(RkmTerritoryIdentity(srcId), RkmTerritoryIdentity(tgtId)))
		}
		return result
	}

	/** Minimal big-endian binary reader. Internal to the RKM decoder. */
	private class BinaryReader(private val data: ByteArray) {
		var position: Int = 0
			private set

		val remaining: Int get() = data.size - position

		fun readExact(n: Int): ByteArray {
			check(n >= 0) { "Negative read length: $n" }
			check(remaining >= n) { "Unexpected end of data (expected $n bytes, got $remaining)" }
			val result = data.copyOfRange(position, position + n)
			position += n
			return result
		}

		fun readU32(): Int {
			val b = readExact(4)
			return ((b[0].toInt() and 0xFF) shl 24) or
				((b[1].toInt() and 0xFF) shl 16) or
				((b[2].toInt() and 0xFF) shl 8) or
				(b[3].toInt() and 0xFF)
		}

		/**
		 * Validates a length prefix before it is used to size an allocation.
		 *
		 * The read loops themselves fail fast once the buffer runs out, but a collection sized from a raw count allocates its whole backing table up front —
		 * so a four-byte field could request gigabytes before a single element is read.
		 */
		fun requireCount(count: Int, bytesPerElement: Int, label: String): Int {
			check(count >= 0) { "$label count is negative: $count" }
			check(count.toLong() * bytesPerElement <= remaining) {
				"$label declares $count entries but only $remaining bytes remain"
			}
			return count
		}
	}
}
