package org.adelheid.hondius.akm

import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException

/**
 * What an inline image may contain: pixels and what is needed to decode them, and nothing about who made it, when,
 * where or with what. The checks walk the image file's own structure; no pixel is decoded.
 *
 * PNG and WebP are checked in full. Of an AVIF file only its type is checked: finding metadata items in one needs a
 * parser for its box structure, which this library does not have.
 */
internal object ImageRules {
	private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
	private val PNG_CHUNKS = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "sRGB")
	private val WEBP_CHUNKS = setOf("VP8 ", "VP8L", "VP8X", "ALPH")

	fun check(format: ImageFormat, data: ByteArray, what: String) {
		if (data.isEmpty()) throw FormatException(FormatError.BAD_IMAGE, "$what has an empty image")
		when (format) {
			ImageFormat.PNG -> checkPng(data, what)
			ImageFormat.WEBP -> checkWebp(data, what)
			ImageFormat.AVIF -> checkAvif(data, what)
		}
	}

	private fun checkPng(data: ByteArray, what: String) {
		if (data.size < PNG_SIGNATURE.size || PNG_SIGNATURE.indices.any { data[it] != PNG_SIGNATURE[it] }) fail("$what is not a PNG file")
		var position = PNG_SIGNATURE.size
		var first = true
		var ended = false
		while (position < data.size) {
			if (ended) fail("$what has data after the end of its PNG")
			if (data.size - position < 12) fail("$what has a PNG cut short")
			val length = big(data, position)
			val type = ascii(data, position + 4)
			if (length < 0 || length > data.size - position - 12) fail("$what has a PNG cut short")
			if (first && type != "IHDR") fail("$what has a PNG that does not start with its header")
			if (type !in PNG_CHUNKS) fail("$what has a PNG with a '$type' chunk, which an image may not carry")
			ended = type == "IEND"
			first = false
			position += 12 + length
		}
		if (!ended) fail("$what has a PNG without its end")
	}

	private fun checkWebp(data: ByteArray, what: String) {
		if (data.size < 12 || ascii(data, 0) != "RIFF" || ascii(data, 8) != "WEBP") fail("$what is not a WebP file")
		if (little(data, 4).toLong() + 8 != data.size.toLong()) fail("$what has a WebP whose declared size is not its size")
		var position = 12
		while (position < data.size) {
			if (data.size - position < 8) fail("$what has a WebP cut short")
			val type = ascii(data, position)
			val length = little(data, position + 4)
			if (length < 0 || length > data.size - position - 8) fail("$what has a WebP cut short")
			if (type !in WEBP_CHUNKS) fail("$what has a WebP with a '$type' chunk, which an image may not carry")
			// Chunks are padded to an even length
			position += 8 + length + (length and 1)
		}
	}

	private fun checkAvif(data: ByteArray, what: String) {
		if (data.size < 16 || ascii(data, 4) != "ftyp") fail("$what is not an AVIF file")
		val boxSize = big(data, 0)
		if (boxSize < 16 || boxSize > data.size) fail("$what is not an AVIF file")
		val brands = (8 until boxSize - 3 step 4).filter { it != 12 }.map { ascii(data, it) }
		if ("avis" in brands) fail("$what is an AVIF image sequence, which an image may not be")
		if ("avif" !in brands) fail("$what is not an AVIF file")
	}

	private fun fail(message: String): Nothing = throw FormatException(FormatError.BAD_IMAGE, message)

	private fun big(data: ByteArray, at: Int): Int =
		((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
			((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)

	private fun little(data: ByteArray, at: Int): Int =
		(data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8) or
			((data[at + 2].toInt() and 0xFF) shl 16) or ((data[at + 3].toInt() and 0xFF) shl 24)

	private fun ascii(data: ByteArray, at: Int): String {
		val out = StringBuilder(4)
		for (i in 0 until 4) out.append((data[at + i].toInt() and 0xFF).toChar())
		return out.toString()
	}
}
