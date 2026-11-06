package org.adelheid.hondius.rkm

/**
 * A territory in an RKM map: an identity plus one or more nucleus points used as flood-fill seeds.
 */
data class RkmTerritory(
	val identity: RkmTerritoryIdentity,
	val nuclei: Set<RkmNucleus>,
) {
	init {
		require(nuclei.isNotEmpty()) { "Territory must have at least one nucleus" }
	}

	val name: String get() = identity.rawValue
}
