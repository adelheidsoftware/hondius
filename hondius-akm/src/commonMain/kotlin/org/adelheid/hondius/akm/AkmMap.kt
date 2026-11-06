package org.adelheid.hondius.akm

import org.adelheid.hondius.container.Chunk
import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException
import org.adelheid.hondius.container.Metadata
import org.adelheid.hondius.container.Text
import org.adelheid.hondius.container.UVARINT_MAX
import org.adelheid.hondius.container.Uuids
import kotlin.jvm.JvmOverloads

/**
 * A map, as an `.akm` file holds it: territories as vector outlines, the connections between them, and optional
 * artwork.
 *
 * Building one checks every rule of the format, so a map that exists can be written, and a file that was read is a
 * valid map. Connections and layers are put into the order the file holds them in.
 *
 * @property canvasWidth in pixels
 * @property canvasHeight in pixels
 * @property territories at least one; a territory's position here is the index connections refer to
 * @param connections in any order; a bidirectional one may name its two territories either way round
 * @property gridBits `g`: every coordinate is a whole number of 1/2^g pixel
 * @property land land that belongs to no territory
 * @property lines open lines, typically showing a connection across water
 * @param layers in any order; they are held sorted by z-order
 * @property metadata what the file says about itself
 * @property carriedChunks chunks of a later minor version that a reader did not know. Pass on only those that are
 *   [Chunk.isCopySafe] when building a map whose territories or connections differ from the one they came with.
 */
class AkmMap @JvmOverloads constructor(
	val canvasWidth: Int,
	val canvasHeight: Int,
	val territories: List<AkmTerritory>,
	connections: List<AkmConnection> = emptyList(),
	val gridBits: Int = DEFAULT_GRID_BITS,
	val land: List<AkmRegion> = emptyList(),
	val lines: List<AkmLine> = emptyList(),
	layers: List<AkmLayer> = emptyList(),
	val metadata: Metadata = Metadata(),
	val carriedChunks: List<Chunk> = emptyList(),
) {
	/** The connections sorted by their first territory, then their second; a bidirectional one has `a < b`. */
	val connections: List<AkmConnection> = connections
		.map { if (it.bidirectional && it.a > it.b) AkmConnection(it.b, it.a, true) else it }
		.sortedWith(compareBy({ it.a }, { it.b }))

	/** The layers from the back to the front. Layers with the same z-order keep the order they were given in. */
	val layers: List<AkmLayer> = layers.sortedBy { it.zOrder }

	init {
		AkmRules.check(this)
	}

	/** Grid units per pixel. */
	val gridScale: Int get() = 1 shl gridBits

	/** The position of the territory with [id], or -1. */
	fun territoryIndex(id: String): Int = territories.indexOfFirst { it.id == id }

	override fun equals(other: Any?): Boolean =
		other is AkmMap && canvasWidth == other.canvasWidth && canvasHeight == other.canvasHeight && gridBits == other.gridBits &&
			territories == other.territories && connections == other.connections && land == other.land && lines == other.lines &&
			layers == other.layers && metadata == other.metadata && carriedChunks == other.carriedChunks

	override fun hashCode(): Int {
		var result = 31 * canvasWidth + canvasHeight
		result = 31 * result + gridBits
		result = 31 * result + territories.hashCode()
		result = 31 * result + connections.hashCode()
		result = 31 * result + land.hashCode()
		result = 31 * result + lines.hashCode()
		result = 31 * result + layers.hashCode()
		result = 31 * result + metadata.hashCode()
		return 31 * result + carriedChunks.hashCode()
	}

	companion object {
		/** A sixteenth of a pixel. */
		const val DEFAULT_GRID_BITS: Int = 4
		const val MAX_GRID_BITS: Int = 8

		/**
		 * Canvas dimensions are read straight from a file, and consumers allocate raster buffers from them: a
		 * renderer at 4 bytes per pixel turns an unchecked 65535 x 65535 canvas into a 17 GB request. Bounding them
		 * here means every consumer of the codec inherits the check.
		 */
		const val MAX_CANVAS_DIMENSION: Int = 16_384

		/** About 40 megapixels, or roughly 160 MB as a 32-bit raster. */
		const val MAX_CANVAS_PIXELS: Long = 40_000_000L

		const val MAX_TERRITORIES: Int = 65_535
		const val MAX_REGIONS: Int = 1_024
		const val MAX_RINGS: Int = 65_535
		const val MAX_POINTS_PER_LIST: Int = 1_000_000
		const val MAX_POINTS: Int = 16_777_216
		const val MAX_CONNECTIONS: Int = 1_048_576
		const val MAX_SHAPES: Int = 65_535
		const val MAX_LAYERS: Int = 255
		const val MAX_NAME_BYTES: Int = 255
		const val MAX_Z_ORDER: Int = 65_535
		const val MAX_HINTS: Int = 4
		const val MAX_HINT_BYTES: Int = 2_048

		/** The layer names that keep the meaning version 1 gave them, with their conventional z-orders. */
		val STANDARD_LAYERS: Map<String, Int> = mapOf(
			"water" to 5,
			"land" to 10,
			"connections" to 15,
			"territories" to 20,
			"borders" to 25,
			"labels" to 30,
		)
	}
}

