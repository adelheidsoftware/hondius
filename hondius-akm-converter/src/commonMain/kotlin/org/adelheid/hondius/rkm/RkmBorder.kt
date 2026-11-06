package org.adelheid.hondius.rkm

/**
 * Undirected adjacency between two RKM territories.
 *
 * Equality is order-independent: `RkmBorder(a, b) == RkmBorder(b, a)`.
 */
class RkmBorder(val source: RkmTerritoryIdentity, val target: RkmTerritoryIdentity) {
	init {
		require(source != target) { "source and target territories must differ" }
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is RkmBorder) return false
		return (source == other.source && target == other.target) ||
			(source == other.target && target == other.source)
	}

	override fun hashCode(): Int = source.hashCode() + target.hashCode()

	override fun toString(): String = "RkmBorder($source <-> $target)"
}
