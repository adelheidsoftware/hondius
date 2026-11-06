package org.adelheid.hondius.akp.v1

import org.adelheid.hondius.akp.AkpColor

/** An author record of a version 1 file: an id, and the display name at the time the file was written. */
data class AkpV1Author(val authorId: String, val name: String)

/**
 * A version 1 `.akp` file as it was written. [AkpV1Converter] turns it into the current format; a host that migrates
 * stored files reads here what the current format no longer holds: the id, the description, the authors and the
 * creation time.
 */
data class AkpV1Palette(
	val paletteId: String,
	val displayName: String,
	val description: String,
	val authors: List<AkpV1Author>,
	val createdAtEpochSeconds: Long,
	val editorVersion: String,
	val roleColors: List<AkpColor>,
	val playerColors: List<AkpColor>,
)
