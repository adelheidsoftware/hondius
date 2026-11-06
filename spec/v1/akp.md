# AKP Binary Format Specification

**Version:** 1.0
**Extension:** `.akp`
**MIME type:** `application/x-adelkrieg-palette`
**Byte order:** Big-endian

## File Structure

```
+-----------------------------------------------------+
| FILE HEADER (fixed 16 bytes)                         |
+-----------------------------------------------------+
| CHUNK: META (required, exactly 1)                    |
| CHUNK: AKMC (required, exactly 1)                    |
| CHUNK: PLYR (required, exactly 1)                    |
| CHUNK: CKSM (required, exactly 1, must be last)      |
+-----------------------------------------------------+
```

Each chunk follows a uniform envelope:

| Field   | Size | Description                                            |
|---------|------|--------------------------------------------------------|
| Tag     | 4B   | ASCII identifier (`META`, `AKMC`, `PLYR`, `CKSM`)      |
| Size    | u32  | Byte length of Payload (excludes Tag and Size)         |
| Payload | ...  | Chunk-specific data                                    |

Decoders MUST skip chunks with unrecognized tags (forward compatibility).

## File Header

| Offset | Size | Field         | Value                                                        |
|--------|------|---------------|--------------------------------------------------------------|
| 0      | 8    | Signature     | `0x83 0x41 0x4B 0x50 0x0D 0x0A 0x1A 0x0A` (`\x83AKP\r\n\x1a\n`) |
| 8      | 2    | Version Major | `u16` (currently `1`)                                        |
| 10     | 2    | Version Minor | `u16` (currently `0`)                                        |
| 12     | 4    | Reserved      | `0x00000000`                                                 |

The signature uses mixed binary and text bytes (borrowed from the PNG format) to detect corruption from text-mode transfers. Only the third byte differs from the `.akm` signature (`P` vs `M`).

## Primitive Types

| Notation   | Description                            |
|------------|----------------------------------------|
| `u8`       | Unsigned 8-bit integer                 |
| `u16`      | Unsigned 16-bit integer, big-endian    |
| `u32`      | Unsigned 32-bit integer, big-endian    |
| `i64`      | Signed 64-bit integer, big-endian      |
| `uuid`     | 16 bytes: MSB long (8B) then LSB long (8B) |
| `utf8[n]`  | UTF-8 encoded string, `n` bytes        |
| `string`   | `u16` byte-length prefix + `utf8[n]`   |
| `rgba`     | 4 bytes: `u8 r`, `u8 g`, `u8 b`, `u8 a` |

## Chunk: META

Palette metadata. Mirrors `.akm`'s META chunk minus the canvas dimensions, since palettes are dimensionless.

| Field          | Type     | Description                              |
|----------------|----------|------------------------------------------|
| Palette ID     | `uuid`   | Unique palette identifier                |
| Display Name   | `string` | Human-readable name (must not be empty)  |
| Description    | `string` | Optional description (`0`-length = empty) |
| Author Count   | `u16`    | Number of author records (>= 1)          |
| Authors        | ...      | Author records (see below)               |
| Created At     | `i64`    | Unix timestamp in seconds                |
| Editor Version | `string` | Tool that created the file               |

### Author Record

| Field     | Type     | Description              |
|-----------|----------|--------------------------|
| Author ID | `uuid`   | User identifier          |
| Name      | `string` | Display name at export   |

## Chunk: AKMC

AKM-specific role colors. These are presentation defaults applied by the renderer when drawing an `.akm` map — they are *palette* concerns (a palette author picks them to match the playable colors), but they only have meaning in the context of an `.akm` map being rendered.

Each entry is keyed by a name that matches the corresponding `.akm` layer name from the [AKM spec](akm.md).

| Field        | Type | Description                                  |
|--------------|------|----------------------------------------------|
| Entry Count  | `u16`| Number of entries                            |
| Entries      | ...  | AKMC entries, see below                       |

### AKMC Entry

