package org.adelheid.hondius.akm.image

/**
 * Decodes PNG bytes into an [ImageBuffer].
 *
 * - JVM uses `javax.imageio.ImageIO`.
 * - JS and Wasm call into the Rust `png` crate via the `hondius-png` wasm-bindgen
 *   package. Supports every color type the `png` crate handles, including indexed-color
 *   (PLTE + tRNS), 16-bit channels, and Adam7 interlace.
 */
expect fun decodePng(bytes: ByteArray): ImageBuffer

/**
 * Encodes an [ImageBuffer] to PNG bytes.
 *
 * - JVM uses `javax.imageio.ImageIO`.
 * - JS and Wasm call into the Rust `png` crate, which emits properly-compressed RGBA-8 PNGs
 *   via the `flate2` codec.
 */
expect fun encodePng(buffer: ImageBuffer): ByteArray

/**
 * Constructs an in-memory [MutableImageBuffer] from an ARGB pixel array in row-major order.
 * The `argb` array length must equal `width * height`.
 */
expect fun imageBufferOf(width: Int, height: Int, argb: IntArray): MutableImageBuffer
