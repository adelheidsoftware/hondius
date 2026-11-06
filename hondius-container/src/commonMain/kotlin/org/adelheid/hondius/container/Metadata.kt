package org.adelheid.hondius.container

import kotlin.jvm.JvmStatic

/** Someone a file credits. */
sealed interface Author {
	/** A credit by name, as plain text. */
	data class Credit(val name: String) : Author

	/**
	 * An account: an opaque [id] issued by [authority], a domain its issuer controls. It carries no name; whoever
	 * shows the author asks the issuer what the id is called now.
	 *
	 * @property id 16 bytes in their 8-4-4-4-12 lowercase text form
	 */
	data class Account(val authority: String, val id: String) : Author
}

/** A `META` field this version does not define, kept so that it survives a rewrite. */
class MetadataField(val id: Int, val value: ByteArray) {
	override fun equals(other: Any?): Boolean = other is MetadataField && id == other.id && value.contentEquals(other.value)
	override fun hashCode(): Int = 31 * id + value.contentHashCode()
	override fun toString(): String = "MetadataField($id, ${value.size} bytes)"
}

/**
 * What a file says about itself. Everything is optional, and none of it is needed to use the content.
 *
 * @property name what a person calls the map or palette
 * @property generator the tool that wrote the file, as `name/version`
 * @property authors who is credited, in order
 * @property otherFields fields of a later minor version, carried along
 */
data class Metadata(
	val name: String? = null,
	val generator: String? = null,
	val authors: List<Author> = emptyList(),
	val otherFields: List<MetadataField> = emptyList(),
) {
	init {
		name?.let { Text.check(it, "The name", MAX_TEXT_BYTES, 1) }
		generator?.let { Text.check(it, "The generator", MAX_TEXT_BYTES, 1) }
		if (authors.size > MAX_AUTHORS) throw FormatException(FormatError.LIMIT_EXCEEDED, "${authors.size} authors, above the limit of $MAX_AUTHORS")
		if (authors.toSet().size != authors.size) throw FormatException(FormatError.DUPLICATE, "An author is listed twice")
		for (author in authors) {
			when (author) {
				is Author.Credit -> Text.check(author.name, "A credit", MAX_TEXT_BYTES, 1)
				is Author.Account -> {
					checkAuthority(author.authority)
					if (!Uuids.isValid(author.id)) throw FormatException(FormatError.BAD_VALUE, "An account id is not in 8-4-4-4-12 lowercase form: ${author.id}")
				}
			}
		}
		if (authorities().size > MAX_AUTHORITIES) {
			throw FormatException(FormatError.LIMIT_EXCEEDED, "${authorities().size} authorities, above the limit of $MAX_AUTHORITIES")
		}
		var previous = LAST_DEFINED_FIELD
		for (field in otherFields) {
			if (field.id <= previous) throw FormatException(FormatError.NOT_CANONICAL, "Carried metadata fields must have ascending ids above $LAST_DEFINED_FIELD")
			previous = field.id
		}
	}

	/** The distinct authorities of the account authors, in the order the author list first uses them. */
	fun authorities(): List<String> = authors.filterIsInstance<Author.Account>().map { it.authority }.distinct()

	companion object {
		const val MAX_TEXT_BYTES: Int = 255
		const val MAX_AUTHORS: Int = 64
		const val MAX_AUTHORITIES: Int = 16
		const val MAX_AUTHORITY_BYTES: Int = 253

		internal const val FIELD_NAME = 1
		internal const val FIELD_GENERATOR = 2
		internal const val FIELD_AUTHORS = 3
		internal const val LAST_DEFINED_FIELD = FIELD_AUTHORS

		internal fun checkAuthority(authority: String) {
			val valid = authority.length in 1..MAX_AUTHORITY_BYTES &&
				authority.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' }
			if (!valid) {
				throw FormatException(FormatError.BAD_VALUE, "An authority is 1 to $MAX_AUTHORITY_BYTES lowercase letters, digits, hyphens and dots: $authority")
			}
		}
	}
}

/** Reads and writes the payload of the `META` chunk. */
object MetadataCodec {
	private const val KIND_CREDIT = 0
	private const val KIND_ACCOUNT = 1

	@JvmStatic
	fun encode(metadata: Metadata): ByteArray {
		val out = BinaryWriter()
		metadata.name?.let { writeField(out, Metadata.FIELD_NAME, it.encodeToByteArray()) }
		metadata.generator?.let { writeField(out, Metadata.FIELD_GENERATOR, it.encodeToByteArray()) }
		if (metadata.authors.isNotEmpty()) writeField(out, Metadata.FIELD_AUTHORS, encodeAuthors(metadata))
		for (field in metadata.otherFields) writeField(out, field.id, field.value)
		return out.toByteArray()
	}