/** The rules a map must meet, apart from how it is written as bytes. */
internal object AkmRules {
	fun check(map: AkmMap) {
		checkCanvas(map)
		val maxX = map.canvasWidth shl map.gridBits
		val maxY = map.canvasHeight shl map.gridBits
		var points = 0L

		limit(map.territories.size, AkmMap.MAX_TERRITORIES, "territories")
		if (map.territories.isEmpty()) throw FormatException(FormatError.BAD_VALUE, "A map has at least one territory")
		val ids = HashSet<String>()
		for ((index, territory) in map.territories.withIndex()) {
			val what = "Territory $index"
			if (!Uuids.isValid(territory.id)) throw FormatException(FormatError.BAD_VALUE, "$what has an id that is not in 8-4-4-4-12 lowercase form")
			if (!ids.add(territory.id)) throw FormatException(FormatError.DUPLICATE, "$what repeats the id ${territory.id}")
			Text.check(territory.name, "The name of territory $index", AkmMap.MAX_NAME_BYTES)
			territory.anchor?.let {
				if (it.x !in 0..maxX || it.y !in 0..maxY) throw FormatException(FormatError.BAD_GEOMETRY, "$what has its anchor off the canvas")
			}
			limit(territory.regions.size, AkmMap.MAX_REGIONS, "regions in territory $index")
			if (territory.regions.isEmpty()) throw FormatException(FormatError.BAD_GEOMETRY, "$what has no region")
			for (region in territory.regions) points += checkRegion(region, maxX, maxY, what)
		}

		limit(map.land.size, AkmMap.MAX_SHAPES, "land shapes")
		for ((index, region) in map.land.withIndex()) points += checkRegion(region, maxX, maxY, "Land shape $index")
		limit(map.lines.size, AkmMap.MAX_SHAPES, "lines")
		for ((index, line) in map.lines.withIndex()) {
			limit(line.pointCount, AkmMap.MAX_POINTS_PER_LIST, "points in line $index")
			checkPoints(line.points, maxX, maxY, closed = false, what = "Line $index")
			points += line.pointCount
		}
		if (points > AkmMap.MAX_POINTS) throw FormatException(FormatError.LIMIT_EXCEEDED, "$points points, above the limit of ${AkmMap.MAX_POINTS}")

		checkConnections(map)
		checkLayers(map)
	}

	private fun checkCanvas(map: AkmMap) {
		if (map.canvasWidth < 1 || map.canvasHeight < 1) throw FormatException(FormatError.BAD_VALUE, "The canvas is at least 1 by 1 pixel")
		if (map.canvasWidth > AkmMap.MAX_CANVAS_DIMENSION || map.canvasHeight > AkmMap.MAX_CANVAS_DIMENSION ||
			map.canvasWidth.toLong() * map.canvasHeight > AkmMap.MAX_CANVAS_PIXELS
		) {
			throw FormatException(
				FormatError.LIMIT_EXCEEDED,
				"A canvas of ${map.canvasWidth} by ${map.canvasHeight} exceeds ${AkmMap.MAX_CANVAS_DIMENSION} per side or ${AkmMap.MAX_CANVAS_PIXELS} pixels",
			)
		}
		if (map.gridBits !in 0..AkmMap.MAX_GRID_BITS) throw FormatException(FormatError.BAD_VALUE, "The grid is 0 to ${AkmMap.MAX_GRID_BITS} bits, got ${map.gridBits}")
	}

