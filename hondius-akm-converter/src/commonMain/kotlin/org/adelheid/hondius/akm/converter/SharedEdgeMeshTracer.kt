package org.adelheid.hondius.akm.converter

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Traces boundary contours on a label grid using single-pass multi-label edge extraction.
 * Adjacent territories sharing a border receive the same point sequence (reversed),
 * guaranteeing topological consistency after simplification.
 *
 * Algorithm:
 * 1. Pad the label grid with a sentinel so edge territories close properly.
 * 2. Extract all boundary edge segments between differently-labeled cells.
 * 3. Group edges by label pair and chain consecutive segments by shared endpoints.
 * 4. Simplify each chain with the supplied [LineSimplifier].
 * 5. Optionally smooth each chain with Taubin smoothing.
 * 6. Assign chains to territories and stitch into closed rings.
 */
object SharedEdgeMeshTracer {

	private const val BORDER_LABEL = -2

	/**
	 * Traces all territory boundaries from the label grid in a single pass.
	 *
	 * @param labels the label grid (`labels[y][x]`)
	 * @param width grid width
	 * @param height grid height
	 * @param simplifier line simplification strategy, or `null` to skip simplification
	 * @param smoothIterations Taubin smoothing iterations, or `<= 0` to skip
	 */
	fun trace(
		labels: Array<IntArray>,
		width: Int,
		height: Int,
		simplifier: LineSimplifier?,
		smoothIterations: Int,
	): Map<Int, List<List<FloatArray>>> {
		// 1. Pad with sentinel
		val padW = width + 2
		val padH = height + 2
		val pad = Array(padH) { IntArray(padW) { BORDER_LABEL } }
		for (y in 0 until height) {
			labels[y].copyInto(pad[y + 1], 1)
		}

		// 2. Extract boundary edge segments, grouped by label pair.
		val adjByPair = HashMap<Long, HashMap<Long, MutableList<Long>>>()

		for (cy in 0 until padH) {
			for (cx in 0 until padW) {
				val lbl = pad[cy][cx]
				if (cx + 1 < padW && pad[cy][cx + 1] != lbl) {
					val pk = pairKey(lbl, pad[cy][cx + 1])
					addEdge(adjByPair, pk, ckey(cx + 1, cy), ckey(cx + 1, cy + 1))
				}
				if (cy + 1 < padH && pad[cy + 1][cx] != lbl) {
					val pk = pairKey(lbl, pad[cy + 1][cx])
					addEdge(adjByPair, pk, ckey(cx, cy + 1), ckey(cx + 1, cy + 1))
				}
			}
		}

		// 3. Walk the adjacency graph per label pair.
		val chains = ArrayList<Chain>()
		for ((key, adj) in adjByPair) {
			val labelA = (key shr 32).toInt()
			val labelB = (key and 0xFFFFFFFFL).toInt()
			val traced = walkChains(adj)
			for (pts in traced) {
				if (pts.size >= 2) {
					val leftLabel = leftLabelOf(pts[0], pts[1], pad, padW, padH)
					val aIsLeft = leftLabel == labelA
					chains.add(Chain(labelA, labelB, aIsLeft, pts))
				}
			}
		}

		// Collect chain junction coordinates so interior points at same location can be pinned.
		val junctionCoords = HashSet<Long>()
		for (c in chains) {
			if (c.points.isNotEmpty()) {
				junctionCoords.add(pointKey(c.points.first()))
				junctionCoords.add(pointKey(c.points.last()))
			}
		}

		// 4. Simplify each chain (skip border-touching chains).
		if (simplifier != null) {
			for (i in chains.indices) {
				val c = chains[i]
				if (c.labelA == BORDER_LABEL || c.labelB == BORDER_LABEL) continue
				val pinned = computePins(c.points, width, height, junctionCoords)
				val s = simplifier.simplify(c.points, pinned)
				if (s.size >= 2) {
					chains[i] = Chain(c.labelA, c.labelB, c.aIsLeft, s)
				}
			}
		}

		// 4b. Smooth each chain (skip border-touching chains).
		if (smoothIterations > 0) {
			for (i in chains.indices) {
				val c = chains[i]
				if (c.labelA == BORDER_LABEL || c.labelB == BORDER_LABEL) continue
				var pinned = computePins(c.points, width, height, junctionCoords)
				pinned = pinStructuralCorners(c.points, pinned, 100f, 15f)
				val s = PolygonSimplifier.smoothOpenTaubin(c.points, smoothIterations, pinned)
				chains[i] = Chain(c.labelA, c.labelB, c.aIsLeft, s)
			}
		}

		// 5. Assign chains to territories and stitch into closed rings.
		val byLabel = HashMap<Int, MutableList<Oriented>>()
		for (c in chains) {
			val leftLbl = if (c.aIsLeft) c.labelA else c.labelB
			val rightLbl = if (c.aIsLeft) c.labelB else c.labelA
			if (leftLbl != BORDER_LABEL) {
				byLabel.getOrPut(leftLbl) { ArrayList() }.add(Oriented(c, false))
			}
			if (rightLbl != BORDER_LABEL) {
				byLabel.getOrPut(rightLbl) { ArrayList() }.add(Oriented(c, true))
			}
		}

		val result = HashMap<Int, List<List<FloatArray>>>()
		for ((label, oriented) in byLabel) {
			val rings = stitchRings(oriented)
			if (rings.isNotEmpty()) result[label] = rings
		}
		return result
	}

