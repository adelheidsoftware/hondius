# Container Specification

**Version:** 2.0
**Used by:** `.akm` maps ([akm.md](akm.md)) and `.akp` palettes ([akp.md](akp.md))
**Byte order:** big-endian

A file of either format has the same outer structure: a header, a sequence of chunks, and a checksum. This document
defines that structure and the one chunk both formats share, `META`. Each format's own document defines its
signature and its remaining chunks.

The words MUST, MUST NOT, SHOULD and MAY are used as RFC 2119 defines them.

## Primitive types

| Notation | Description |
| --- | --- |
| `u8`, `u16`, `u32` | Unsigned integer of 8, 16 or 32 bits, big-endian |
| `uvarint` | Unsigned integer from 0 to 2³² − 1 in base-128: seven bits per byte, least significant group first, the high bit set on every byte but the last. One to five bytes. It MUST use the fewest bytes that hold the value. |
| `svarint` | Signed 32-bit integer `n`, written as the `uvarint` of `(n << 1) ^ (n >> 31)` |
| `bytes[n]` | `n` bytes, as they are |
| `string` | A `uvarint` byte length followed by that many bytes of UTF-8 |
| `rgba` | Four bytes: red, green, blue, alpha. sRGB, alpha not premultiplied. |

Text is valid UTF-8, with no code point from U+0000 to U+001F and none from U+007F to U+009F. A reader MUST reject
text that breaks either rule.

## File structure

```
header                    16 bytes
META chunk                required, first
the format's own chunks   in the order the format's document lists them
chunks the writer carried without knowing them
CKSM chunk                required, last
```

Nothing follows the `CKSM` chunk. A file is at most 2,147,483,647 bytes long.

## Header

| Offset | Size | Field | Value |
| --- | --- | --- | --- |
| 0 | 8 | Signature | `0x83`, three ASCII letters naming the format, `0x0D 0x0A 0x1A 0x0A` |
| 8 | 2 | Version major | `u16`, `2` |
| 10 | 2 | Version minor | `u16`, `0` |
| 12 | 4 | Reserved | `0x00000000` |

The header has the layout it had in version 1, so a reader of either version recognizes a file of the other and can
say which version it is.

A reader MUST reject a file whose major version it does not implement. It MUST accept a higher minor version than
its own: a minor version only adds chunk types and `META` fields, and the rules below say what a reader does with
those it does not know. It never changes the layout of anything an earlier minor version defined.

## Chunks

| Field | Type | Description |
| --- | --- | --- |
| Tag | `bytes[4]` | Four ASCII letters or digits |
| Flags | `u8` | Bit 0 `REQUIRED`, bit 1 `COPY_SAFE`. Bits 2 to 7 MUST be zero. |
| Encoding | `u8` | How the payload is stored. `0` = as it is. Version 2.0 defines no other value. |
| Length | `u32` | Byte length of the payload as stored |
| Payload | `bytes[Length]` | |

**`REQUIRED`** marks a chunk a reader cannot do without. A reader MUST reject a file that holds a `REQUIRED` chunk
whose tag it does not know, or whose encoding it does not know. A chunk without the mark is optional: a reader that
does not know its tag or its encoding skips it.

**`COPY_SAFE`** applies to optional chunks only, and MUST be zero on required ones. It says that the chunk stays
valid when the rest of the file changes. A tool that rewrites a file and carries a chunk it does not know:

- MUST keep the chunk, byte for byte, when it changed nothing but `META`;
- MUST keep it when the chunk is `COPY_SAFE`;
- MUST drop it otherwise, since the chunk may describe content that is no longer there.

A chunk this specification defines has the flags its definition gives it. A reader MUST reject it with any other.

### Order

1. `META` is the first chunk and `CKSM` the last.
2. The format's chunks follow `META` in the order its document lists them. Chunks of a type that may repeat are
   adjacent.
3. A later minor version lists its new chunk types after the existing ones.
4. A writer puts chunks it carried without knowing them after the ones it knows, before `CKSM`, in the order it
   read them.

A reader MUST reject a file in which the chunks it knows are out of this order, or in which a chunk that may occur
once occurs twice. It does not judge the position of chunks it does not know.

A payload holds exactly what its definition says. A reader MUST reject a known chunk with bytes left over.

## Chunk: META

Flags: `REQUIRED`. Occurs once. Describes the file; none of it is needed to draw or use the content.

The payload is a sequence of fields:

| Field | Type | Description |
| --- | --- | --- |
| Id | `uvarint` | Which field this is |
| Length | `uvarint` | Byte length of the value |
| Value | `bytes[Length]` | |

Fields are in ascending order of id, and an id occurs at most once. Every field is optional: an empty payload is a
valid `META`. A tool that rewrites a file keeps the fields whose id it does not know.

| Id | Name | Value |
| --- | --- | --- |
| 1 | Name | UTF-8 text, 1 to 255 bytes. What a person calls this map or palette. |
| 2 | Generator | UTF-8 text, 1 to 255 bytes. The tool that wrote the file, as `name/version`. |
| 3 | Authors | The author list, below |

A file carries no identifier of its own, no description and no timestamp. A host that stores files keeps those.

### Authors

| Field | Type | Description |
| --- | --- | --- |
| Authority count | `uvarint` | 0 to 16 |
| Authorities | `string` each | |
| Author count | `uvarint` | 1 to 64 |
| Authors | author entries | In the order they are credited |

An **authority** is a domain name its issuer controls, such as `adelkrieg.com`: 1 to 253 bytes of lowercase ASCII
letters, digits, hyphens and dots. It says who issued an account id, and nothing else; it is not an address to
fetch. Authorities are listed in the order the author entries first use them, each once, and each is used.

An **author entry** begins with a `u8` kind:

| Kind | Rest of the entry | Meaning |
| --- | --- | --- |
| 0 | `string`, 1 to 255 bytes | A credit: a name as plain text |
| 1 | `uvarint` authority index, `bytes[16]` id | An account: an opaque id that the authority issued |

An account entry holds no name. Whoever shows the author asks the authority's issuer what the id is called now, and
shows the author as unknown when the id no longer resolves. An entry occurs at most once.

When the field is absent, the file names no author.

## Chunk: CKSM

Flags: `REQUIRED`. Encoding `0`. Occurs once, last.

| Field | Type | Description |
| --- | --- | --- |
| Algorithm | `u8` | `0` = SHA-256 |
| Digest | `bytes[32]` | SHA-256 of every byte of the file before this chunk's tag |

A reader MUST reject a file whose digest does not match.

## One encoding

A document has exactly one valid encoding. The rules above and in the format documents leave a writer no choice: the
shortest integer form, fields and chunks in a fixed order, lists in a stated order, nothing optional written in its
default form, no padding. Two files with the same content are therefore the same bytes, and a reader MUST reject a
file that departs from any of these rules, even where it could make sense of it.

## Limits

The limits in these documents are structural: they keep a reader from being asked for unreasonable work, and every
reader enforces them. A host that accepts files sets its own, stricter policy on top (how many territories a map
needs, how many colors a palette may have, how large an upload may be) and publishes it itself.
