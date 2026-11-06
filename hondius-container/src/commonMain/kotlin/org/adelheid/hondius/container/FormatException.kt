package org.adelheid.hondius.container

/** Why data is not a valid document. One value per kind of fault, so a host can tell an uploader what is wrong. */
enum class FormatError {
	/** The data ends before the structure it declares does. */
	TRUNCATED,

	/** The first eight bytes are not the format's signature. */
	BAD_SIGNATURE,

	/** A major version this reader does not implement. */
	UNSUPPORTED_VERSION,

	/** A header or chunk field holds something the specification does not allow, or bytes are left over. */
	BAD_STRUCTURE,

	/** A required chunk whose tag or encoding this reader does not know. */
	UNKNOWN_REQUIRED_CHUNK,

	/** A required chunk is missing, a chunk occurs too often, or chunks are out of order. */
	BAD_CHUNK_ORDER,

	/** The digest in the file does not match its content. */
	BAD_CHECKSUM,

	/** Valid content, written in a form other than the one the specification prescribes. */
	NOT_CANONICAL,

	/** Text that is not valid UTF-8, holds a control character, or has a length outside its bounds. */
	BAD_TEXT,

	/** A count or a size beyond a structural limit. */
	LIMIT_EXCEEDED,

	/** A field holds a value outside its range. */
	BAD_VALUE,

	/** An index that points at nothing. */
	BAD_REFERENCE,

	/** Something that must be unique occurs twice. */
	DUPLICATE,

	/** An outline or a line that is not valid on the canvas. */
	BAD_GEOMETRY,

	/** An image that is not of its declared format, or that carries what an image may not contain. */
	BAD_IMAGE,
}

/**
 * Thrown for data that is not a valid document, by the decoders for bytes and by the models for values. [error] says
 * which rule was broken; the message says where.
 */
class FormatException(val error: FormatError, message: String) : RuntimeException(message)