	/** Pin mask for chain points. Returns `null` if no interior points need pinning. */
	private fun computePins(
		points: List<FloatArray>,
		width: Int,
		height: Int,
		junctionCoords: Set<Long>,
	): BooleanArray? {
		val pinned = BooleanArray(points.size)
		var anyPinned = false
		for (i in points.indices) {
			val x = points[i][0]
			val y = points[i][1]
			if (x <= 0f || x >= width - 1f || y <= 0f || y >= height - 1f) {
				pinned[i] = true
				anyPinned = true
			} else if (i > 0 && i < points.size - 1 && pointKey(points[i]) in junctionCoords) {
				pinned[i] = true
				anyPinned = true
			}
		}
		return if (anyPinned) pinned else null
	}

	/**
	 * Pin interior vertices that are structural corners: sharp angle (below threshold) where
	 * BOTH adjacent segments are long (above minimum length). Distinguishes true corners from
	 * raster noise.
	 */
	private fun pinStructuralCorners(
		points: List<FloatArray>,
		existing: BooleanArray?,
		minAngleDeg: Float,
		minSegmentLength: Float,
	): BooleanArray? {
		if (points.size < 3) return existing
		val minLenSq = minSegmentLength * minSegmentLength
		val pinned = existing ?: BooleanArray(points.size)
		var anyNew = false
		for (i in 1 until points.size - 1) {
			if (pinned[i]) continue
			val a = points[i - 1]
			val b = points[i]
			val c = points[i + 1]
			val bax = a[0] - b[0]
			val bay = a[1] - b[1]
			val bcx = c[0] - b[0]
			val bcy = c[1] - b[1]
			val lenBASq = bax * bax + bay * bay
			val lenBCSq = bcx * bcx + bcy * bcy
			if (lenBASq < minLenSq || lenBCSq < minLenSq) continue
			val dot = bax * bcx + bay * bcy
			val mag = sqrt(lenBASq * lenBCSq)
			if (mag < 1e-6f) continue
			val angle = (acos(max(-1f, min(1f, dot / mag))).toDouble() * 180.0 / PI).toFloat()
			if (angle < minAngleDeg) {
				pinned[i] = true
				anyNew = true
			}
		}
		return if (existing != null || anyNew) pinned else null
	}

	/** Long key for a point: high 32 bits = x bits, low 32 bits = y bits. Exact float comparison. */
	private fun pointKey(pt: FloatArray): Long =
		(pt[0].toRawBits().toLong() shl 32) or (pt[1].toRawBits().toLong() and 0xFFFFFFFFL)

	private fun pairKey(a: Int, b: Int): Long {
		val mn = min(a, b)
		val mx = max(a, b)
		return (mn.toLong() shl 32) or (mx.toLong() and 0xFFFFFFFFL)
	}

