//! PNG codec for the Hondius map converter.
//!
//! Compiled to Wasm with `wasm-pack` and consumed from Kotlin/JS and Kotlin/Wasm via
//! wasm-bindgen-generated JS glue. Exposes:
//!
//! - `png_decode(bytes) -> PngImage { width, height, pixels: Uint32Array }`
//!   where each pixel is an ARGB-packed `u32` (0xAARRGGBB) matching `ImageBuffer.toArgbArray()`.
//! - `png_encode(width, height, pixels: Uint32Array) -> Uint8Array` (indexed when the image has at most
//!   256 colors, RGBA-8 otherwise).

use std::collections::HashMap;

use wasm_bindgen::prelude::*;

/// Decoded PNG image: width × height, with `pixels` as ARGB-packed `u32` in row-major order.
#[wasm_bindgen]
pub struct PngImage {
    width: u32,
    height: u32,
    pixels: Vec<u32>,
}

#[wasm_bindgen]
impl PngImage {
    #[wasm_bindgen(getter)]
    pub fn width(&self) -> u32 {
        self.width
    }

    #[wasm_bindgen(getter)]
    pub fn height(&self) -> u32 {
        self.height
    }

    /// Returns the ARGB pixel buffer as a fresh `Uint32Array` view.
    #[wasm_bindgen(getter)]
    pub fn pixels(&self) -> Vec<u32> {
        self.pixels.clone()
    }
}

/// Decode PNG bytes to an ARGB-packed pixel buffer. Returns a JS Error on malformed input.
///
/// Supports every color type the `png` crate handles: grayscale, RGB, grayscale+alpha, RGBA
/// (8 or 16 bit per channel), and indexed-color with PLTE + tRNS. Adam7 interlace is also
/// handled — the prior pure-Kotlin codec didn't support it.
#[wasm_bindgen]
pub fn png_decode(bytes: &[u8]) -> Result<PngImage, JsValue> {
    let mut decoder = png::Decoder::new(std::io::Cursor::new(bytes));
    // Expand indexed-color (PLTE) PNGs to RGB(A), and expand sub-byte grayscale/transparency
    // info so downstream code only has to handle full-byte color types.
    decoder.set_transformations(png::Transformations::EXPAND);
    let mut reader = decoder
        .read_info()
        .map_err(|e| JsValue::from_str(&format!("PNG decode failed at header: {e}")))?;

    // png 0.18 returns Option here — None means an unknown buffer size (e.g. APNG before metadata is read).
    let output_size = reader
        .output_buffer_size()
        .ok_or_else(|| JsValue::from_str("PNG output buffer size not yet known"))?;
    let mut buf = vec![0u8; output_size];
    let info = reader
        .next_frame(&mut buf)
        .map_err(|e| JsValue::from_str(&format!("PNG decode failed reading frame: {e}")))?;
    buf.truncate(info.buffer_size());

    let width = info.width;
    let height = info.height;
    let pixel_count = (width as usize)
        .checked_mul(height as usize)
        .ok_or_else(|| JsValue::from_str("PNG dimensions overflow"))?;

    let mut pixels = vec![0u32; pixel_count];
    match info.color_type {
        png::ColorType::Rgba => {
            // Expect 8-bit-per-channel after read_info normalization for non-16-bit inputs.
            match info.bit_depth {
                png::BitDepth::Eight => pack_rgba8(&buf, &mut pixels),
                png::BitDepth::Sixteen => pack_rgba16(&buf, &mut pixels),
                other => return Err(JsValue::from_str(&format!("Unsupported RGBA bit depth: {other:?}"))),
            }
        }
        png::ColorType::Rgb => match info.bit_depth {
            png::BitDepth::Eight => pack_rgb8(&buf, &mut pixels),
            png::BitDepth::Sixteen => pack_rgb16(&buf, &mut pixels),
            other => return Err(JsValue::from_str(&format!("Unsupported RGB bit depth: {other:?}"))),
        },
        png::ColorType::Grayscale => pack_gray(&buf, &mut pixels, info.bit_depth),
        png::ColorType::GrayscaleAlpha => match info.bit_depth {
            png::BitDepth::Eight => pack_ga8(&buf, &mut pixels),
            png::BitDepth::Sixteen => pack_ga16(&buf, &mut pixels),
            other => return Err(JsValue::from_str(&format!("Unsupported GA bit depth: {other:?}"))),
        },
        png::ColorType::Indexed => {
            // EXPAND transformation should turn Indexed into RGB or RGBA before we get here.
            // Reaching this arm means a 1/2/4-bit indexed image without tRNS that the decoder
            // returned as-is — fall back to grayscale expansion.
            pack_gray(&buf, &mut pixels, info.bit_depth);
        }
    }

    Ok(PngImage { width, height, pixels })
}

