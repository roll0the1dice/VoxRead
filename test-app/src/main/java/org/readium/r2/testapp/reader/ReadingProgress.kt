/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.reader

import kotlin.math.roundToInt
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/**
 * Where the reader is in a publication.
 *
 * [fraction] is `position / total` when both are known, so the last page is 100%.
 * Otherwise it falls back to the locator's [Locator.Locations.totalProgression].
 */
data class ReadingProgress(
    val fraction: Double,
    val position: Int?,
    val total: Int?,
    val chapterTitle: String?,
) {
    val percent: Int
        get() = (fraction * 100).roundToInt().coerceIn(0, 100)
}

fun readingProgress(locator: Locator, positionCount: Int): ReadingProgress {
    val total = positionCount.takeIf { it > 0 }
    val position = locator.locations.position
    return ReadingProgress(
        fraction = displayFraction(position, locator.locations.totalProgression, positionCount),
        position = position,
        total = total,
        chapterTitle = locator.title?.takeIf { it.isNotBlank() },
    )
}

/**
 * Fraction stored with the book, using the same scale as [readingProgress] once
 * [positionCount] is known.
 */
fun Locator.withDisplayProgress(positionCount: Int): Locator {
    val position = locations.position ?: return this
    if (positionCount <= 0) return this
    val fraction = displayFraction(position, locations.totalProgression, positionCount)
    if (locations.totalProgression == fraction) return this
    return copy(locations = locations.copy(totalProgression = fraction))
}

/**
 * Reads a previously saved progression locator and returns its overall fraction.
 * Returns null when the book has never been opened.
 */
fun storedReadingFraction(progression: String?): Double? {
    if (progression.isNullOrBlank() || progression == "{}") return null
    val locator = try {
        Locator.fromJSON(JSONObject(progression))
    } catch (_: Exception) {
        null
    } ?: return null
    return locator.locations.totalProgression?.coerceIn(0.0, 1.0)
}

/**
 * Maps a 0–1 reading fraction onto a position list.
 *
 * The fraction is `position / count` (1-based), so the matching index is
 * `round(fraction * count) - 1`.
 */
fun indexForProgressFraction(fraction: Double, positionCount: Int): Int {
    if (positionCount <= 1) return 0
    val index = (fraction.coerceIn(0.0, 1.0) * positionCount).roundToInt() - 1
    return index.coerceIn(0, positionCount - 1)
}

private fun displayFraction(
    position: Int?,
    totalProgression: Double?,
    positionCount: Int,
): Double {
    if (position != null && positionCount > 0) {
        return (position.toDouble() / positionCount).coerceIn(0.0, 1.0)
    }
    return (totalProgression ?: 0.0).coerceIn(0.0, 1.0)
}
