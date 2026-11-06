package org.adelheid.hondius.rkm

/**
 * Chunk tag identifiers for the legacy `.rkm` (Risk Map) binary format.
 *
 * Each tag is exactly 4 ASCII bytes. Unknown tags are safe to skip (forward compatibility).
 * Field layout in the file: `[4-byte tag][4-byte big-endian length][data]`.
 */
enum class RkmField(val tag: ByteArray) {
	UNKNOWN("UNKN".encodeToByteArray()),

	/** Map code name (lowercase, hyphen-allowed identifier). Tag: `MCNM`. */
	MAP_CODE_NAME("MCNM".encodeToByteArray()),

	/** Display name (human-readable). Tag: `MDNM`. */
	MAP_DISPLAY_NAME("MDNM".encodeToByteArray()),

	/** Author name (free-form string). Tag: `MATN`. */
	MAP_AUTHOR_NAME("MATN".encodeToByteArray()),

	/** Vertices: territories with one or more nucleus points. Tag: `VTCS`. */
	VERTICES("VTCS".encodeToByteArray()),

	/** Edges: undirected adjacency between vertex IDs. Tag: `EDGS`. */
	EDGES("EDGS".encodeToByteArray()),

	/** Base map image, PNG-encoded. Tag: `MIBS`. */
	MAP_IMAGE_BASE("MIBS".encodeToByteArray()),

	/** Text/label overlay image, PNG-encoded. Tag: `MITX`. */
	MAP_IMAGE_TEXT("MITX".encodeToByteArray()),

	/** SHA-512 checksum over all preceding bytes. Also acts as EOF marker. Tag: `CKSM`. */
	CHECKSUM("CKSM".encodeToByteArray()),
	;

	companion object {
		fun of(tagBytes: ByteArray): RkmField =
			entries.find { it.tag.contentEquals(tagBytes) } ?: UNKNOWN
	}
}
