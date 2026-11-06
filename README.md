# Hondius

The `.akm` map format and the `.akp` palette format of [Adelkrieg](https://adelkrieg.com), a browser-based multiplayer
territory strategy game: their specifications, and the Kotlin Multiplatform libraries that read and write them.

Named after Jodocus Hondius, the engraver who republished Mercator's atlas.

## What is here

| Folder | What it is |
| --- | --- |
| `spec/` | The format specifications: [`container.md`](spec/container.md) for what both formats share, [`akm.md`](spec/akm.md) for maps, [`akp.md`](spec/akp.md) for palettes; version 1 is in `spec/v1/`, and [`conformance/`](spec/conformance/README.md) holds files to check an implementation against |
| `hondius-container/` | What both formats share: the header, chunks, metadata with its author list, the checksum, and the one error type |
| `hondius-akm/` | The `.akm` codec |
| `hondius-akm-converter/` | Converts legacy `.rkm` maps and version 1 `.akm` files to the current format |
| `hondius-akp/` | The `.akp` codec |
| `hondius-akp-converter/` | Converts legacy `.rkp` palettes and version 1 `.akp` files to the current format |
| `native/png/` | A Rust-built WebAssembly PNG codec for the map converter's JS and Wasm targets, committed prebuilt |

Every module builds for the JVM (Java 17 or later), JS and Wasm. The libraries are the reference implementation of
the specifications. The container and the two codecs are plain Kotlin with no dependency beyond the standard library.

## Status

The codecs read and write version 2.0 of the formats. Version 1 files (`spec/v1/`) and the legacy `.rkm` and `.rkp`
files are read by the two converters, which turn them into version 2.0.

One rule of the map format is not enforced in full: an inline AVIF image is checked for its file type only, not for
the metadata an image may not carry. PNG and WebP images are checked completely.

Not released yet: the version is `1.0.0-SNAPSHOT` and nothing is on Maven Central. To use it from another build,
publish it to the local Maven repository (`~/.m2/repository`), which needs no credentials or signing:

```bash
./gradlew publishToMavenLocal
```

and depend on it with `mavenLocal()` among the consumer's repositories:

```kotlin
dependencies {
	implementation("org.adelheid:hondius-akm:1.0.0-SNAPSHOT")
}
```

A JVM-only consumer uses the platform artifact, `org.adelheid:hondius-akm-jvm`. The other modules follow the same
pattern: `hondius-akp`, `hondius-akm-converter`, `hondius-akp-converter`.

### The map converter on JS and Wasm

`hondius-akm-converter` decodes and encodes PNG. On the JVM it uses the platform's own codec. Its JS and Wasm
artifacts call a Rust-built WebAssembly module, `hondius-png`, which is not on npm and which the Maven artifacts do
not carry. A JS or Wasm consumer of the map converter declares it from a checkout of this repository:

```kotlin
wasmJsMain.dependencies {
	implementation(npm("hondius-png", file("<checkout>/native/png/pkg")))
}
```

No other module needs anything of the kind, on any target.

## Usage

```kotlin
import org.adelheid.hondius.akm.AkmMap
import org.adelheid.hondius.akm.codec.AkmDecoder
import org.adelheid.hondius.akm.codec.AkmEncoder

val bytes: ByteArray = AkmEncoder.encode(map)
val map: AkmMap = AkmDecoder.decode(bytes)
```

```kotlin
import org.adelheid.hondius.akp.AkpPalette
import org.adelheid.hondius.akp.codec.AkpDecoder
import org.adelheid.hondius.akp.codec.AkpEncoder

val bytes: ByteArray = AkpEncoder.encode(palette)
val palette: AkpPalette = AkpDecoder.decode(bytes)
```

A decoder refuses anything that is not a valid file with a `FormatException`, whose `error` names the rule that was
broken.

Beside the codecs, the libraries offer what a host or an editor needs around them:

| Call | What it is for |
| --- | --- |
| `AkmDecoder.summarize(bytes)` | A map's metadata, canvas and counts, without decoding its outlines or images |
| `AkmDecoder.summarize(source)` | The same from a `ByteSource`, such as a file on disk: the images are passed over, not read |
| `AkmEncoder.withMetadata(file, metadata)`, `AkpEncoder.withMetadata(file, metadata)` | Replaces a stored file's metadata, for a host that says who the authors are when it serves a file |
| `AkmGrid` | Puts float pixel outlines onto a map's grid and back, for an editor that works in pixels |
| `AkmAnchors.of(territory, gridBits)` | Where a territory's label goes: its anchor, or a point inside it when it has none |
| `AkmBorders.shared(map)`, `AkmBorders.contacts(map)` | The borders neighbors share, and the places where they touch without sharing a vertex |

Older files go through the converters:

```kotlin
import org.adelheid.hondius.akm.converter.RkmToAkmConverter
import org.adelheid.hondius.akm.v1.AkmV1Converter
import org.adelheid.hondius.rkm.codec.RkmDecoder

val fromVersionOne: AkmMap = AkmV1Converter.convert(versionOneBytes)
val fromLegacy: AkmMap = RkmToAkmConverter().convert(RkmDecoder.decode(rkmBytes))
```

A version 1 file opens with the same signature as a current one. `AkmV1Decoder.isVersion1(bytes)` and
`AkpV1Decoder.isVersion1(bytes)` tell them apart.

## Building

```bash
./gradlew build
```

That compiles every target and runs the JVM, Node and browser tests; the browser tests need Chrome. One target's
tests run with `./gradlew jvmTest`, `./gradlew jsNodeTest`, `./gradlew jsBrowserTest` or `./gradlew wasmJsBrowserTest`.

The WebAssembly module in `native/png/pkg` is committed prebuilt. After a change to `native/png/src`, rebuild it with
`native/png/build.ps1` (Rust 1.95.0, wasm-pack 0.14.0), which also keeps local paths out of the binary.

## Releasing

See [RELEASING.md](RELEASING.md). Credentials live in `~/.gradle/gradle.properties`, never in this repository.

## License

MIT, see [LICENSE](LICENSE).
