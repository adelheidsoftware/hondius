@file:Suppress("DEPRECATION")

package org.adelheid.hondius.rkp.codec

import kotlinx.serialization.json.Json
import org.adelheid.hondius.rkp.RkpPalette
import org.adelheid.hondius.rkp.legacy.LegacyPalette
import kotlin.jvm.JvmStatic

/**
 * Decodes `.rkp` JSON palette files into [RkpPalette] instances.
 *
 * Tries the modern [RkpPalette] schema first. If that fails, falls back to the older
 * [LegacyPalette] schema and upgrades it to [RkpPalette] with default role colors.
 */
object RkpDecoder {

	private val JSON = Json {
		ignoreUnknownKeys = true
		isLenient = true
	}

	@JvmStatic
	fun decode(data: ByteArray): RkpPalette = decode(data.decodeToString())

	@JvmStatic
	fun decode(json: String): RkpPalette {
		return try {
			JSON.decodeFromString(RkpPalette.serializer(), json)
		} catch (_: Throwable) {
			JSON.decodeFromString(LegacyPalette.serializer(), json).toRkpPalette()
		}
	}
}
