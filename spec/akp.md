# AKP Palette Format Specification

**Version:** 2.0
**Extension:** `.akp`
**MIME type:** `application/x-adelkrieg-palette`
**Signature:** `0x83 0x41 0x4B 0x50 0x0D 0x0A 0x1A 0x0A` (`\x83AKP\r\n\x1a\n`)

An `.akp` file is a palette: the colors players claim territories with, and the colors a reader draws a map's parts
in. It differs from the `.akm` signature in its third letter only.

The outer structure, the primitive types, the `META` chunk and the checksum are defined in
[container.md](container.md). This document defines the palette's own chunks. Version 1 is in [v1/akp.md](v1/akp.md).

## File structure

| Order | Chunk | Flags | Occurs | Holds |
| --- | --- | --- | --- | --- |
| 1 | `META` | `REQUIRED` | once | Name, generator, authors (see the container) |
| 2 | `AKMC` | `REQUIRED` | once | Role colors: what the parts of a map are drawn in |
| 3 | `PLYR` | `REQUIRED` | once | Player colors, in order |
| last | `CKSM` | `REQUIRED` | once | Checksum (see the container) |

Every color is an `rgba` value: sRGB, alpha not premultiplied.

## Chunk: AKMC

| Field | Type | Description |
| --- | --- | --- |
| Entry count | `uvarint` | 6 to 255 |
| Entries | entry records | Sorted by name, byte by byte. No name twice. |

### Entry

| Field | Type | Description |
| --- | --- | --- |
| Name | `string` | 1 to 64 bytes of lowercase ASCII letters, digits and hyphens |
| Color | `rgba` | |

Six entries are required. A reader MUST reject a file that lacks one of them:

| Name | What a reader draws in it |
| --- | --- |
| `borders` | The borders between territories |
| `connections` | Connection lines across water |
| `labels` | Territory names |
| `land` | Land that belongs to no territory |
| `territories` | A territory nobody holds |
| `water` | Water |

A reader ignores an entry whose name it does not know, and a tool that rewrites a palette keeps it. A later minor
version may give more names a meaning.

## Chunk: PLYR

The colors players choose from. Position is meaning: the first color is the first player's default, the second the
second player's, and so on.

| Field | Type | Description |
| --- | --- | --- |
| Color count | `uvarint` | 1 to 1,024 |
| Colors | color records | In order |

### Color

| Field | Type | Description |
| --- | --- | --- |
| Name | `string` | 1 to 255 bytes. What a person calls the color, such as `Salmon`. |
| Color | `rgba` | |

How many colors a palette needs to be usable is for the host that accepts it to say.

## Version history

| Version | Changes |
| --- | --- |
| 2.0 | Shared container with optional metadata and an author list. Role colors in a fixed order. No file id, description or timestamp. The bounds on the number of player colors become host policy. |
| 1.0 | Initial specification |