fn pack_rgba8(buf: &[u8], out: &mut [u32]) {
    for (i, chunk) in buf.chunks_exact(4).enumerate() {
        out[i] = ((chunk[3] as u32) << 24)
            | ((chunk[0] as u32) << 16)
            | ((chunk[1] as u32) << 8)
            | (chunk[2] as u32);
    }
}

fn pack_rgba16(buf: &[u8], out: &mut [u32]) {
    // Take the high byte of each 16-bit channel (lossy downscale to 8-bit, matching ImageIO behavior).
    for (i, chunk) in buf.chunks_exact(8).enumerate() {
        out[i] = ((chunk[6] as u32) << 24)
            | ((chunk[0] as u32) << 16)
            | ((chunk[2] as u32) << 8)
            | (chunk[4] as u32);
    }
}

fn pack_rgb8(buf: &[u8], out: &mut [u32]) {
    for (i, chunk) in buf.chunks_exact(3).enumerate() {
        out[i] = 0xFF00_0000
            | ((chunk[0] as u32) << 16)
            | ((chunk[1] as u32) << 8)
            | (chunk[2] as u32);
    }
}

fn pack_rgb16(buf: &[u8], out: &mut [u32]) {
    for (i, chunk) in buf.chunks_exact(6).enumerate() {
        out[i] = 0xFF00_0000
            | ((chunk[0] as u32) << 16)
            | ((chunk[2] as u32) << 8)
            | (chunk[4] as u32);
    }
}

fn pack_gray(buf: &[u8], out: &mut [u32], depth: png::BitDepth) {
    // `read_info()` expands sub-byte grayscale to one byte per pixel.
    let stride = match depth {
        png::BitDepth::Sixteen => 2,
        _ => 1,
    };
    for (i, chunk) in buf.chunks_exact(stride).enumerate() {
        let v = chunk[0] as u32;
        out[i] = 0xFF00_0000 | (v << 16) | (v << 8) | v;
    }
}

fn pack_ga8(buf: &[u8], out: &mut [u32]) {
    for (i, chunk) in buf.chunks_exact(2).enumerate() {
        let v = chunk[0] as u32;
        let a = chunk[1] as u32;
        out[i] = (a << 24) | (v << 16) | (v << 8) | v;
    }
}

fn pack_ga16(buf: &[u8], out: &mut [u32]) {
    for (i, chunk) in buf.chunks_exact(4).enumerate() {
        let v = chunk[0] as u32;
        let a = chunk[2] as u32;
        out[i] = (a << 24) | (v << 16) | (v << 8) | v;
    }
}

