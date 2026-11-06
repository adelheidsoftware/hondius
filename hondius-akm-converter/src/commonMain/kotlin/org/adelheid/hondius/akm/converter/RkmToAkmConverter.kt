package org.adelheid.hondius.akm.converter

import org.adelheid.hondius.akm.AkmImage
import org.adelheid.hondius.akm.AkmLayer
import org.adelheid.hondius.akm.AkmMap
import org.adelheid.hondius.akm.ImageFormat
import org.adelheid.hondius.akm.LayerRole
import org.adelheid.hondius.akm.image.decodePng
import org.adelheid.hondius.akm.image.encodePng
import org.adelheid.hondius.akm.v1.AkmV1Edge
import org.adelheid.hondius.akm.v1.AkmV1Territory
import org.adelheid.hondius.akm.v1.GridGeometry
import org.adelheid.hondius.container.Author
import org.adelheid.hondius.container.Metadata
import org.adelheid.hondius.rkm.RkmMap
import org.adelheid.hondius.rkm.RkmTerritoryIdentity
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Converts a legacy [RkmMap] (raster-based) into an [AkmMap] (vector-based).
 *
 * The conversion pipeline:
 * 1. Decode the RKM base PNG into pixel data.
 * 2. Flood-fill from nuclei to assign pixels to territories.
 * 3. Trace shared boundary contours in a single pass.
 * 4. Simplify with Visvalingam-Whyatt and smooth with Taubin (per chain).
 * 5. Classify rings into exterior / hole and group into regions.
 * 6. Put the outlines onto the map's grid, and turn the RKM border set into connections.
 * 7. Give each territory its anchor: the point of its largest region that lies furthest from an edge.
 * 8. Split the base image into color-classified layers and embed each as PNG.
 *
 * @property simplifier line simplifier applied per chain
 * @property smoothIterations Taubin smoothing iterations per chain (`<= 0` to skip)
 */
class RkmToAkmConverter @JvmOverloads constructor(
	private val simplifier: LineSimplifier = VisvalingamWhyatt.of(DEFAULT_VW_MIN_AREA),
	private val smoothIterations: Int = DEFAULT_SMOOTH_ITERATIONS,
) {

	/**
	 * Converts an [RkmMap] to an [AkmMap].
	 *
	 * @param rkm the legacy raster map
	 * @param metadata what the map says about itself; by default the RKM name, the RKM author as a credit, and
	 *   this converter's stamp
	 * @param gridBits the grid the outlines are put on
	 */
	@OptIn(ExperimentalUuidApi::class)
	@JvmOverloads
	fun convert(rkm: RkmMap, metadata: Metadata = defaultMetadata(rkm), gridBits: Int = AkmMap.DEFAULT_GRID_BITS): AkmMap {
		val baseLayer = decodePng(rkm.basePng)
		val width = baseLayer.width
		val height = baseLayer.height

		// 1. Flood-fill from nuclei.
		val fill = FloodFill.fill(baseLayer, rkm.territories)

		// 2. Trace shared edge mesh in one pass.
		val allBoundaries = SharedEdgeMeshTracer.trace(fill.labels, width, height, simplifier, smoothIterations)
		val rawBoundariesByIdentity = HashMap<RkmTerritoryIdentity, List<List<FloatArray>>>()
		for ((identity, label) in fill.identityToIndex) {
			rawBoundariesByIdentity[identity] = allBoundaries[label] ?: emptyList()
		}

		// 3. Process each territory.
		val identityToUuid = HashMap<RkmTerritoryIdentity, String>()
		val territories = ArrayList<AkmV1Territory>(fill.identityToIndex.size)

		for ((identity, _) in fill.identityToIndex) {
			val territoryId = Uuid.random().toString()
			identityToUuid[identity] = territoryId

			val rawBoundaries = rawBoundariesByIdentity[identity] ?: emptyList()
			val filtered = rawBoundaries.filter { it.size >= 3 }

			val regions = RingClassifier.classify(filtered)

			// A territory with no region has no shape to draw, and a map holds none without one. The traced
			// outlines carry no label point; the grid conversion finds one.
			if (regions.isNotEmpty()) {
				territories.add(AkmV1Territory(territoryId, identity.rawValue, 0f, 0f, regions))
			}
		}

		// 4. The adjacency graph (RKM borders are always bidirectional), then both onto the grid.
		val edges = ArrayList<AkmV1Edge>(rkm.borders.size)
		for (border in rkm.borders) {
			val sourceId = identityToUuid[border.source]
			val targetId = identityToUuid[border.target]
			if (sourceId != null && targetId != null && sourceId != targetId) {
				edges.add(AkmV1Edge(sourceId, targetId, bidirectional = true))
			}
		}
		val (gridTerritories, connections) = GridGeometry.convert(width, height, gridBits, territories, edges, ownAnchors = false)

		// 5. Split the base layer into color-classified layers and embed PNGs.
		val splitResult = BaseLayerSplitter.split(baseLayer)
		val layers = ArrayList<AkmLayer>()
		for (name in SPLIT_LAYERS) {
			splitResult.layers[name]?.let { image ->
				layers.add(layer(LayerRole.BELOW, name, width, height, encodePng(image)))
			}
		}
		layers.add(layer(LayerRole.LABEL_ART, LAYER_LABELS, width, height, encodePng(decodePng(rkm.textPng))))

		return AkmMap(
			canvasWidth = width,
			canvasHeight = height,
			territories = gridTerritories,
			connections = connections,
			gridBits = gridBits,
			layers = layers,
			metadata = metadata,
		)
	}

	/** A canvas-sized layer under its standard name, at that name's conventional z-order. */
	private fun layer(role: LayerRole, name: String, width: Int, height: Int, png: ByteArray): AkmLayer =
		AkmLayer(role, name, AkmMap.STANDARD_LAYERS.getValue(name), 0, 0, width, height, ImageFormat.PNG, AkmImage.Inline(png))

	companion object {
		const val DEFAULT_VW_MIN_AREA = 1.8f
		const val DEFAULT_SMOOTH_ITERATIONS = 2

		/** The tool stamp a converted map carries. */
		const val GENERATOR: String = "hondius-akm-converter/1.0"

		private const val LAYER_LABELS = "labels"

		private val SPLIT_LAYERS = listOf(
			BaseLayerSplitter.LAYER_WATER,
			BaseLayerSplitter.LAYER_LAND,
			BaseLayerSplitter.LAYER_CONNECTIONS,
			BaseLayerSplitter.LAYER_TERRITORIES,
			BaseLayerSplitter.LAYER_BORDERS,
		)

		/** What a converted map says about itself unless the caller says otherwise. */
		@JvmStatic
		fun defaultMetadata(rkm: RkmMap): Metadata =
			Metadata(name = rkm.displayName, generator = GENERATOR, authors = listOf(Author.Credit(rkm.author)))
	}
}
