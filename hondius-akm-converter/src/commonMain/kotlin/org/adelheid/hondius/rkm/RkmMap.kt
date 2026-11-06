package org.adelheid.hondius.rkm

/**
 * In-memory representation of a parsed `.rkm` (legacy raster map) file.
 *
 * PNG image data is kept as raw bytes; decoding into pixel buffers is the conversion-pipeline's
 * responsibility (it uses platform-specific PNG codecs via `expect`/`actual`).
 *
 * @property codename machine identifier, lowercase alphanumeric + hyphen (regex [CODENAME_REGEX])
 * @property displayName human-readable map name (non-blank)
 * @property author author display name (non-blank)
 * @property territories vertex set (each with one or more nuclei)
 * @property borders adjacency set (undirected)
 * @property basePng PNG bytes of the base map layer (must be non-empty)
 * @property textPng PNG bytes of the text/label overlay (must be non-empty)
 */
class RkmMap(
	val codename: String,
	val displayName: String,
	val author: String,
	val territories: Set<RkmTerritory>,
	val borders: Set<RkmBorder>,
	val basePng: ByteArray,
	val textPng: ByteArray,
) {
	init {
		require(codename.isNotBlank()) { "codename must not be blank" }
		require(CODENAME_REGEX.matches(codename)) {
			"codename must match regex '${CODENAME_REGEX.pattern}', got '$codename'"
		}
		require(displayName.isNotBlank()) { "displayName must not be blank" }
		require(author.isNotBlank()) { "author must not be blank" }
		require(basePng.isNotEmpty()) { "basePng must not be empty" }
		require(textPng.isNotEmpty()) { "textPng must not be empty" }
	}

	fun get(identity: RkmTerritoryIdentity): RkmTerritory? =
		territories.firstOrNull { it.identity == identity }

	operator fun contains(identity: RkmTerritoryIdentity): Boolean =
		territories.any { it.identity == identity }

	companion object {
		/** Regex enforced on [codename]: lowercase, digits and hyphens, cannot start or end with `-`. */
		val CODENAME_REGEX = Regex("^(?!-)[a-z\\d-]+[^-]$")
	}
}
