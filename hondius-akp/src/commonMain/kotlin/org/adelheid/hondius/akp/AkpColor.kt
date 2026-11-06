package org.adelheid.hondius.akp

import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException

/**
 * A named color: a role color, named by its role, or a player color, named for people. sRGB, alpha not
 * premultiplied; `a = 0xFF` is opaque.
 */
data class AkpColor(
	val name: String,
	val r: Int,
	val g: Int,
	val b: Int,
	val a: Int = 0xFF,
) {
	init {
		if (r !in 0..255 || g !in 0..255 || b !in 0..255 || a !in 0..255) {
			throw FormatException(FormatError.BAD_VALUE, "Color '$name' has a channel outside 0..255: $r, $g, $b, $a")
		}
	}
}
