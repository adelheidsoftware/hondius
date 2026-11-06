package org.adelheid.hondius.akm

import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException

/**
 * One connected piece of land: an outline and the holes in it. A territory with exclaves has several regions.
 *
 * @property rings the outline first, then the holes
 */
data class AkmRegion(val rings: List<AkmRing>) {
	init {
		if (rings.isEmpty()) throw FormatException(FormatError.BAD_GEOMETRY, "A region has at least its outline")
	}

	val outline: AkmRing get() = rings.first()

	val holes: List<AkmRing> get() = rings.drop(1)
}