| Field | Type     | Description                          |
|-------|----------|--------------------------------------|
| Name  | `string` | Entry name (must not be empty)       |
| Color | `rgba`   | Color including alpha                |

### Required entries for v1.0

A conforming v1.0 file MUST contain exactly the following six entries. Names match AKM layer names verbatim. Order within the chunk does not matter — entries are looked up by name.

| Name           | Renderer purpose                                                       |
|----------------|------------------------------------------------------------------------|
| `water`        | Color used for water / ocean                                           |
| `land`         | Color used for non-territory landmass                                  |
| `connections`  | Color used for cross-water connection lines                            |
| `territories`  | Default fill color for an unclaimed territory                          |
| `borders`      | Color used for territory borders                                       |
| `labels`       | Color used for territory name labels                                   |

Decoders MUST reject files missing any required entry. Unknown entry names (beyond the six above) MUST be preserved on round-trip but otherwise ignored, so future versions can add new AKM-specific roles without breaking older readers.

## Chunk: PLYR

The playable player palette: the colors a player can select to claim territories. Stored in canonical order; the position of each color in the chunk IS its order (slot 0 = first player's default, slot 1 = second player's, …).

| Field       | Type | Description                                  |
|-------------|------|----------------------------------------------|
| Color Count | `u16`| Number of colors (`2 <= N <= 256`)           |
| Colors      | ...  | Player color records, in canonical order      |

### Player Color Record

| Field | Type     | Description                                     |
|-------|----------|-------------------------------------------------|
| Name  | `string` | Display name (must not be empty, e.g. `"Salmon"`) |
| Color | `rgba`   | Color including alpha                            |

## Chunk: CKSM

Integrity checksum. Must be the last chunk in the file.

| Field     | Type        | Description                                          |
|-----------|-------------|------------------------------------------------------|
| Algorithm | `u8`        | `0` = SHA-256                                        |
| Hash      | `bytes[32]` | SHA-256 of all preceding bytes (signature through end of last chunk before CKSM) |

Choice of SHA-256 (rather than RKP's SHA-512) intentionally matches AKM so a single hash primitive serves both formats.

## Comparison with RKP

RKP (`.rkp`) is JSON-encoded with a `SortedSet<RkpColor>` whose `order: int` field acts as the sort key. AKP is binary, removes the explicit `order` field (the position in the PLYR chunk is canonical), adds alpha support, and gains a META block with UUID, authors, and timestamp — bringing palettes in line with AKM's community-submission model.

Conversion is straightforward and lossless in the RKP → AKP direction (alpha defaults to `0xFF` since RKP is RGB-only):

| RKP field                                       | AKP destination                                       |
|-------------------------------------------------|-------------------------------------------------------|
| `name`                                          | META `Display Name`                                   |
| `textColor.{r,g,b}` + alpha `0xFF`              | AKMC entry named `labels`                             |
| `borderColor.{r,g,b}` + alpha `0xFF`            | AKMC entry named `borders`                            |
| `territoryColor.{r,g,b}` + alpha `0xFF`         | AKMC entry named `territories`                        |
| `landColor.{r,g,b}` + alpha `0xFF`              | AKMC entry named `land`                               |
| `waterColor.{r,g,b}` + alpha `0xFF`             | AKMC entry named `water`                              |
| `connectionColor.{r,g,b}` + alpha `0xFF`        | AKMC entry named `connections`                        |
| `sortedColorSet` (iteration order)              | PLYR colors, in iteration order; each gets alpha `0xFF` |
| (none)                                          | META `Palette ID` — fresh UUID at conversion          |
| (none)                                          | META `Authors` — caller supplies                      |
| (none)                                          | META `Created At` — conversion time                   |

Role color *names* in RKP (e.g. `"Text"`, `"Border"`) are remapped to AKM-aligned names (`labels`, `borders`, …) — the original RKP role-color names are not preserved.

## Reference Implementation

The `hondius-akp` module of this repository.

## Version History

| Version | Changes               |
|---------|-----------------------|
| 1.0     | Initial specification |
