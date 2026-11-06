package org.adelheid.hondius.container

/**
 * Bytes read from front to back, such as a file on disk. It lets a reader look into a file without loading it
 * whole: what the reader does not need, it passes over.
 */
interface ByteSource {
	/** How many bytes are left. */
	val remaining: Long

	/** The next [count] bytes. A reader asks only for what [remaining] covers. */
	fun read(count: Int): ByteArray

	/** Passes over the next [count] bytes. A reader passes over only what [remaining] covers. */
	fun skip(count: Long)
}

/** A [ByteSource] over bytes that are already in memory. */
class ByteArraySource(private val data: ByteArray) : ByteSource {
	private var position = 0

	override val remaining: Long get() = (data.size - position).toLong()

	override fun read(count: Int): ByteArray {
		require(count >= 0 && count <= remaining) { "Cannot read $count bytes, $remaining are left" }
		return data.copyOfRange(position, position + count).also { position += count }
	}

	override fun skip(count: Long) {
		require(count >= 0 && count <= remaining) { "Cannot pass over $count bytes, $remaining are left" }
		position += count.toInt()
	}
}
