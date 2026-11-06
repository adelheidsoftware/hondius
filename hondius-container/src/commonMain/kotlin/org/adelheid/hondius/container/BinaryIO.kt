package org.adelheid.hondius.container

/**
 * Binary reading and writing for the codecs. Big-endian byte order throughout, as both formats are on the wire.
 */

/** The largest value a `uvarint` holds. */
const val UVARINT_MAX: Long = 0xFFFFFFFFL

/**
 * Backed by a growable [ByteArray] rather than a `MutableList<Byte>`: layer payloads are whole images, and boxing every
 * byte of a multi-megabyte image costs roughly an order of magnitude more heap than the data itself.
 */
class BinaryWriter(initialCapacity: Int = 256) {
	private var buffer = ByteArray(initialCapacity.coerceAtLeast(16))
	private var count = 0

	fun toByteArray(): ByteArray = buffer.copyOf(count)

	val size: Int get() = count

	private fun ensureCapacity(additional: Int) {
		val required = count + additional
		// Int overflow means the output has passed the maximum array size — fail loudly rather than wrap.
		require(required >= 0) { "Encoded output exceeds the maximum supported size" }
		if (required <= buffer.size) return

		var newSize = buffer.size
		while (newSize in 1..<required) newSize = newSize shl 1
		if (newSize <= 0) newSize = required
		buffer = buffer.copyOf(newSize)
	}

	fun writeByte(value: Byte) {
		ensureCapacity(1)
		buffer[count++] = value
	}

	fun writeByte(value: Int) {
		writeByte(value.toByte())
	}

	fun writeBytes(bytes: ByteArray) {
		ensureCapacity(bytes.size)
		bytes.copyInto(buffer, count)
		count += bytes.size
	}

	fun writeU16(value: Int) {
		ensureCapacity(2)
		buffer[count++] = ((value shr 8) and 0xFF).toByte()
		buffer[count++] = (value and 0xFF).toByte()
	}

	fun writeU32(value: Int) {
		ensureCapacity(4)
		buffer[count++] = ((value shr 24) and 0xFF).toByte()
		buffer[count++] = ((value shr 16) and 0xFF).toByte()
		buffer[count++] = ((value shr 8) and 0xFF).toByte()
		buffer[count++] = (value and 0xFF).toByte()
	}

	fun writeI64(value: Long) {
		ensureCapacity(8)
		for (i in 56 downTo 0 step 8) {
			buffer[count++] = ((value shr i) and 0xFF).toByte()
		}
	}

	fun writeF32(value: Float) {
		writeU32(value.toRawBits())
	}

	/** Writes [value], from 0 to [UVARINT_MAX], in the fewest base-128 bytes that hold it. */
	fun writeUvarint(value: Long) {
		require(value in 0..UVARINT_MAX) { "A uvarint holds 0 to $UVARINT_MAX, got $value" }
		var rest = value
		while (rest >= 0x80) {
			writeByte(((rest and 0x7F) or 0x80).toInt())
			rest = rest ushr 7
		}
		writeByte(rest.toInt())
	}

	fun writeUvarint(value: Int) {
		writeUvarint(value.toLong())
	}

	/** Writes a signed value so that small magnitudes of either sign take few bytes. */
	fun writeSvarint(value: Int) {
		writeUvarint(((value shl 1) xor (value shr 31)).toLong() and UVARINT_MAX)
	}

	/** Writes [value] as its UTF-8 bytes behind a `uvarint` length. */
	fun writeString(value: String) {
		val bytes = value.encodeToByteArray()
		writeUvarint(bytes.size)
		writeBytes(bytes)
	}

	/** Writes the 16 bytes that [uuid] spells in its 8-4-4-4-12 text form. */
	fun writeUuid(uuid: String) {
		writeBytes(Uuids.toBytes(uuid))
	}
}

/**
 * Reads what [BinaryWriter] writes. Every fault in the data, from a short read to a malformed number, is a
 * [FormatException].
 */
class BinaryReader(private val data: ByteArray) {
	var position: Int = 0
		private set

	val remaining: Int get() = data.size - position

	fun readExact(n: Int): ByteArray {
		// A negative n would pass the `remaining >= n` check and then fail deep inside copyOfRange.
		if (n < 0) throw FormatException(FormatError.BAD_VALUE, "Negative read length: $n")
		if (remaining < n) throw FormatException(FormatError.TRUNCATED, "Unexpected end of data (expected $n bytes, got $remaining)")
		val result = data.copyOfRange(position, position + n)
		position += n
		return result
	}

	/**
	 * Validates a count read from the data before it is used to size an allocation.
	 *
	 * Counts are attacker-controlled, and allocating from one directly lets a handful of bytes request gigabytes.
	 * Anything larger than the bytes actually remaining cannot be legitimate, so this bounds every count by the
	 * smallest possible encoding of its elements.
	 *
	 * @param count the declared element count
	 * @param bytesPerElement the minimum on-wire size of a single element
	 * @param label used in the failure message
	 */
	fun requireCount(count: Int, bytesPerElement: Int, label: String): Int {
		if (count < 0) throw FormatException(FormatError.BAD_VALUE, "$label count is negative: $count")
		if (count.toLong() * bytesPerElement > remaining) {
			throw FormatException(
				FormatError.TRUNCATED,
				"$label declares $count entries (min ${count.toLong() * bytesPerElement} bytes) but only $remaining bytes remain",
			)
		}
		return count
	}

	fun readByte(): Byte {
		if (remaining < 1) throw FormatException(FormatError.TRUNCATED, "Unexpected end of data")
		return data[position++]
	}

	fun readU8(): Int = readByte().toInt() and 0xFF

	fun readU16(): Int {
		val b = readExact(2)
		return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
	}