/// Encode an ARGB-packed pixel buffer to a PNG byte stream.
///
/// An image of at most 256 colors is written with a palette, at the smallest bit depth that holds its indexes;
/// anything else as 8-bit RGBA. Fully transparent pixels count as one color whatever their RGB. The output holds
/// the chunks IHDR, PLTE, tRNS, IDAT and IEND only.
#[wasm_bindgen]
pub fn png_encode(width: u32, height: u32, pixels: &[u32]) -> Result<Vec<u8>, JsValue> {
    let expected = (width as usize)
        .checked_mul(height as usize)
        .ok_or_else(|| JsValue::from_str("Image dimensions overflow"))?;
    if pixels.len() != expected {
        return Err(JsValue::from_str(&format!(
            "pixels length {} does not match width * height = {}",
            pixels.len(),
            expected
        )));
    }

    let mut out = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut out, width, height);
        encoder.set_compression(png::Compression::High);
        let data = match index_pixels(pixels) {
            Some((palette, indexes)) => {
                let depth = match palette.len() {
                    0..=2 => png::BitDepth::One,
                    3..=4 => png::BitDepth::Two,
                    5..=16 => png::BitDepth::Four,
                    _ => png::BitDepth::Eight,
                };
                encoder.set_color(png::ColorType::Indexed);
                encoder.set_depth(depth);
                let mut plte = Vec::with_capacity(palette.len() * 3);
                let mut trns = Vec::with_capacity(palette.len());
                for &argb in &palette {
                    plte.push(((argb >> 16) & 0xFF) as u8);
                    plte.push(((argb >> 8) & 0xFF) as u8);
                    plte.push((argb & 0xFF) as u8);
                    trns.push(((argb >> 24) & 0xFF) as u8);
                }
                encoder.set_palette(plte);
                if trns.iter().any(|&alpha| alpha != 0xFF) {
                    encoder.set_trns(trns);
                }
                pack_rows(&indexes, width as usize, height as usize, depth as usize)
            }
            None => {
                encoder.set_color(png::ColorType::Rgba);
                encoder.set_depth(png::BitDepth::Eight);
                let mut rgba = Vec::with_capacity(expected * 4);
                for &argb in pixels {
                    rgba.push(((argb >> 16) & 0xFF) as u8);
                    rgba.push(((argb >> 8) & 0xFF) as u8);
                    rgba.push((argb & 0xFF) as u8);
                    rgba.push(((argb >> 24) & 0xFF) as u8);
                }
                rgba
            }
        };
        let mut writer = encoder
            .write_header()
            .map_err(|e| JsValue::from_str(&format!("PNG encode header failed: {e}")))?;
        writer
            .write_image_data(&data)
            .map_err(|e| JsValue::from_str(&format!("PNG encode data failed: {e}")))?;
    }
    Ok(out)
}

/// The image as a palette and one index per pixel, or `None` when it has more than 256 colors.
fn index_pixels(pixels: &[u32]) -> Option<(Vec<u32>, Vec<u8>)> {
    let mut palette: Vec<u32> = Vec::new();
    let mut lookup: HashMap<u32, u8> = HashMap::new();
    let mut indexes = Vec::with_capacity(pixels.len());
    for &argb in pixels {
        let color = if argb >> 24 == 0 { 0 } else { argb };
        let index = match lookup.get(&color) {
            Some(&index) => index,
            None => {
                if palette.len() == 256 {
                    return None;
                }
                let index = palette.len() as u8;
                palette.push(color);
                lookup.insert(color, index);
                index
            }
        };
        indexes.push(index);
    }
    Some((palette, indexes))
}

/// Packs one index per pixel into rows of `depth` bits per pixel, the first pixel in the high bits, each row
/// padded to a whole byte.
fn pack_rows(indexes: &[u8], width: usize, height: usize, depth: usize) -> Vec<u8> {
    if depth == 8 {
        return indexes.to_vec();
    }
    let row_bytes = (width * depth).div_ceil(8);
    let mut packed = vec![0u8; row_bytes * height];
    for y in 0..height {
        for x in 0..width {
            let bit = x * depth;
            packed[y * row_bytes + bit / 8] |= indexes[y * width + x] << (8 - depth - bit % 8);
        }
    }
    packed
}
