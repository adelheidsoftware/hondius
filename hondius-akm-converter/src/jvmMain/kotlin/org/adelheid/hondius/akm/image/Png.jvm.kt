package org.adelheid.hondius.akm.image

import java.awt.image.BufferedImage
import java.awt.image.DataBuffer
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import org.adelheid.hondius.akm.AkmMap

/**
 * JVM-backed [ImageBuffer] wrapping a [BufferedImage] of type `TYPE_INT_ARGB`.
 * Single-pixel reads delegate to [BufferedImage.getRGB]; bulk reads use the array form.
 */
internal class BufferedImageBuffer(private val image: BufferedImage) : MutableImageBuffer {
	override val width: Int get() = image.width
	override val height: Int get() = image.height
	override fun getRgb(x: Int, y: Int): Int = image.getRGB(x, y)
	override fun setRgb(x: Int, y: Int, argb: Int) = image.setRGB(x, y, argb)
	override fun toArgbArray(): IntArray = image.getRGB(0, 0, width, height, null, 0, width)
	val bufferedImage: BufferedImage get() = image
}

actual fun decodePng(bytes: ByteArray): ImageBuffer {
	// A PNG declares its dimensions in the header independently of how well it compresses, so a
	// tiny file can ask for a multi-gigabyte raster. Read the dimensions from the header and reject
	// implausible ones BEFORE decoding any pixel data — ImageIO.read allocates up front.
	val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
		?: error("Failed to decode PNG bytes — could not open an image stream")

	val image = input.use { stream ->
		val readers = ImageIO.getImageReaders(stream)
		check(readers.hasNext()) { "Failed to decode PNG bytes — no compatible reader" }

		val reader = readers.next()
		try {
			reader.input = stream
			val width = reader.getWidth(0)
			val height = reader.getHeight(0)
			require(width in 1..AkmMap.MAX_CANVAS_DIMENSION && height in 1..AkmMap.MAX_CANVAS_DIMENSION) {
				"Image dimensions ${width}x$height are outside 1..${AkmMap.MAX_CANVAS_DIMENSION}"
			}
			require(width.toLong() * height <= AkmMap.MAX_CANVAS_PIXELS) {
				"Image area ${width}x$height exceeds ${AkmMap.MAX_CANVAS_PIXELS} pixels"
			}
			reader.read(0)
		} finally {
			reader.dispose()
		}
	}

	// Normalize to TYPE_INT_ARGB so callers see a consistent pixel layout.
	val normalized = if (image.type == BufferedImage.TYPE_INT_ARGB) {
		image
	} else {
		BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also { dst ->
			val g = dst.createGraphics()
			try {
				g.drawImage(image, 0, 0, null)
			} finally {
				g.dispose()
			}
		}
	}
	return BufferedImageBuffer(normalized)
}

/**
 * An image of at most 256 colors is written with a palette, at the smallest bit depth that holds its indexes;
 * anything else as 8-bit RGBA. Fully transparent pixels count as one color whatever their RGB.
 */
actual fun encodePng(buffer: ImageBuffer): ByteArray {
	val argb = buffer.toArgbArray()
	val image = indexedImage(buffer.width, buffer.height, argb)
		?: BufferedImage(buffer.width, buffer.height, BufferedImage.TYPE_INT_ARGB).also {
			it.setRGB(0, 0, buffer.width, buffer.height, argb, 0, buffer.width)
		}
	val writers = ImageIO.getImageWritersByFormatName("png")
	check(writers.hasNext()) { "ImageIO had no PNG writer available" }
	val writer = writers.next()
	val out = ByteArrayOutputStream()
	try {
		// The writer's default trades size for speed; a map's images are written once and read many times
		val settings = writer.defaultWriteParam
		if (settings.canWriteCompressed()) {
			settings.compressionMode = ImageWriteParam.MODE_EXPLICIT
			settings.compressionQuality = 0f
		}
		ImageIO.createImageOutputStream(out).use { stream ->
			writer.output = stream
			writer.write(null, IIOImage(image, null, null), settings)
		}
	} finally {
		writer.dispose()
	}
	return out.toByteArray()
}

/** The image with a palette, or null when it has more than 256 colors. */
private fun indexedImage(width: Int, height: Int, argb: IntArray): BufferedImage? {
	val indexByColor = LinkedHashMap<Int, Int>()
	val indexes = ByteArray(argb.size)
	for ((position, pixel) in argb.withIndex()) {
		val color = if (pixel ushr 24 == 0) 0 else pixel
		var index = indexByColor[color]
		if (index == null) {
			if (indexByColor.size == 256) return null
			index = indexByColor.size
			indexByColor[color] = index
		}
		indexes[position] = index.toByte()
	}
	val bits = when {
		indexByColor.size <= 2 -> 1
		indexByColor.size <= 4 -> 2
		indexByColor.size <= 16 -> 4
		else -> 8
	}
	val palette = indexByColor.keys.toIntArray()
	val model = IndexColorModel(bits, palette.size, palette, 0, true, -1, DataBuffer.TYPE_BYTE)
	val type = if (bits == 8) BufferedImage.TYPE_BYTE_INDEXED else BufferedImage.TYPE_BYTE_BINARY
	val image = BufferedImage(width, height, type, model)
	val raster = image.raster
	for (y in 0 until height) {
		for (x in 0 until width) {
			raster.setSample(x, y, 0, indexes[y * width + x].toInt() and 0xFF)
		}
	}
	return image
}

actual fun imageBufferOf(width: Int, height: Int, argb: IntArray): MutableImageBuffer {
	// Long arithmetic: width * height overflows Int for large canvases, which would make a
	// mismatched array look like a match.
	require(argb.size.toLong() == width.toLong() * height) {
		"argb array length ${argb.size} does not match $width * $height = ${width.toLong() * height}"
	}
	val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
	image.setRGB(0, 0, width, height, argb, 0, width)
	return BufferedImageBuffer(image)
}
