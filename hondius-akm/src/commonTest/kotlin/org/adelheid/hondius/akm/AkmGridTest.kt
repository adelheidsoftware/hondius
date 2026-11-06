package org.adelheid.hondius.akm

import org.adelheid.hondius.container.FormatError
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Every fraction below is a multiple of 1/64, which a float holds exactly on every target.
class AkmGridTest {

	@Test
	fun aCoordinateGoesToTheNearestGridPointAndStaysOnTheCanvas() {
		assertEquals(160, AkmGrid.toGrid(10f, 4, 100))
		assertEquals(160, AkmGrid.toGrid(10.015625f, 4, 100))
		assertEquals(161, AkmGrid.toGrid(10.046875f, 4, 100))
		assertEquals(10, AkmGrid.toGrid(10.25f, 0, 100))
		assertEquals(0, AkmGrid.toGrid(-3f, 4, 100))
		assertEquals(1600, AkmGrid.toGrid(250f, 4, 100))
		assertEquals(AkmPoint(8, 24), AkmGrid.point(0.5f, 1.5f, 4, 100, 80))
		assertEquals(10.0625f, AkmGrid.toPixels(161, 4))
		Fixtures.assertFormatError(FormatError.BAD_GEOMETRY) { AkmGrid.toGrid(Float.NaN, 4, 100) }
	}

	@Test
	fun anOutlineLosesItsRepeatedPointsAndIsTurnedTheRightWay() {
		// Runs the way a hole does; its fourth point rounds onto its third and its last onto its first
		val pixels = floatArrayOf(0f, 0f, 0f, 10f, 10f, 10f, 10.015625f, 10.015625f, 10f, 0f, 0.015625f, 0f)

		val hole = assertNotNull(AkmGrid.ring(pixels, 4, 100, 80, outline = false))
		assertContentEquals(intArrayOf(0, 0, 0, 160, 160, 160, 160, 0), hole.points)
		assertTrue(hole.signedArea2() < 0)

		val outline = assertNotNull(AkmGrid.ring(pixels, 4, 100, 80, outline = true))
		assertContentEquals(intArrayOf(160, 0, 160, 160, 0, 160, 0, 0), outline.points)
		assertTrue(outline.signedArea2() > 0)
	}

	@Test
	fun whatHasNoAreaOnTheGridIsDropped() {
		val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
		val speck = floatArrayOf(5f, 5f, 5.015625f, 5f, 5.015625f, 5.015625f)
		assertNull(AkmGrid.ring(speck, 4, 100, 80, outline = true))
		assertNull(AkmGrid.ring(floatArrayOf(0f, 0f, 10f, 0f, 20f, 0f), 4, 100, 80, outline = true))
		assertNull(AkmGrid.ring(floatArrayOf(0f, 0f, 10f, 0f), 4, 100, 80, outline = true))
		Fixtures.assertFormatError(FormatError.BAD_GEOMETRY) { AkmGrid.ring(floatArrayOf(0f, 0f, 10f), 4, 100, 80, outline = true) }

		// A hole that leaves nothing goes; an outline that leaves nothing takes its region with it
		assertEquals(1, assertNotNull(AkmGrid.region(listOf(square, speck), 4, 100, 80)).rings.size)
		assertNull(AkmGrid.region(listOf(speck, square), 4, 100, 80))
		assertNull(AkmGrid.region(emptyList(), 4, 100, 80))
	}

	@Test
	fun aRegionKeepsItsHolesTurnedAgainstItsOutline() {
		val outer = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
		val inner = floatArrayOf(2f, 2f, 8f, 2f, 8f, 8f, 2f, 8f)
		val region = assertNotNull(AkmGrid.region(listOf(outer, inner), 4, 100, 80))
		assertTrue(region.outline.signedArea2() > 0)
		assertTrue(region.holes.single().signedArea2() < 0)
		// The region is one a map accepts
		AkmMap(100, 80, listOf(AkmTerritory(Fixtures.id(1), listOf(region))))
	}

	@Test
	fun aRingReadsBackInPixelsExactly() {
		val ring = AkmRing(intArrayOf(1, 0, 161, 8, 3, 160))
		val pixels = AkmGrid.pixels(ring, 4)
		assertContentEquals(floatArrayOf(0.0625f, 0f, 10.0625f, 0.5f, 0.1875f, 10f), pixels)
		assertEquals(ring, AkmGrid.ring(pixels, 4, 100, 80, outline = true))
	}
}

class AkmAnchorsTest {
	private fun region(vararg rings: AkmRing) = AkmRegion(rings.toList())

	@Test
	fun aTerritorysOwnAnchorIsWhereItsLabelGoes() {
		val territory = AkmTerritory(Fixtures.id(1), listOf(region(Fixtures.square(0, 0, 1600))), anchor = AkmPoint(3, 4))
		assertEquals(AkmPoint(3, 4), AkmAnchors.of(territory, 4))
	}

	@Test
	fun withoutAnAnchorTheLargestRegionCarriesTheLabel() {
		val territory = AkmTerritory(Fixtures.id(1), listOf(region(Fixtures.square(0, 0, 160)), region(Fixtures.square(800, 800, 480))))
		assertEquals(AkmPoint(1040, 1040), AkmAnchors.of(territory, 4))
		Fixtures.assertFormatError(FormatError.BAD_GEOMETRY) { AkmAnchors.of(AkmTerritory(Fixtures.id(2), emptyList()), 4) }
	}

	@Test
	fun theLabelOfARegionAroundAHoleLiesInTheLand() {
		// A 100 pixel square around a 60 pixel hole: a band 20 pixels wide, whose center of mass is in the hole
		val pole = AkmAnchors.pole(region(Fixtures.square(0, 0, 1600), Fixtures.square(320, 320, 960).reversed()), 4)
		val toOutline = minOf(pole.x, pole.y, 1600 - pole.x, 1600 - pole.y)
		val dx = max(max(320 - pole.x, pole.x - 1280), 0)
		val dy = max(max(320 - pole.y, pole.y - 1280), 0)
		val toHole = sqrt((dx * dx + dy * dy).toDouble())
		// The best point is 10 pixels from both; the search is good to one pixel
		assertTrue(toOutline >= 160 - 16, "pole $pole is $toOutline grid units from the outline")
		assertTrue(toHole >= 160 - 16, "pole $pole is $toHole grid units from the hole")
	}

	@Test
	fun theLabelOfACrescentLiesInsideIt() {
		// A C: a spine on the left and two arms, each 20 pixels thick. Its center of mass lies in the opening.
		val crescent = AkmRing(intArrayOf(0, 0, 1600, 0, 1600, 320, 320, 320, 320, 1280, 1600, 1280, 1600, 1600, 0, 1600))
		assertTrue(crescent.signedArea2() > 0)
		val pole = AkmAnchors.pole(region(crescent), 4)
		assertTrue(pole.x in 0..1600 && pole.y in 0..1600)
		assertTrue(pole.x < 320 || pole.y < 320 || pole.y > 1280, "pole $pole lies in the opening")
	}

	@Test
	fun aCoarserGridIsSearchedToOnePixelToo() {
		assertEquals(AkmPoint(50, 50), AkmAnchors.pole(region(Fixtures.square(0, 0, 100)), 0))
	}
}
