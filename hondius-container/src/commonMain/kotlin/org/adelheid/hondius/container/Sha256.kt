package org.adelheid.hondius.container

import kotlin.jvm.JvmStatic

/**
 * SHA-256 as FIPS 180-4 defines it, in plain Kotlin: every target computes it with the same code and needs no platform
 * library or native module.
 */
object Sha256 {
	/** The length of a digest in bytes. */
	const val SIZE: Int = 32

	private const val BLOCK = 64

	private val K = intArrayOf(
		0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
		0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
		0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
		0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
		0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
		0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
		0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
		0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
		0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
		0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
		0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
		0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
		0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
		0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
		0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
		0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
	)

	/** Returns the 32-byte digest of [data]. */
	@JvmStatic
	fun digest(data: ByteArray): ByteArray {
		val state = intArrayOf(
			0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
			0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
		)
		val schedule = IntArray(64)
		val fullBlocks = data.size / BLOCK
		for (block in 0 until fullBlocks) {
			compress(state, data, block * BLOCK, schedule)
		}

		// The tail: what is left of the data, a single 1 bit, zeros, and the bit length in the last eight bytes
		val rest = data.size - fullBlocks * BLOCK
		val tail = ByteArray(if (rest < BLOCK - 8) BLOCK else 2 * BLOCK)
		data.copyInto(tail, 0, fullBlocks * BLOCK, data.size)
		tail[rest] = 0x80.toByte()
		val bits = data.size.toLong() * 8
		for (i in 0 until 8) {
			tail[tail.size - 1 - i] = (bits ushr (8 * i)).toByte()
		}
		for (offset in tail.indices step BLOCK) {
			compress(state, tail, offset, schedule)
		}

		val out = ByteArray(SIZE)
		for (i in 0 until 8) {
			out[i * 4] = (state[i] ushr 24).toByte()
			out[i * 4 + 1] = (state[i] ushr 16).toByte()
			out[i * 4 + 2] = (state[i] ushr 8).toByte()
			out[i * 4 + 3] = state[i].toByte()
		}
		return out
	}

	private fun compress(state: IntArray, block: ByteArray, offset: Int, w: IntArray) {
		for (i in 0 until 16) {
			val j = offset + i * 4
			w[i] = ((block[j].toInt() and 0xFF) shl 24) or
				((block[j + 1].toInt() and 0xFF) shl 16) or
				((block[j + 2].toInt() and 0xFF) shl 8) or
				(block[j + 3].toInt() and 0xFF)
		}
		for (i in 16 until 64) {
			val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
			val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
			w[i] = w[i - 16] + s0 + w[i - 7] + s1
		}

		var a = state[0]
		var b = state[1]
		var c = state[2]
		var d = state[3]
		var e = state[4]
		var f = state[5]
		var g = state[6]
		var h = state[7]
		for (i in 0 until 64) {
			val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
			val choice = (e and f) xor (e.inv() and g)
			val t1 = h + s1 + choice + K[i] + w[i]
			val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
			val majority = (a and b) xor (a and c) xor (b and c)
			val t2 = s0 + majority
			h = g
			g = f
			f = e
			e = d + t1
			d = c
			c = b
			b = a
			a = t1 + t2
		}
		state[0] += a
		state[1] += b
		state[2] += c
		state[3] += d
		state[4] += e
		state[5] += f
		state[6] += g
		state[7] += h
	}
}
