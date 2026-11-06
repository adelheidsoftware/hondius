package org.adelheid.hondius.container

import kotlin.jvm.JvmStatic

/**
 * SHA-512 as FIPS 180-4 defines it, in plain Kotlin: every target computes it with the same code and needs no platform
 * library or native module.
 */
object Sha512 {
	/** The length of a digest in bytes. */
	const val SIZE: Int = 64

	private const val BLOCK = 128

	private val K = longArrayOf(
		0x428a2f98d728ae22L, 0x7137449123ef65cdL,
		0xb5c0fbcfec4d3b2fuL.toLong(), 0xe9b5dba58189dbbcuL.toLong(),
		0x3956c25bf348b538L, 0x59f111f1b605d019L,
		0x923f82a4af194f9buL.toLong(), 0xab1c5ed5da6d8118uL.toLong(),
		0xd807aa98a3030242uL.toLong(), 0x12835b0145706fbeL,
		0x243185be4ee4b28cL, 0x550c7dc3d5ffb4e2L,
		0x72be5d74f27b896fL, 0x80deb1fe3b1696b1uL.toLong(),
		0x9bdc06a725c71235uL.toLong(), 0xc19bf174cf692694uL.toLong(),
		0xe49b69c19ef14ad2uL.toLong(), 0xefbe4786384f25e3uL.toLong(),
		0x0fc19dc68b8cd5b5L, 0x240ca1cc77ac9c65L,
		0x2de92c6f592b0275L, 0x4a7484aa6ea6e483L,
		0x5cb0a9dcbd41fbd4L, 0x76f988da831153b5L,
		0x983e5152ee66dfabuL.toLong(), 0xa831c66d2db43210uL.toLong(),
		0xb00327c898fb213fuL.toLong(), 0xbf597fc7beef0ee4uL.toLong(),
		0xc6e00bf33da88fc2uL.toLong(), 0xd5a79147930aa725uL.toLong(),
		0x06ca6351e003826fL, 0x142929670a0e6e70L,
		0x27b70a8546d22ffcL, 0x2e1b21385c26c926L,
		0x4d2c6dfc5ac42aedL, 0x53380d139d95b3dfL,
		0x650a73548baf63deL, 0x766a0abb3c77b2a8L,
		0x81c2c92e47edaee6uL.toLong(), 0x92722c851482353buL.toLong(),
		0xa2bfe8a14cf10364uL.toLong(), 0xa81a664bbc423001uL.toLong(),
		0xc24b8b70d0f89791uL.toLong(), 0xc76c51a30654be30uL.toLong(),
		0xd192e819d6ef5218uL.toLong(), 0xd69906245565a910uL.toLong(),
		0xf40e35855771202auL.toLong(), 0x106aa07032bbd1b8L,
		0x19a4c116b8d2d0c8L, 0x1e376c085141ab53L,
		0x2748774cdf8eeb99L, 0x34b0bcb5e19b48a8L,
		0x391c0cb3c5c95a63L, 0x4ed8aa4ae3418acbL,
		0x5b9cca4f7763e373L, 0x682e6ff3d6b2b8a3L,
		0x748f82ee5defb2fcL, 0x78a5636f43172f60L,
		0x84c87814a1f0ab72uL.toLong(), 0x8cc702081a6439ecuL.toLong(),
		0x90befffa23631e28uL.toLong(), 0xa4506cebde82bde9uL.toLong(),
		0xbef9a3f7b2c67915uL.toLong(), 0xc67178f2e372532buL.toLong(),
		0xca273eceea26619cuL.toLong(), 0xd186b8c721c0c207uL.toLong(),
		0xeada7dd6cde0eb1euL.toLong(), 0xf57d4f7fee6ed178uL.toLong(),
		0x06f067aa72176fbaL, 0x0a637dc5a2c898a6L,
		0x113f9804bef90daeL, 0x1b710b35131c471bL,
		0x28db77f523047d84L, 0x32caab7b40c72493L,
		0x3c9ebe0a15c9bebcL, 0x431d67c49c100d4cL,
		0x4cc5d4becb3e42b6L, 0x597f299cfc657e2aL,
		0x5fcb6fab3ad6faecL, 0x6c44198c4a475817L,
	)

	/** Returns the 64-byte digest of [data]. */
	@JvmStatic
	fun digest(data: ByteArray): ByteArray {
		val state = longArrayOf(
			0x6a09e667f3bcc908L, 0xbb67ae8584caa73buL.toLong(),
			0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1uL.toLong(),
			0x510e527fade682d1L, 0x9b05688c2b3e6c1fuL.toLong(),
			0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L,
		)
		val schedule = LongArray(80)
		val fullBlocks = data.size / BLOCK
		for (block in 0 until fullBlocks) {
			compress(state, data, block * BLOCK, schedule)
		}

		// The tail: what is left of the data, a single 1 bit, zeros, and the bit length in the last sixteen bytes. An array
		// never holds enough bytes for the upper eight of those to be anything but zero.
		val rest = data.size - fullBlocks * BLOCK
		val tail = ByteArray(if (rest < BLOCK - 16) BLOCK else 2 * BLOCK)
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
			for (j in 0 until 8) {
				out[i * 8 + j] = (state[i] ushr (56 - 8 * j)).toByte()
			}
		}
		return out
	}

	private fun compress(state: LongArray, block: ByteArray, offset: Int, w: LongArray) {
		for (i in 0 until 16) {
			var word = 0L
			for (j in 0 until 8) {
				word = (word shl 8) or (block[offset + i * 8 + j].toLong() and 0xFF)
			}
			w[i] = word
		}
		for (i in 16 until 80) {
			val s0 = w[i - 15].rotateRight(1) xor w[i - 15].rotateRight(8) xor (w[i - 15] ushr 7)
			val s1 = w[i - 2].rotateRight(19) xor w[i - 2].rotateRight(61) xor (w[i - 2] ushr 6)
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
		for (i in 0 until 80) {
			val s1 = e.rotateRight(14) xor e.rotateRight(18) xor e.rotateRight(41)
			val choice = (e and f) xor (e.inv() and g)
			val t1 = h + s1 + choice + K[i] + w[i]
			val s0 = a.rotateRight(28) xor a.rotateRight(34) xor a.rotateRight(39)
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
