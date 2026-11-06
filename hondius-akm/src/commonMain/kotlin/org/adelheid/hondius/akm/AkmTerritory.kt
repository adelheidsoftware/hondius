package org.adelheid.hondius.akm

import kotlin.jvm.JvmOverloads

/**
 * A territory: an id, its shape, and what it is called.
 *
 * @property id 16 bytes in their 8-4-4-4-12 lowercase text form, unique within the map. It is what stays the same
 *   when a map is revised and its territories are reordered.
 * @property regions the territory's pieces; more than one means exclaves
 * @property name may be empty
 * @property anchor where a label or a count is placed, or null to leave the placement to the reader
 */
data class AkmTerritory @JvmOverloads constructor(
	val id: String,
	val regions: List<AkmRegion>,
	val name: String = "",
	val anchor: AkmPoint? = null,
)

/**
 * A connection between two territories, named by their positions in the map's territory list.
 *
 * @property bidirectional true when the connection runs both ways; false when it runs from [a] to [b] only
 */
data class AkmConnection @JvmOverloads constructor(val a: Int, val b: Int, val bidirectional: Boolean = true)
