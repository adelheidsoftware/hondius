package org.adelheid.hondius.akp.v1

import org.adelheid.hondius.akp.AkpPalette
import org.adelheid.hondius.container.Metadata
import kotlin.jvm.JvmStatic

/**
 * Converts a version 1 palette to the current format.
 *
 * The colors carry over unchanged. Of the version 1 header, the name and the tool stamp become metadata. The id, the
 * description and the creation time have no place in the current format, and the authors are left out: a version 1
 * author is an id with no issuer and a name frozen at export, and who a file credits is for its host to say. All of
 * them remain readable from the [AkpV1Palette].
 */
object AkpV1Converter {

	@JvmStatic
	fun convert(v1: AkpV1Palette): AkpPalette =
		AkpPalette(
			roleColors = v1.roleColors,
			playerColors = v1.playerColors,
			metadata = Metadata(
				name = v1.displayName.takeIf { it.isNotBlank() },
				generator = v1.editorVersion.takeIf { it.isNotBlank() },
			),
		)

	/** Reads version 1 bytes and converts them. */
	@JvmStatic
	fun convert(v1Bytes: ByteArray): AkpPalette = convert(AkpV1Decoder.decode(v1Bytes))
}