	@JvmStatic
	fun decode(payload: ByteArray): Metadata {
		val reader = BinaryReader(payload)
		var name: String? = null
		var generator: String? = null
		var authors: List<Author> = emptyList()
		val others = ArrayList<MetadataField>()
		var previous = 0L
		while (reader.remaining > 0) {
			val id = reader.readUvarint()
			if (id <= previous) {
				val error = if (id == previous) FormatError.DUPLICATE else FormatError.NOT_CANONICAL
				throw FormatException(error, "Metadata field $id does not follow field $previous in ascending order")
			}
			previous = id
			val length = reader.readUvarint()
			if (length > reader.remaining) throw FormatException(FormatError.TRUNCATED, "Metadata field $id declares more bytes than the chunk holds")
			val value = reader.readExact(length.toInt())
			when (id) {
				Metadata.FIELD_NAME.toLong() -> name = decodeText(value, "The name")
				Metadata.FIELD_GENERATOR.toLong() -> generator = decodeText(value, "The generator")
				Metadata.FIELD_AUTHORS.toLong() -> authors = decodeAuthors(value)
				else -> {
					if (id > Int.MAX_VALUE) throw FormatException(FormatError.LIMIT_EXCEEDED, "Metadata field id $id is too large")
					others.add(MetadataField(id.toInt(), value))
				}
			}
		}
		return Metadata(name, generator, authors, others)
	}

	private fun writeField(out: BinaryWriter, id: Int, value: ByteArray) {
		out.writeUvarint(id)
		out.writeUvarint(value.size)
		out.writeBytes(value)
	}

	private fun decodeText(value: ByteArray, what: String): String {
		if (value.isEmpty() || value.size > Metadata.MAX_TEXT_BYTES) {
			throw FormatException(FormatError.BAD_TEXT, "$what is ${value.size} bytes long, outside 1..${Metadata.MAX_TEXT_BYTES}")
		}
		return Text.decode(value, what)
	}

	private fun encodeAuthors(metadata: Metadata): ByteArray {
		val out = BinaryWriter()
		val authorities = metadata.authorities()
		out.writeUvarint(authorities.size)
		for (authority in authorities) out.writeString(authority)
		out.writeUvarint(metadata.authors.size)
		for (author in metadata.authors) {
			when (author) {
				is Author.Credit -> {
					out.writeByte(KIND_CREDIT)
					out.writeString(author.name)
				}
				is Author.Account -> {
					out.writeByte(KIND_ACCOUNT)
					out.writeUvarint(authorities.indexOf(author.authority))
					out.writeUuid(author.id)
				}
			}
		}
		return out.toByteArray()
	}

	private fun decodeAuthors(value: ByteArray): List<Author> {
		val reader = BinaryReader(value)
		val authorityCount = reader.readCount("The number of authorities", Metadata.MAX_AUTHORITIES)
		val authorities = ArrayList<String>(authorityCount)
		repeat(authorityCount) {
			val authority = reader.readString("An authority", Metadata.MAX_AUTHORITY_BYTES, 1)
			Metadata.checkAuthority(authority)
			if (authority in authorities) throw FormatException(FormatError.DUPLICATE, "Authority $authority is listed twice")
			authorities.add(authority)
		}
		val authorCount = reader.readCount("The number of authors", Metadata.MAX_AUTHORS, 1)
		val authors = ArrayList<Author>(authorCount)
		var authoritiesUsed = 0
		repeat(authorCount) {
			when (val kind = reader.readU8()) {
				KIND_CREDIT -> authors.add(Author.Credit(reader.readString("A credit", Metadata.MAX_TEXT_BYTES, 1)))
				KIND_ACCOUNT -> {
					val index = reader.readUvarint()
					if (index >= authorities.size) throw FormatException(FormatError.BAD_REFERENCE, "An author names authority $index of ${authorities.size}")
					// Authorities are listed in the order the authors first use them
					if (index > authoritiesUsed) throw FormatException(FormatError.NOT_CANONICAL, "Authorities are not listed in the order the authors use them")
					if (index.toInt() == authoritiesUsed) authoritiesUsed++
					authors.add(Author.Account(authorities[index.toInt()], reader.readUuid()))
				}
				else -> throw FormatException(FormatError.BAD_VALUE, "Unknown author kind: $kind")
			}
		}
		if (authoritiesUsed != authorities.size) throw FormatException(FormatError.NOT_CANONICAL, "An authority is listed that no author uses")
		reader.expectEnd("The author list")
		if (authors.toSet().size != authors.size) throw FormatException(FormatError.DUPLICATE, "An author is listed twice")
		return authors
	}
}
