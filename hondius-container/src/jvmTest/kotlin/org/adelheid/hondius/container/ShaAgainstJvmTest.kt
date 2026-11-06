package org.adelheid.hondius.container

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * The JVM ships its own SHA implementations. Ours must agree with them on input of every length, whether or not it
 * ends on a block boundary.
 */
class ShaAgainstJvmTest {
	private val random = Random(20261106)

	@Test
	fun sha256AgreesWithTheJvmOnEveryShortLength() {
		val jvm = MessageDigest.getInstance("SHA-256")
		for (length in 0..600) {
			val data = random.nextBytes(length)
			assertContentEquals(jvm.digest(data), Sha256.digest(data), "input of $length bytes")
		}
	}

	@Test
	fun sha512AgreesWithTheJvmOnEveryShortLength() {
		val jvm = MessageDigest.getInstance("SHA-512")
		for (length in 0..600) {
			val data = random.nextBytes(length)
			assertContentEquals(jvm.digest(data), Sha512.digest(data), "input of $length bytes")
		}
	}

	@Test
	fun bothAgreeWithTheJvmOnLargeInput() {
		for (length in listOf(1 shl 20, 5_000_003)) {
			val data = random.nextBytes(length)
			assertContentEquals(MessageDigest.getInstance("SHA-256").digest(data), Sha256.digest(data), "SHA-256 of $length bytes")
			assertContentEquals(MessageDigest.getInstance("SHA-512").digest(data), Sha512.digest(data), "SHA-512 of $length bytes")
		}
	}
}
