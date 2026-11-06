package org.adelheid.hondius.akm

import kotlin.jvm.JvmOverloads

/** Where a layer belongs when a reader draws the territories itself. */
enum class LayerRole(val wireValue: Int) {
	/** Painted before the territories. */
	BELOW(0),

	/** Painted after the territories, their borders and their names. */
	ABOVE(1),

	/** Takes the place of the names the reader would draw. */
	LABEL_ART(2),
	;

	companion object {
		fun fromWireValue(value: Int): LayerRole? = entries.firstOrNull { it.wireValue == value }
	}
}

/** How a layer is combined with what is under it, as the W3C Compositing and Blending specification defines. */
enum class BlendMode(val wireValue: Int) {
	NORMAL(0),
	MULTIPLY(1),
	SCREEN(2),
	OVERLAY(3),
	;

	companion object {
		fun fromWireValue(value: Int): BlendMode? = entries.firstOrNull { it.wireValue == value }
	}
}

/** The file format of a layer's image. */
enum class ImageFormat(val wireValue: Int, val mimeType: String) {
	PNG(0, "image/png"),
	WEBP(1, "image/webp"),
	AVIF(2, "image/avif"),
	;

	companion object {
		fun fromWireValue(value: Int): ImageFormat? = entries.firstOrNull { it.wireValue == value }
	}
}

/** A layer's image: in the file, or named by what it is and kept elsewhere. */
sealed interface AkmImage {
	/** The image file itself. */
	class Inline(val data: ByteArray) : AkmImage {
		override fun equals(other: Any?): Boolean = other is Inline && data.contentEquals(other.data)
		override fun hashCode(): Int = data.contentHashCode()
		override fun toString(): String = "Inline(${data.size} bytes)"
	}

	/**
	 * An image kept outside the file. It is identified by content: an image with another digest or length is not
	 * the image.
	 *
	 * @property sha256 the 32-byte SHA-256 of the image file
	 * @property length the byte length of the image file
	 * @property hints suggested locations, each a path relative to the map or an `https` URL. Advice only: a
	 *   reader may ignore them, and a host should.
	 */
	class External @JvmOverloads constructor(val sha256: ByteArray, val length: Long, val hints: List<String> = emptyList()) : AkmImage {
		override fun equals(other: Any?): Boolean =
			other is External && sha256.contentEquals(other.sha256) && length == other.length && hints == other.hints

		override fun hashCode(): Int = 31 * (31 * sha256.contentHashCode() + length.hashCode()) + hints.hashCode()
		override fun toString(): String = "External($length bytes)"
	}
}

/**
 * One raster image placed on the canvas.
 *
 * @property name for people and tools; the six names [AkmMap.STANDARD_LAYERS] keep the meaning version 1 gave them
 * @property zOrder paint order, 0 to 65,535: a lower value is further back
 * @property opacity 0 to 255
 * @property x left edge on the canvas, in pixels
 * @property y top edge on the canvas, in pixels
 * @property width size on the canvas in pixels; the image is scaled to it
 * @property height size on the canvas in pixels
 */
data class AkmLayer @JvmOverloads constructor(
	val role: LayerRole,
	val name: String,
	val zOrder: Int,
	val x: Int,
	val y: Int,
	val width: Int,
	val height: Int,
	val format: ImageFormat,
	val image: AkmImage,
	val blendMode: BlendMode = BlendMode.NORMAL,
	val opacity: Int = 255,
)