	private fun ckey(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
	private fun ckeyX(k: Long): Int = (k shr 32).toInt()
	private fun ckeyY(k: Long): Int = (k and 0xFFFFFFFFL).toInt()

	private fun addEdge(
		adjByPair: HashMap<Long, HashMap<Long, MutableList<Long>>>,
		pk: Long,
		v1: Long,
		v2: Long,
	) {
		val adj = adjByPair.getOrPut(pk) { HashMap() }
		adj.getOrPut(v1) { ArrayList() }.add(v2)
		adj.getOrPut(v2) { ArrayList() }.add(v1)
	}

	/** Walks all chains from the adjacency graph, consuming edges as we go. */
	private fun walkChains(adj: HashMap<Long, MutableList<Long>>): List<List<FloatArray>> {
		val result = ArrayList<List<FloatArray>>()
		while (true) {
			var start: Long? = null
			for ((k, v) in adj) {
				if (v.isNotEmpty()) {
					if (v.size % 2 != 0) {
						start = k
						break
					}
					if (start == null) start = k
				}
			}
			if (start == null) break

			val chain = ArrayList<FloatArray>()
			var cur = start
			chain.add(toReal(cur))

			while (true) {
				val nbrs = adj[cur]
				if (nbrs == null || nbrs.isEmpty()) break
				val next = nbrs.removeAt(nbrs.size - 1)
				adj[next]?.remove(cur)
				cur = next
				chain.add(toReal(cur))
				if (cur == start) break
			}

			if (chain.size > 2 && matches(chain.first(), chain.last())) {
				chain.removeAt(chain.size - 1)
			}
			if (chain.size >= 2) result.add(chain)
		}
		return result
	}

	private fun toReal(ck: Long): FloatArray = floatArrayOf(ckeyX(ck) - 1f, ckeyY(ck) - 1f)

	/** Determines which label is on the LEFT side of a directed edge (v1→v2) in padded-corner space. */
	private fun leftLabelOf(v1: FloatArray, v2: FloatArray, pad: Array<IntArray>, padW: Int, padH: Int): Int {
		val c1x = round(v1[0] + 1).toInt()
		val c1y = round(v1[1] + 1).toInt()
		val dx = v2[0] - v1[0]
		val dy = v2[1] - v1[1]

		val cellX: Int
		val cellY: Int
		when {
			dx > 0 -> { cellX = c1x; cellY = c1y - 1 }
			dx < 0 -> { cellX = c1x - 1; cellY = c1y }
			dy > 0 -> { cellX = c1x; cellY = c1y }
			else -> { cellX = c1x - 1; cellY = c1y - 1 }
		}

		return if (cellX in 0 until padW && cellY in 0 until padH) pad[cellY][cellX] else BORDER_LABEL
	}

	private data class Chain(val labelA: Int, val labelB: Int, val aIsLeft: Boolean, val points: List<FloatArray>)

	private data class Oriented(val chain: Chain, val reversed: Boolean) {
		fun startPt(): FloatArray = if (reversed) chain.points.last() else chain.points.first()
		fun ordered(): List<FloatArray> =
			if (!reversed) chain.points else chain.points.toMutableList().also { it.reverse() }
	}

	private fun stitchRings(chains: List<Oriented>): List<List<FloatArray>> {
		val rings = ArrayList<List<FloatArray>>()
		val used = BooleanArray(chains.size)

		for (i in chains.indices) {
			if (used[i]) continue
			used[i] = true

			val ring = ArrayList(chains[i].ordered())
			val ringStart = ring.first()

			for (iter in chains.indices) {
				val end = ring.last()
				if (ring.size > 2 && matches(end, ringStart)) break

				var found = false
				for (j in chains.indices) {
					if (used[j]) continue
					if (matches(end, chains[j].startPt())) {
						used[j] = true
						val next = chains[j].ordered()
						for (k in 1 until next.size) ring.add(next[k])
						found = true
						break
					}
				}
				if (!found) break
			}

			if (ring.size > 2 && matches(ring.first(), ring.last())) {
				ring.removeAt(ring.size - 1)
			}
			if (ring.size >= 3) rings.add(ring)
		}
		return rings
	}

	private fun matches(a: FloatArray, b: FloatArray): Boolean = a[0] == b[0] && a[1] == b[1]
}
