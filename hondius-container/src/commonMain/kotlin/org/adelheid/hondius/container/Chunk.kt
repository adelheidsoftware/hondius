package org.adelheid.hondius.container

/**
 * One chunk of a file: a four-character tag, its flags, how its payload is stored, and the payload as stored.
 *
 * A model keeps the chunks its reader did not know as values of this class, and its writer puts them back.
 */
class Chunk(val tag: String, val flags: Int, val payload: ByteArray, val encoding: Int = ENCODING_STORED) {
	init {
		if (tag.length != 4 || tag.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' }) {
			throw FormatException(FormatError.BAD_STRUCTURE, "A chunk tag is four ASCII letters or digits")
		}
		if (flags and (REQUIRED or COPY_SAFE).inv() != 0) {
			throw FormatException(FormatError.BAD_STRUCTURE, "Chunk $tag sets a reserved flag bit")
		}
		if (flags == (REQUIRED or COPY_SAFE)) {
			throw FormatException(FormatError.BAD_STRUCTURE, "Chunk $tag is required and marked copy-safe, which only an optional chunk may be")
		}
		if (encoding !in 0..255) throw FormatException(FormatError.BAD_STRUCTURE, "Chunk $tag has an encoding outside 0..255")
	}

	/** A reader that does not know this chunk must refuse the file. */
	val isRequired: Boolean get() = flags and REQUIRED != 0

	/** A tool that changed the content and does not know this chunk may keep it. */
	val isCopySafe: Boolean get() = flags and COPY_SAFE != 0

	override fun equals(other: Any?): Boolean =
		other is Chunk && tag == other.tag && flags == other.flags && encoding == other.encoding && payload.contentEquals(other.payload)

	override fun hashCode(): Int = 31 * (31 * (31 * tag.hashCode() + flags) + encoding) + payload.contentHashCode()

	override fun toString(): String = "Chunk($tag, flags=$flags, encoding=$encoding, ${payload.size} bytes)"

	companion object {
		const val REQUIRED: Int = 1
		const val COPY_SAFE: Int = 2

		/** The payload is stored as it is. The only encoding version 2.0 defines. */
		const val ENCODING_STORED: Int = 0
	}
}

/** What a format says about one of its chunk types: its tag, its flags, and how often it may occur. */
class ChunkSpec(val tag: String, val flags: Int, val mustOccur: Boolean, val mayRepeat: Boolean = false)

/** A file's chunks, told apart: those the format defines, by tag, and those to carry along unread. */
class SortedChunks(private val known: Map<String, List<Chunk>>, val carried: List<Chunk>) {
	/** The one chunk with [tag], or null when the file has none. */
	fun single(tag: String): Chunk? = known[tag]?.singleOrNull()

	/** Every chunk with [tag], in file order. */
	fun all(tag: String): List<Chunk> = known[tag].orEmpty()
}

/**
 * Applies a format's chunk rules to the chunks that follow `META`: the defined ones are in the format's order and
 * occur as often as allowed, an unknown required chunk is refused, an unknown optional one is carried.
 *
 * @param chunks the chunks after `META`, in file order
 * @param specs the format's chunk types, in the order its specification lists them
 */
fun sortChunks(chunks: List<Chunk>, specs: List<ChunkSpec>): SortedChunks {
	val known = LinkedHashMap<String, MutableList<Chunk>>()
	val carried = ArrayList<Chunk>()
	var lastIndex = -1
	for (chunk in chunks) {
		val index = specs.indexOfFirst { it.tag == chunk.tag }
		val understood = index >= 0 && chunk.encoding == Chunk.ENCODING_STORED
		if (!understood) {
			if (chunk.isRequired) {
				val what = if (index >= 0) "an unknown encoding (${chunk.encoding})" else "an unknown tag"
				throw FormatException(FormatError.UNKNOWN_REQUIRED_CHUNK, "Required chunk ${chunk.tag} has $what")
			}
			carried.add(chunk)
			continue
		}
		val spec = specs[index]
		if (chunk.flags != spec.flags) {
			throw FormatException(FormatError.BAD_STRUCTURE, "Chunk ${chunk.tag} has flags ${chunk.flags}, its definition gives it ${spec.flags}")
		}
		if (index < lastIndex) throw FormatException(FormatError.BAD_CHUNK_ORDER, "Chunk ${chunk.tag} is out of order")
		if (index == lastIndex && !spec.mayRepeat) {
			throw FormatException(FormatError.BAD_CHUNK_ORDER, "Chunk ${chunk.tag} occurs more than once")
		}
		lastIndex = index
		known.getOrPut(chunk.tag) { ArrayList() }.add(chunk)
	}
	for (spec in specs) {
		if (spec.mustOccur && spec.tag !in known) {
			throw FormatException(FormatError.BAD_CHUNK_ORDER, "Chunk ${spec.tag} is missing")
		}
	}
	return SortedChunks(known, carried)
}