	fun readU32(): Int {
		val b = readExact(4)
		return ((b[0].toInt() and 0xFF) shl 24) or
			((b[1].toInt() and 0xFF) shl 16) or
			((b[2].toInt() and 0xFF) shl 8) or
			(b[3].toInt() and 0xFF)
	}

	fun readI64(): Long {
		val b = readExact(8)
		var result = 0L
		for (byte in b) {
			result = (result shl 8) or (byte.toLong() and 0xFF)
		}
		return result
	}

	fun readF32(): Float = Float.fromBits(readU32())

	/** Reads a `uvarint`, rejecting one that is longer than its value needs or larger than [UVARINT_MAX]. */
	fun readUvarint(): Long {
		var result = 0L
		for (index in 0 until 5) {
			val byte = readU8()
			result = result or ((byte and 0x7F).toLong() shl (7 * index))
			if (byte and 0x80 == 0) {
				if (index > 0 && byte == 0) {
					throw FormatException(FormatError.NOT_CANONICAL, "An integer is written with more bytes than it needs")
				}
				if (result > UVARINT_MAX) throw FormatException(FormatError.BAD_VALUE, "An integer exceeds $UVARINT_MAX")
				return result
			}
		}
		throw FormatException(FormatError.BAD_VALUE, "An integer is longer than five bytes")
	}

	fun readSvarint(): Int {
		val raw = readUvarint()
		return ((raw ushr 1) xor -(raw and 1)).toInt()
	}

	/** Reads a `uvarint` that counts or measures something, which must lie in [min]..[max]. */
	fun readCount(what: String, max: Int, min: Int = 0): Int {
		val value = readUvarint()
		if (value > max) throw FormatException(FormatError.LIMIT_EXCEEDED, "$what is $value, above the limit of $max")
		if (value < min) throw FormatException(FormatError.BAD_VALUE, "$what is $value, below the minimum of $min")
		return value.toInt()
	}

	/** Reads a `string` whose UTF-8 form must be [minBytes]..[maxBytes] long, and checks that it is valid text. */
	fun readString(what: String, maxBytes: Int, minBytes: Int = 0): String {
		val length = readUvarint()
		if (length > maxBytes || length < minBytes) {
			throw FormatException(FormatError.BAD_TEXT, "$what is $length bytes long, outside $minBytes..$maxBytes")
		}
		return Text.decode(readExact(length.toInt()), what)
	}

	/** Reads 16 bytes as the 8-4-4-4-12 text form of a UUID. */
	fun readUuid(): String = Uuids.toText(readExact(16))

	/** Fails when the data holds more than what was read: a payload contains exactly what its definition says. */
	fun expectEnd(what: String) {
		if (remaining != 0) throw FormatException(FormatError.BAD_STRUCTURE, "$what has $remaining bytes left over")
	}
}

/** The rules for text: valid UTF-8, and no control character. */
object Text {
	/** Decodes [bytes] as UTF-8, failing on a malformed sequence or a control character. */
	fun decode(bytes: ByteArray, what: String): String {
		val text = try {
			bytes.decodeToString(throwOnInvalidSequence = true)
		} catch (e: CharacterCodingException) {
			throw FormatException(FormatError.BAD_TEXT, "$what is not valid UTF-8")
		}
		check(text, what)
		return text
	}

	/** Fails when [text] cannot be written as valid UTF-8 or holds a control character. Returns its UTF-8 length. */
	fun check(text: String, what: String): Int {
		for (char in text) {
			if (char.code <= 0x1F || char.code in 0x7F..0x9F) {
				throw FormatException(FormatError.BAD_TEXT, "$what holds a control character")
			}
		}
		return try {
			text.encodeToByteArray(throwOnInvalidSequence = true).size
		} catch (e: CharacterCodingException) {
			throw FormatException(FormatError.BAD_TEXT, "$what holds an unpaired surrogate")
		}
	}

	/** Like [check], and the UTF-8 length must also lie in [minBytes]..[maxBytes]. */
	fun check(text: String, what: String, maxBytes: Int, minBytes: Int = 0) {
		val length = check(text, what)
		if (length > maxBytes || length < minBytes) {
			throw FormatException(FormatError.BAD_TEXT, "$what is $length bytes long, outside $minBytes..$maxBytes")
		}
	}
}

/** A 16-byte id and its text form, eight-four-four-four-twelve lowercase hexadecimal digits. */
object Uuids {
	private const val HEX = "0123456789abcdef"

	fun toBytes(uuid: String): ByteArray {
		if (!isValid(uuid)) throw FormatException(FormatError.BAD_VALUE, "Not an id in 8-4-4-4-12 lowercase form: $uuid")
		val out = ByteArray(16)
		var index = 0
		var high = -1
		for (char in uuid) {
			if (char == '-') continue
			val digit = HEX.indexOf(char)
			if (high < 0) {
				high = digit
			} else {
				out[index++] = ((high shl 4) or digit).toByte()
				high = -1
			}
		}
		return out
	}

	fun toText(bytes: ByteArray): String {
		require(bytes.size == 16) { "An id is 16 bytes, got ${bytes.size}" }
		val out = StringBuilder(36)
		for ((index, byte) in bytes.withIndex()) {
			if (index == 4 || index == 6 || index == 8 || index == 10) out.append('-')
			out.append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
		}
		return out.toString()
	}

	fun isValid(uuid: String): Boolean {
		if (uuid.length != 36) return false
		for ((index, char) in uuid.withIndex()) {
			val dash = index == 8 || index == 13 || index == 18 || index == 23
			if (dash != (char == '-')) return false
			if (!dash && HEX.indexOf(char) < 0) return false
		}
		return true
	}
}
