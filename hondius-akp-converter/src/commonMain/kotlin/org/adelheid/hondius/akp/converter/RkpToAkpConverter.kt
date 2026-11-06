package org.adelheid.hondius.akp.converter

import org.adelheid.hondius.akp.AkpColor
import org.adelheid.hondius.akp.AkpPalette
import org.adelheid.hondius.container.Metadata
import org.adelheid.hondius.rkp.RkpColor
import org.adelheid.hondius.rkp.RkpPalette
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * Converts a legacy [RkpPalette] into an [AkpPalette].
 *
 * The mapping is mechanical and loses nothing:
 *
 * - The six RKP role colors become the role colors named after the part of a map they color (`textColor` becomes
 *   `labels`, `borderColor` becomes `borders`, and so on). The names RKP gave them, such as `"Text"`, are dropped.
 * - RKP `sortedColorSet`, sorted by `order`, becomes the player colors: position in the list is the order.
 * - Every color is opaque, since RKP has no alpha.
 */
object RkpToAkpConverter {
	/** The tool stamp a converted palette carries. */
	const val GENERATOR: String = "hondius-akp-converter/1.0"

	/**
	 * @param rkp the legacy JSON palette
	 * @param metadata what the palette says about itself; by default the RKP name and this converter's stamp
	 */
	@JvmStatic
	@JvmOverloads
	fun convert(rkp: RkpPalette, metadata: Metadata = Metadata(name = rkp.name, generator = GENERATOR)): AkpPalette {
		val roles = listOf(
			roleColor(AkpPalette.WATER, rkp.waterColor),
			roleColor(AkpPalette.LAND, rkp.landColor),
			roleColor(AkpPalette.CONNECTIONS, rkp.connectionColor),
			roleColor(AkpPalette.TERRITORIES, rkp.territoryColor),
			roleColor(AkpPalette.BORDERS, rkp.borderColor),
			roleColor(AkpPalette.LABELS, rkp.textColor),
		)
		val players = rkp.sortedColorSet
			.sortedBy { it.order }
			.map { AkpColor(name = it.name, r = it.r, g = it.g, b = it.b, a = 0xFF) }
		return AkpPalette(roles, players, metadata)
	}

	private fun roleColor(role: String, source: RkpColor): AkpColor =
		AkpColor(name = role, r = source.r, g = source.g, b = source.b, a = 0xFF)
}