	/** Checks a region and returns how many points it holds. */
	private fun checkRegion(region: AkmRegion, maxX: Int, maxY: Int, what: String): Int {
		limit(region.rings.size, AkmMap.MAX_RINGS, "rings in a region")
		var points = 0
		for ((index, ring) in region.rings.withIndex()) {
			limit(ring.pointCount, AkmMap.MAX_POINTS_PER_LIST, "points in a ring")
			checkPoints(ring.points, maxX, maxY, closed = true, what = what)
			val area = ring.signedArea2()
			if (area == 0L) throw FormatException(FormatError.BAD_GEOMETRY, "$what has a ring with no area")
			if ((area > 0) != (index == 0)) {
				val expected = if (index == 0) "an outline, which has a positive area" else "a hole, which has a negative area"
				throw FormatException(FormatError.BAD_GEOMETRY, "$what has a ring running the wrong way for $expected")
			}
			points += ring.pointCount
		}
		return points
	}

	private fun checkPoints(points: IntArray, maxX: Int, maxY: Int, closed: Boolean, what: String) {
		val count = points.size / 2
		for (i in 0 until count) {
			val x = points[i * 2]
			val y = points[i * 2 + 1]
			if (x !in 0..maxX || y !in 0..maxY) throw FormatException(FormatError.BAD_GEOMETRY, "$what has a point off the canvas: $x, $y")
			val next = if (i + 1 < count) i + 1 else if (closed) 0 else continue
			if (x == points[next * 2] && y == points[next * 2 + 1]) {
				throw FormatException(FormatError.BAD_GEOMETRY, "$what repeats the point $x, $y")
			}
		}
	}

	private fun checkConnections(map: AkmMap) {
		limit(map.connections.size, AkmMap.MAX_CONNECTIONS, "connections")
		val pairs = HashSet<Long>()
		for (connection in map.connections) {
			val a = connection.a
			val b = connection.b
			if (a !in map.territories.indices || b !in map.territories.indices) {
				throw FormatException(FormatError.BAD_REFERENCE, "A connection names territory $a or $b of ${map.territories.size}")
			}
			if (a == b) throw FormatException(FormatError.BAD_VALUE, "Territory $a is connected to itself")
			val low = minOf(a, b).toLong()
			val high = maxOf(a, b).toLong()
			if (!pairs.add((low shl 32) or high)) {
				throw FormatException(FormatError.DUPLICATE, "Territories $low and $high have more than one connection; both directions are one bidirectional connection")
			}
		}
	}

	private fun checkLayers(map: AkmMap) {
		limit(map.layers.size, AkmMap.MAX_LAYERS, "layers")
		var highestBelow = -1
		var lowestAbove = Int.MAX_VALUE
		for (layer in map.layers) {
			val what = "Layer '${layer.name}'"
			Text.check(layer.name, "A layer name", AkmMap.MAX_NAME_BYTES)
			if (layer.zOrder !in 0..AkmMap.MAX_Z_ORDER) throw FormatException(FormatError.BAD_VALUE, "$what has a z-order outside 0..${AkmMap.MAX_Z_ORDER}")
			if (layer.opacity !in 0..255) throw FormatException(FormatError.BAD_VALUE, "$what has an opacity outside 0..255")
			val onCanvas = layer.x >= 0 && layer.y >= 0 && layer.width >= 1 && layer.height >= 1 &&
				layer.x.toLong() + layer.width <= map.canvasWidth && layer.y.toLong() + layer.height <= map.canvasHeight
			if (!onCanvas) throw FormatException(FormatError.BAD_VALUE, "$what does not lie on the canvas")
			when (layer.role) {
				LayerRole.BELOW -> highestBelow = maxOf(highestBelow, layer.zOrder)
				LayerRole.ABOVE -> lowestAbove = minOf(lowestAbove, layer.zOrder)
				LayerRole.LABEL_ART -> Unit
			}
			when (val image = layer.image) {
				is AkmImage.Inline -> ImageRules.check(layer.format, image.data, what)
				is AkmImage.External -> {
					if (image.sha256.size != 32) throw FormatException(FormatError.BAD_VALUE, "$what names its image by a digest that is not 32 bytes")
					if (image.length !in 1..UVARINT_MAX) throw FormatException(FormatError.BAD_VALUE, "$what names an image of ${image.length} bytes")
					limit(image.hints.size, AkmMap.MAX_HINTS, "hints for one image")
					for (hint in image.hints) Text.check(hint, "An image hint", AkmMap.MAX_HINT_BYTES, 1)
				}
			}
		}
		if (highestBelow >= lowestAbove) {
			throw FormatException(FormatError.BAD_VALUE, "A layer below the territories has z-order $highestBelow, not lower than $lowestAbove of a layer above them")
		}
	}

	private fun limit(count: Int, max: Int, what: String) {
		if (count > max) throw FormatException(FormatError.LIMIT_EXCEEDED, "$count $what, above the limit of $max")
	}
}
