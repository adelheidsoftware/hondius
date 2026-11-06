package org.adelheid.hondius.rkm

/**
 * A nucleus (seed) point inside an RKM territory. Used to flood-fill the base layer pixels
 * belonging to this territory during RKM → AKM conversion.
 */
data class RkmNucleus(val x: Int, val y: Int)
