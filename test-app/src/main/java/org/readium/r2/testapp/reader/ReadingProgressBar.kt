/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.readium.r2.testapp.R

/**
 * Chapter, percentage, and a slider that jumps to another position in the book.
 */
@Composable
fun ReadingProgressBar(
    progress: ReadingProgress,
    canSeek: Boolean,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val displayed = dragFraction ?: progress.fraction.toFloat()
    val percent = (displayed * 100).roundToInt().coerceIn(0, 100)
    val total = progress.total
    val previewPosition = total?.let { count ->
        dragFraction?.let { indexForProgressFraction(it.toDouble(), count) + 1 } ?: progress.position
    }
    val sliderDescription = stringResource(R.string.reading_progress_slider)

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = progress.chapterTitle.orEmpty(),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = stringResource(R.string.reading_progress_percent, percent),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Slider(
                modifier = Modifier.semantics { contentDescription = sliderDescription },
                value = displayed.coerceIn(0f, 1f),
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    val target = dragFraction
                    dragFraction = null
                    if (target != null) onSeek(target.toDouble())
                },
                enabled = canSeek,
            )
            if (total != null && previewPosition != null) {
                Text(
                    text = stringResource(
                        R.string.reading_progress_position,
                        previewPosition,
                        total,
                    ),
                    modifier = Modifier.align(Alignment.End),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
