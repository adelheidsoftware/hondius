# AKM Map Format Specification

**Version:** 2.0
**Extension:** `.akm`
**MIME type:** `application/x-adelkrieg-map`
**Signature:** `0x83 0x41 0x4B 0x4D 0x0D 0x0A 0x1A 0x0A` (`\x83AKM\r\n\x1a\n`)

An `.akm` file is a map for a territory game: the territories as vector outlines, the connections between them, and
optional artwork. It holds what is needed to draw and play the map. What a catalog knows about a map (a description,
suggested player counts, ratings, who may use it) is the catalog's to keep.

The outer structure, the primitive types, the `META` chunk and the checksum are defined in
[container.md](container.md). This document defines the map's own chunks. Version 1 is in [v1/akm.md](v1/akm.md).

## File structure

| Order | Chunk | Flags | Occurs | Holds |
| --- | --- | --- | --- | --- |
| 1 | `META` | `REQUIRED` | once | Name, generator, authors (see the container) |
| 2 | `GEOM` | `REQUIRED` | once | Canvas, coordinate grid, territories |
| 3 | `ADJC` | `REQUIRED` | once | Connections between territories |
| 4 | `SHAP` | optional | at most once | Land and connection lines as vector shapes |
| 5 | `LAYR` | optional | 0 to 255 times | One raster layer each, in ascending z-order |
| last | `CKSM` | `REQUIRED` | once | Checksum (see the container) |

A map can be drawn and played from `GEOM` and `ADJC` alone.

## Coordinates

The canvas is a rectangle of whole pixels with its origin at the top left, x growing to the right and y downward.

Every coordinate in `GEOM` and `SHAP` is a whole number of **grid units**. `GEOM` declares the grid: a unit is
1/2ᵍ of a pixel, with `g` from 0 to 8. The default is `g = 4`, a sixteenth of a pixel. A coordinate lies on the canvas:
`0 ≤ x ≤ width · 2ᵍ` and `0 ≤ y ≤ height · 2ᵍ`.

A **point list** of `n` points is written as the first point's x and y as `uvarint`s, then for each further point the
step from the point before it, as `svarint` x then `svarint` y. No step is zero in both directions.

Because coordinates are whole numbers, two outlines that share a vertex hold exactly the same value for it.

## Chunk: GEOM

| Field | Type | Description |
| --- | --- | --- |
| Canvas width | `uvarint` | Pixels, 1 to 16,384 |
| Canvas height | `uvarint` | Pixels, 1 to 16,384. Width times height is at most 40,000,000. |
| Grid | `u8` | `g`, 0 to 8 |
| Territory count | `uvarint` | 1 to 65,535 |
| Territories | territory records | |

### Territory

| Field | Type | Description |
| --- | --- | --- |
| Id | `bytes[16]` | A UUID. Unique within the file. |
| Name | `string` | 0 to 255 bytes. May be empty. |
| Flags | `u8` | Bit 0 `HAS_ANCHOR`. Bits 1 to 7 MUST be zero. |
| Anchor | `uvarint` x, `uvarint` y | Present only with `HAS_ANCHOR`. Where a label or a count is placed. |
| Region count | `uvarint` | 1 to 1,024 |
| Regions | region records | |

A territory's position in the list is its **index**, from 0. `ADJC` refers to territories by index. The id is what
stays the same when a map is revised and its territories are reordered.

A territory with no anchor leaves the placement to the reader, which computes a point inside the territory.

### Region

One connected piece of a territory. A territory with exclaves has several.

| Field | Type | Description |
| --- | --- | --- |
| Ring count | `uvarint` | 1 to 65,535 |
| Rings | ring records | The first is the outline, the others are holes in it |

### Ring

A closed polygon. Its last point joins its first, which is not repeated.

| Field | Type | Description |
| --- | --- | --- |
| Point count | `uvarint` | 3 to 1,000,000 |
| Points | point list | |

The signed area of a ring is half the sum, over its points in order, of `xᵢ · yᵢ₊₁ − xᵢ₊₁ · yᵢ`. An outline has a
positive area and a hole a negative one; a reader MUST reject a ring whose area is zero or has the wrong sign. With y
growing downward, an outline therefore runs clockwise on screen.

A ring MUST NOT cross or touch itself, a hole lies inside its outline, and the regions of one map do not overlap.
A reader is not required to verify these three; a tool that checks maps should.

Where two territories share a border, both outlines SHOULD hold the same vertices along it. A map written that way
has no gaps or overlaps between neighbors, and a reader can find each shared border by comparing vertices.

A file holds at most 16,777,216 points across all its rings.

## Chunk: ADJC

Which territories are connected. The list is complete: territories that touch are connected only if they are listed.

| Field | Type | Description |
| --- | --- | --- |
| Connection count | `uvarint` | 0 to 1,048,576 |
| Connections | connection records | Sorted by A, then by B |

### Connection

| Field | Type | Description |
| --- | --- | --- |
| A | `uvarint` | Territory index |
| B | `uvarint` | Territory index, different from A |
| Flags | `u8` | Bit 0 `BIDIRECTIONAL`. Bits 1 to 7 MUST be zero. |

