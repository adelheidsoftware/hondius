package org.adelheid.hondius.rkp.codec

import kotlinx.serialization.json.Json
import org.adelheid.hondius.rkp.RkpPalette
import kotlin.jvm.JvmStatic

/**
 * Encodes an [RkpPalette] into the legacy `.rkp` JSON format.
 *
 * Output is pretty-printed with 2-space indentation, the layout existing `.rkp` files have,
 * so that regenerating one changes only the lines whose content changed.
 */
object RkpEncoder {

	private val JSON = Json {
		prettyPrint = true
		prettyPrintIndent = "  "
	}

	/** Encodes a palette to UTF-8 JSON bytes. */
	@JvmStatic
	fun encode(palette: RkpPalette): ByteArray = encodeToString(palette).encodeToByteArray()

	/** Encodes a palette to a pretty-printed JSON string. */
	@JvmStatic
	fun encodeToString(palette: RkpPalette): String =
		JSON.encodeToString(RkpPalette.serializer(), palette)
}
