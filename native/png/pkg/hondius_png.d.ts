/* tslint:disable */
/* eslint-disable */

/**
 * Decoded PNG image: width × height, with `pixels` as ARGB-packed `u32` in row-major order.
 */
export class PngImage {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    readonly height: number;
    /**
     * Returns the ARGB pixel buffer as a fresh `Uint32Array` view.
     */
    readonly pixels: Uint32Array;
    readonly width: number;
}

/**
 * Decode PNG bytes to an ARGB-packed pixel buffer. Returns a JS Error on malformed input.
 *
 * Supports every color type the `png` crate handles: grayscale, RGB, grayscale+alpha, RGBA
 * (8 or 16 bit per channel), and indexed-color with PLTE + tRNS. Adam7 interlace is also
 * handled — the prior pure-Kotlin codec didn't support it.
 */
export function png_decode(bytes: Uint8Array): PngImage;

/**
 * Encode an ARGB-packed pixel buffer to a PNG byte stream.
 *
 * An image of at most 256 colors is written with a palette, at the smallest bit depth that holds its indexes;
 * anything else as 8-bit RGBA. Fully transparent pixels count as one color whatever their RGB. The output holds
 * the chunks IHDR, PLTE, tRNS, IDAT and IEND only.
 */
export function png_encode(width: number, height: number, pixels: Uint32Array): Uint8Array;