With `BIDIRECTIONAL` set, the connection runs both ways and is written with A less than B. Without it, the
connection runs from A to B only.

Two territories have at most one connection between them. Both directions are one `BIDIRECTIONAL` connection, never
two one-way ones.

A connection's position in the list is its index, from 0.

## Chunk: SHAP

Flags: none (optional, not `COPY_SAFE`). Shapes that are part of the picture and not of the game.

| Field | Type | Description |
| --- | --- | --- |
| Land count | `uvarint` | 0 to 65,535 |
| Land | region records | Land that belongs to no territory, drawn in the palette's `land` color |
| Line count | `uvarint` | 0 to 65,535 |
| Lines | line records | Drawn in the palette's `connections` color |

A **line** is a `uvarint` point count from 2 to 1,000,000 followed by a point list. It is open: its ends are not
joined. Lines typically show a connection across water.

The chunk is absent when both counts would be zero.

## Chunk: LAYR

Flags: none (optional, not `COPY_SAFE`). One raster image placed on the canvas.

| Field | Type | Description |
| --- | --- | --- |
| Role | `u8` | `0` = below the territories, `1` = above the territories, `2` = label art |
| Name | `string` | 0 to 255 bytes |
| Z-order | `u16` | Paint order: a lower value is further back |
| Blend mode | `u8` | `0` = normal, `1` = multiply, `2` = screen, `3` = overlay |
| Opacity | `u8` | 0 to 255, for 0.0 to 1.0 |
| X, Y | `uvarint` each | Top left corner on the canvas, in pixels |
| Width, Height | `uvarint` each | Size on the canvas, in pixels, at least 1 |
| Format | `u8` | `0` = PNG, `1` = WebP, `2` = AVIF |
| Source | `u8` | `0` = inline, `1` = external |
| Image | by source, below | |

The rectangle lies on the canvas. The image is scaled to it, so an image may be stored smaller than it is drawn. A
layer that covers the canvas has X and Y of 0 and the canvas's width and height.

**Z-order** is each layer's own statement of where it is painted: layers are painted from the lowest value to the
highest. The `LAYR` chunks appear in that order too, so a file has one encoding, but the value is what counts: a tool
that stores layers apart and puts a file together again orders them by it. Two layers may share a value; they are
then painted in the order their chunks appear.

**Role** says where a layer belongs when a reader draws the territories itself. Layers below are painted first, then
the territories, their borders and their names, then the layers above. A label-art layer takes the place of the
names the reader would draw; a reader shows one or the other. Every layer below the territories has a lower z-order
than every layer above them, so a reader that only stacks the layers by z-order gets the same picture.

**Name** is for people and tools. Six names keep the meaning they had in version 1, and a reader may treat a layer
by its name. Their conventional z-orders are unchanged:

| Name | Z-order |
| --- | --- |
| `water` | 5 |
| `land` | 10 |
| `connections` | 15 |
| `territories` | 20 |
| `borders` | 25 |
| `labels` | 30 |

Blend modes are those of the W3C Compositing and Blending specification. Colors are sRGB.

### Inline image

| Field | Type | Description |
| --- | --- | --- |
| Length | `uvarint` | At least 1 |
| Data | `bytes[Length]` | The image file |

### External image

| Field | Type | Description |
| --- | --- | --- |
| Digest | `bytes[32]` | SHA-256 of the image file |
| Length | `uvarint` | Byte length of the image file, at least 1 |
| Hint count | `uvarint` | 0 to 4 |
| Hints | `string` each | 1 to 2,048 bytes |

An external image is named by what it is, not by where it is. Whoever holds the map knows where its images are kept:
a host's own storage, a folder beside the file. An image with another digest or length is not the image, and a
reader MUST NOT use it.

A **hint** suggests a location: a path relative to the map file, or an `https` URL. It is advice. A reader MAY
ignore every hint, and a host SHOULD, since following one makes its users contact an address that the map's author
chose.

### What an image may contain

An image holds pixels and what is needed to decode them, and nothing about who made it, when, where or with what:

- PNG: the chunks `IHDR`, `PLTE`, `IDAT`, `IEND`, `tRNS` and `sRGB` only. No animation.
- WebP: the chunks `VP8 `, `VP8L`, `VP8X` and `ALPH` only. That leaves out `EXIF`, `XMP `, `ICCP` and animation.
- AVIF: no Exif or XMP item and no image sequence.

A writer MUST strip anything else. A reader that inspects images MUST reject one that carries it.

## Limits

| What | Limit |
| --- | --- |
| Canvas | 16,384 by 16,384 pixels, 40,000,000 pixels in all |
| Territories | 65,535 |
| Points in one ring or line | 1,000,000 |
| Points in the file | 16,777,216 |
| Connections | 1,048,576 |
| Layers | 255 |

## Version history

| Version | Changes |
| --- | --- |
| 2.0 | Shared container with optional metadata and an author list. Whole-number coordinates on a declared grid. Connections by territory index. Vector land and lines. Layers with a role, a place on the canvas and inline or external images. No file id, description or timestamp. |
| 1.0 | Initial specification |
