package org.adelheid.hondius.akp

import org.adelheid.hondius.container.Chunk
import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException
import org.adelheid.hondius.container.Metadata
import org.adelheid.hondius.container.Text
import kotlin.jvm.JvmOverloads

/**
 * A palette, as an `.akp` file holds it.
 *
 * Building one checks every rule of the format, so a palette that exists can be written, and a file that was read
 * is a valid palette.
 *
 * @param roleColors what the parts of a map are drawn in, each named by its role; the six in [REQUIRED_ROLES] must
 *   be among them. Given in any order.
 * @property playerColors the colors players choose from. Position is meaning: the first is the first player's default.
 * @property metadata what the file says about itself
 * @property carriedChunks chunks of a later minor version that a reader did not know, kept so a rewrite preserves them
 */
class AkpPalette @JvmOverloads constructor(
	roleColors: List<AkpColor>,
	val playerColors: List<AkpColor>,
	val metadata: Metadata = Metadata(),
	val carriedChunks: List<Chunk> = emptyList(),
) {
	/** The role colors, sorted by name: the order the file holds them in. */
	val roleColors: List<AkpColor> = roleColors.sortedBy { it.name }

	init {
		if (roleColors.size > MAX_ROLE_COLORS) {
			throw FormatException(FormatError.LIMIT_EXCEEDED, "${roleColors.size} role colors, above the limit of $MAX_ROLE_COLORS")
		}
		val names = HashSet<String>()
		for (color in roleColors) {
			val valid = color.name.length in 1..MAX_ROLE_NAME_BYTES && color.name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
			if (!valid) {
				throw FormatException(FormatError.BAD_VALUE, "A role name is 1 to $MAX_ROLE_NAME_BYTES lowercase letters, digits and hyphens: '${color.name}'")
			}
			if (!names.add(color.name)) throw FormatException(FormatError.DUPLICATE, "Role color '${color.name}' occurs twice")
		}
		for (role in REQUIRED_ROLES) {
			if (role !in names) throw FormatException(FormatError.BAD_VALUE, "Required role color '$role' is missing")
		}
		if (playerColors.isEmpty()) throw FormatException(FormatError.BAD_VALUE, "A palette has at least one player color")
		if (playerColors.size > MAX_PLAYER_COLORS) {
			throw FormatException(FormatError.LIMIT_EXCEEDED, "${playerColors.size} player colors, above the limit of $MAX_PLAYER_COLORS")
		}
		for (color in playerColors) Text.check(color.name, "A player color's name", MAX_PLAYER_NAME_BYTES, 1)
	}

	/** The color of [role], or null when the palette does not define it. */
	fun role(role: String): AkpColor? = roleColors.firstOrNull { it.name == role }

	val water: AkpColor get() = role(WATER)!!
	val land: AkpColor get() = role(LAND)!!
	val connections: AkpColor get() = role(CONNECTIONS)!!
	val territories: AkpColor get() = role(TERRITORIES)!!
	val borders: AkpColor get() = role(BORDERS)!!
	val labels: AkpColor get() = role(LABELS)!!

	override fun equals(other: Any?): Boolean =
		other is AkpPalette && roleColors == other.roleColors && playerColors == other.playerColors &&
			metadata == other.metadata && carriedChunks == other.carriedChunks

	override fun hashCode(): Int = 31 * (31 * (31 * roleColors.hashCode() + playerColors.hashCode()) + metadata.hashCode()) + carriedChunks.hashCode()

	companion object {
		const val WATER: String = "water"
		const val LAND: String = "land"
		const val CONNECTIONS: String = "connections"
		const val TERRITORIES: String = "territories"
		const val BORDERS: String = "borders"
		const val LABELS: String = "labels"

		/** The roles every palette defines. */
		val REQUIRED_ROLES: Set<String> = setOf(WATER, LAND, CONNECTIONS, TERRITORIES, BORDERS, LABELS)

		const val MAX_ROLE_COLORS: Int = 255
		const val MAX_ROLE_NAME_BYTES: Int = 64
		const val MAX_PLAYER_COLORS: Int = 1024
		const val MAX_PLAYER_NAME_BYTES: Int = 255
	}
}
