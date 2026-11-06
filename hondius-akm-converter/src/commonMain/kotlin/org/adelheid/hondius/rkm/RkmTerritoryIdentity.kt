package org.adelheid.hondius.rkm

/**
 * Identifier of a territory within an RKM map.
 * Wraps a non-blank string (typically a slug-like name, e.g. `"alaska"`).
 */
data class RkmTerritoryIdentity(val rawValue: String) {
	init {
		require(rawValue.isNotBlank()) { "Territory identity rawValue must not be blank" }
	}

	override fun toString(): String = rawValue
}
