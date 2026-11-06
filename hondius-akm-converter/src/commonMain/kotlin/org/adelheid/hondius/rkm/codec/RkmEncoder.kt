package org.adelheid.hondius.rkm.codec

import org.adelheid.hondius.container.BinaryWriter
import org.adelheid.hondius.container.Sha512
import org.adelheid.hondius.rkm.RkmBorder
import org.adelheid.hondius.rkm.RkmField
import org.adelheid.hondius.rkm.RkmMap
import org.adelheid.hondius.rkm.RkmTerritory
import kotlin.jvm.JvmStatic

/**
 * Encodes an [RkmMap] into the legacy `.rkm` binary format.
 *
 * Embedded PNG image data is written through as-is: `basePng` and `textPng` are opaque
 * [ByteArray]s, so a decode → encode round-trip produces byte-identical output when the
 * input set order is preserved ([RkmDecoder] uses [LinkedHashSet] to guarantee that).
 *
 * SHA-512 checksum is computed over the entire preceding byte stream (signature + all
 * fields) and appended as the final `CKSM` field.
 */
object RkmEncoder {

	/** RKM file signature: `0x83 'R' 'K' 'M' 0x0D 0x0A 0x1A 0x0A`. */
	private val SIGNATURE = byteArrayOf(
		0x83.toByte(), 0x52, 0x4B, 0x4D,
		0x0D, 0x0A, 0x1A, 0x0A,
	)

	@JvmStatic
	fun encode(map: RkmMap): ByteArray {
		val buf = BinaryWriter()
		buf.writeBytes(SIGNATURE)
		writeStringField(buf, RkmField.MAP_CODE_NAME, map.codename)
		writeStringField(buf, RkmField.MAP_DISPLAY_NAME, map.displayName)
		writeStringField(buf, RkmField.MAP_AUTHOR_NAME, map.author)
		writeVerticesField(buf, map.territories)
		writeEdgesField(buf, map.borders)
		writeRawField(buf, RkmField.MAP_IMAGE_BASE, map.basePng)
		writeRawField(buf, RkmField.MAP_IMAGE_TEXT, map.textPng)

		// CKSM covers all bytes written so far, then is itself written as a field.
		val priorBytes = buf.toByteArray()
		val checksum = Sha512.digest(priorBytes)
		writeRawField(buf, RkmField.CHECKSUM, checksum)

		return buf.toByteArray()
	}

	private fun writeStringField(buf: BinaryWriter, field: RkmField, value: String) {
		val data = value.encodeToByteArray()
		writeRawField(buf, field, data)
	}

	private fun writeRawField(buf: BinaryWriter, field: RkmField, data: ByteArray) {
		require(data.isNotEmpty()) { "RKM: field ${field.name} must have non-empty data" }
		buf.writeBytes(field.tag)
		buf.writeU32(data.size)
		buf.writeBytes(data)
	}

	private fun writeVerticesField(buf: BinaryWriter, territories: Set<RkmTerritory>) {
		// Payload layout: [u32 vertex count][vertex records...]
		val payload = BinaryWriter()
		payload.writeU32(territories.size)
		for (t in territories) {
			val idBytes = t.identity.rawValue.encodeToByteArray()
			payload.writeU32(idBytes.size)
			payload.writeBytes(idBytes)
			payload.writeU32(t.nuclei.size)
			for (n in t.nuclei) {
				payload.writeU32(n.x)
				payload.writeU32(n.y)
			}
		}
		val payloadBytes = payload.toByteArray()
		buf.writeBytes(RkmField.VERTICES.tag)
		buf.writeU32(payloadBytes.size)
		buf.writeBytes(payloadBytes)
	}

	private fun writeEdgesField(buf: BinaryWriter, borders: Set<RkmBorder>) {
		// Payload layout: [u32 edge count][edge records...]
		val payload = BinaryWriter()
		payload.writeU32(borders.size)
		for (b in borders) {
			val srcBytes = b.source.rawValue.encodeToByteArray()
			val tgtBytes = b.target.rawValue.encodeToByteArray()
			payload.writeU32(srcBytes.size)
			payload.writeBytes(srcBytes)
			payload.writeU32(tgtBytes.size)
			payload.writeBytes(tgtBytes)
		}
		val payloadBytes = payload.toByteArray()
		buf.writeBytes(RkmField.EDGES.tag)
		buf.writeU32(payloadBytes.size)
		buf.writeBytes(payloadBytes)
	}
}
