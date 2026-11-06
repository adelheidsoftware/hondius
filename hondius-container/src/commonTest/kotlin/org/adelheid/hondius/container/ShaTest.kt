package org.adelheid.hondius.container

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The digests every target must produce: the vectors published with the standard, and inputs of each length around the
 * points where the padding grows by a block.
 */
class ShaTest {
	private fun hex(bytes: ByteArray): String =
		bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

	/** A fixed byte pattern, so that every target hashes identical input. */
	private fun pattern(length: Int): ByteArray = ByteArray(length) { ((it * 31 + 7) and 0xFF).toByte() }

	@Test
	fun sha256MatchesThePublishedVectors() {
		assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(Sha256.digest(ByteArray(0))))
		assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(Sha256.digest("abc".encodeToByteArray())))
		assertEquals(
			"248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
			hex(Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
		)
		assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", hex(Sha256.digest(ByteArray(1_000_000) { 'a'.code.toByte() })))
	}

	@Test
	fun sha256PadsEveryLengthAroundABlockBoundary() {
		val expected = mapOf(
			1 to "ca358758f6d27e6cf45272937977a748fd88391db679ceda7dc7bf1f005ee879",
			55 to "8aa994584139d128848eeebc4e815639ba5ab6e6e39574195a63ac4f14f7c43b",
			56 to "ad574708f75c044c9b85de64cb568ee7711ff4f36448c6242f053ba8f6cc2b63",
			57 to "5b46e502092be01b1100193e089fdda95638c12e19a1d24f308eb2c3d3ae849d",
			63 to "280ed3e8ff1df845b2e7dfe6ac6cee817bef20e783cc65abc41b818b4d2fe076",
			64 to "c6ab9724ade5b6a7a1edfffb12f3aa9181351355af8fd08c919952ad211339dd",
			65 to "788367c73c7ddf4c53f65e68cc0d943e6227ab55b0e78ba63ace822b1c6301c0",
			119 to "3d610547d68216dedf7435a4fb6260353911f6b3fd3f18805ddb8be285d726fe",
			120 to "1f80156a804cb7862ad113e8200e9d74499723e7c7854d5f48776d3148e09656",
			127 to "192409cd280e14b743642ad1343fbd3e82d9305de72c078117745a679210cc3d",
			128 to "cc548ca2dec1f6fe4f58b2e27aa9c7521607df1130d140b55a4dad0665302356",
			129 to "81e89a7b2911aaa7795f9e3d4910cb47d6cd2b00d83b8399481527261a1a7519",
			1000 to "5097e7d587352f5097062ae679f37bda5802d9f875aba14c8cb4d1a188ada179",
		)
		for ((length, digest) in expected) {
			assertEquals(digest, hex(Sha256.digest(pattern(length))), "input of $length bytes")
		}
	}

	@Test
	fun sha512MatchesThePublishedVectors() {
		assertEquals(
			"cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
			hex(Sha512.digest(ByteArray(0))),
		)
		assertEquals(
			"ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
			hex(Sha512.digest("abc".encodeToByteArray())),
		)
		assertEquals(
			"8e959b75dae313da8cf4f72814fc143f8f7779c6eb9f7fa17299aeadb6889018501d289e4900f7e4331b99dec4b5433ac7d329eeb6dd26545e96e55b874be909",
			hex(Sha512.digest("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu".encodeToByteArray())),
		)
	}

	@Test
	fun sha512PadsEveryLengthAroundABlockBoundary() {
		val expected = mapOf(
			1 to "365d11a1dfe610b60efa996136d37ab8afd2715b8c6bc2850dc5e6005b702bb9f59b0f306ecb2c43ee44c429967d45843524eb2f7c16aab9bde142ee268b51c6",
			111 to "da780d8338a8a920ceb6892cb4ecbb0cc0c66956269aadd5dd0f48790a00857bd975890f3b2955a317738cc7a770820c29f922ffbc22020f1909d594cc987d1b",
			112 to "053182f7fa4e59f8636e415a77ed4fdc650f0a43834c9d35adf899599c3ab9c4153f02ff50bd01888060cd36a6fa12d9db242fc35164c80135613514186d5843",
			113 to "766fea2f90094ecec1166ea4d6e9ddc6ae6fbe4a060e049300ecbe631a7d6547409b6ac39865409e6dd5dada112955fddf32698feb98806c1910f446ac5af7e3",
			127 to "a7a75593826fd37d4e60f6101eabb9f8ab1cf4d5319ebc805266d5da8deb5097de1a235fc5d9d3d73c50ac100ffc75089fb454674ab61232091bd19cbdc67396",
			128 to "df007a08f3aaae47e0c92ef840ecd43645ae6098c819f2a4a66174ef1cd49e5c6dfccf0616895e570b7564af641de5863dff9f89c752913d30cf0ecf678e1635",
			129 to "a10ea769b79831bca33e9efb29fc501ded6e9c88885d079acb76eecaa8ac58d68bdc7e00103053e1519352c0d064c87accc3d553a6039decc2c4f1e7667eef79",
			239 to "38980139aa28f1ccec548e21fd73d927488c4aa749a4b304fe0c5f7797fb80a03bc0557584ef42f47e0d3ff4eee0011084ac8421cf08dd2cea6a132bcd075c90",
			240 to "eb5690f4b1753298de6626f031576ca2b93cec042d597a7a1f9e47534af610f409acb608d88fdaeade8b36fd09a6cd81f2ff8ec10962e79cd791eebc26d569f5",
			255 to "ac1ecd53106f3dfba66b7b3fcac37652aad2aad2a291c354eaca03928e74853d53c0e8c7f2fa05025efdb262fd4b51103747d3dafd53e1feefc590bf2617b30a",
			256 to "7629794792236644c8c5792257d9a64320b887aae34806f9bc3713c494ea7f17602e0a9a9009b1e69b31d189d89a4f599dd30891fa04a1668ff31e30d0719df4",
			257 to "fa398228112256bf360b7f6693efec0aecd70147173ab48853f8b99f5e30861868b9ce341bde0e47baa31ecb3a9e773d6fa8996b112e108b31e6522bc8de8ab4",
			1000 to "b41d42e106eca6bf57123566b7ed1550c37d33af23afbfa8e302dfd44c988b3003a26b4140ef42f535e66ea424e7c4a31616307269e6d520a6508f726da23d0a",
		)
		for ((length, digest) in expected) {
			assertEquals(digest, hex(Sha512.digest(pattern(length))), "input of $length bytes")
		}
	}

	@Test
	fun aDigestHasItsDeclaredSize() {
		assertEquals(Sha256.SIZE, Sha256.digest(pattern(10)).size)
		assertEquals(Sha512.SIZE, Sha512.digest(pattern(10)).size)
	}
}
