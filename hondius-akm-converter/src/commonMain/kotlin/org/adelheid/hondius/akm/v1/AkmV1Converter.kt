package org.adelheid.hondius.akm.v1

import org.adelheid.hondius.akm.AkmAnchors
import org.adelheid.hondius.akm.AkmConnection
import org.adelheid.hondius.akm.AkmGrid
import org.adelheid.hondius.akm.AkmImage
import org.adelheid.hondius.akm.AkmLayer
import org.adelheid.hondius.akm.AkmMap
import org.adelheid.hondius.akm.AkmTerritory
import org.adelheid.hondius.akm.ImageFormat
import org.adelheid.hondius.akm.LayerRole
import org.adelheid.hondius.akm.image.decodePng
import org.adelheid.hondius.akm.image.encodePng
import org.adelheid.hondius.container.FormatError
import org.adelheid.hondius.container.FormatException
import org.adelheid.hondius.container.Metadata
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * Puts float outlines, as version 1 and the legacy tracer hold them, onto the whole-number grid of the current
 * format.
 */
internal object GridGeometry {
	/**
	 * @param ownAnchors true when each territory's centroid is where its label goes, as in a version 1 file; false
	 *   when the territories carry no such point, and one is then found on the grid
	 * @return the territories that still have a shape on the grid, and the connections among them
	 */
	fun convert(
		width: Int,
		height: Int,
		gridBits: Int,
		territories: List<AkmV1Territory>,
		edges: List<AkmV1Edge>,
		ownAnchors: Boolean = true,
	): Pair<List<AkmTerritory>, List<AkmConnection>> {
		val converted = ArrayList<AkmTerritory>(territories.size)
		val indexById = HashMap<String, Int>()
		for (territory in territories) {
			val regions = territory.regions.mapNotNull { region -> AkmGrid.region(region.rings.map { it.points }, gridBits, width, height) }
			// A territory whose every outline collapses on the grid has no shape left to draw
			if (regions.isEmpty()) continue
			indexById[territory.territoryId] = converted.size
			val shape = AkmTerritory(territory.territoryId, regions, territory.label)
			val anchor = if (ownAnchors) {
				AkmGrid.point(territory.centroidX, territory.centroidY, gridBits, width, height)
			} else {
				AkmAnchors.of(shape, gridBits)
			}
			converted.add(shape.copy(anchor = anchor))
		}
		return Pair(converted, connections(edges, indexById))
	}

	/**
	 * Connections by territory index, one per pair of territories: version 1 allowed a pair to be listed twice or as
	 * two one-way edges, and both directions together are one bidirectional connection.
	 */
	private fun connections(edges: List<AkmV1Edge>, indexById: Map<String, Int>): List<AkmConnection> {
		val byPair = LinkedHashMap<Pair<Int, Int>, AkmConnection>()
		for (edge in edges) {
			val from = indexById[edge.sourceId] ?: continue
			val to = indexById[edge.targetId] ?: continue
			if (from == to) continue
			val pair = Pair(minOf(from, to), maxOf(from, to))
			val known = byPair[pair]
			val bothWays = edge.bidirectional || (known != null && (known.bidirectional || known.a != from))
			byPair[pair] = if (bothWays) AkmConnection(pair.first, pair.second, true) else AkmConnection(from, to, false)
		}
		return byPair.values.toList()
	}
}

/**
 * Converts a version 1 map to the current format.
 *
 * Outlines go onto the grid: each coordinate is rounded to the nearest grid unit, which at the default grid moves
 * a point by at most 1/32 of a pixel. Layers keep their names, z-orders, blend modes and opacity and cover the
 * canvas; a layer named `labels` becomes label art and every other one lies below the territories. PNG images are
 * written again, which removes anything but pixels from them and stores few-color images with a palette.
 *
 * Of the version 1 header, the name and the tool stamp become metadata. The id, the description and the creation
 * time have no place in the current format, and the authors are left out: a version 1 author is an id with no
 * issuer and a name frozen at export, and who a file credits is for its host to say. All of them remain readable
 * from the [AkmV1Map].
 */
object AkmV1Converter {

	@JvmStatic
	@JvmOverloads
	fun convert(v1: AkmV1Map, gridBits: Int = AkmMap.DEFAULT_GRID_BITS): AkmMap {
		val (territories, connections) = GridGeometry.convert(v1.canvasWidth, v1.canvasHeight, gridBits, v1.territories, v1.edges)
		val layers = v1.layers.map { layer ->
			val role = if (layer.name == "labels") LayerRole.LABEL_ART else LayerRole.BELOW
			val data = if (layer.imageFormat == ImageFormat.PNG) rewritePng(layer) else layer.imageData
			AkmLayer(role, layer.name, layer.zOrder, 0, 0, v1.canvasWidth, v1.canvasHeight, layer.imageFormat, AkmImage.Inline(data), layer.blendMode, layer.opacity)
		}
		return AkmMap(
			canvasWidth = v1.canvasWidth,
			canvasHeight = v1.canvasHeight,
			territories = territories,
			connections = connections,
			gridBits = gridBits,
			layers = layers,
			metadata = Metadata(
				name = v1.displayName.takeIf { it.isNotBlank() },
				generator = v1.editorVersion.takeIf { it.isNotBlank() },
			),
		)
	}

	/** Reads version 1 bytes and converts them. */
	@JvmStatic
	fun convert(v1Bytes: ByteArray): AkmMap = convert(AkmV1Decoder.decode(v1Bytes))

	private fun rewritePng(layer: AkmV1Layer): ByteArray =
		try {
			encodePng(decodePng(layer.imageData))
		} catch (e: FormatException) {
			throw e
		} catch (e: Exception) {
			throw FormatException(FormatError.BAD_IMAGE, "Layer '${layer.name}' holds a PNG that cannot be read: ${e.message}")
		}
}
