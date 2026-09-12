/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.reader.tts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.readium.r2.testapp.R
import org.readium.r2.testapp.utils.extensions.asStateWhenStarted

/**
 * TTS controls bar displayed at the bottom of the screen when speaking a publication.
 */
@Composable
fun TtsControls(
    model: TtsViewModel,
    onPreferences: () -> Unit,
    onHighlightColor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showControls by model.showControls.asStateWhenStarted()
    val isPlaying by model.isPlaying.asStateWhenStarted()
    val highlightColor by model.highlightColor.asStateWhenStarted()

    if (showControls) {
        TtsControls(
            playing = isPlaying,
            highlightColor = highlightColor,
            onPlayPause = { if (isPlaying) model.pause() else model.play() },
            onStop = model::stop,
            onPrevious = model::previous,
            onNext = model::next,
            onPreferences = onPreferences,
            onHighlightColor = onHighlightColor,
            modifier = modifier
        )
    }
}

@Composable
fun TtsControls(
    playing: Boolean,
    highlightColor: TtsHighlightColor,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPreferences: () -> Unit,
    onHighlightColor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val largeButtonModifier = Modifier.size(40.dp)

            IconButton(onClick = onPrevious) {
                Icon(
                    painter = painterResource(id = R.drawable.skip_previous),
                    contentDescription = stringResource(R.string.tts_previous),
                )
            }

            IconButton(
                onClick = onPlayPause
            ) {
                Icon(
                    painter = if (playing) {
                        painterResource(id = R.drawable.pause)
                    } else {
                        painterResource(id = R.drawable.play_arrow)
                    },
                    contentDescription = stringResource(
                        if (playing) {
                            R.string.tts_pause
                        } else {
                            R.string.tts_play
                        }
                    ),
                    modifier = Modifier.then(largeButtonModifier)
                )
            }
            IconButton(
                onClick = onStop
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.stop),
                    contentDescription = stringResource(R.string.tts_stop),
                    modifier = Modifier.then(largeButtonModifier),
                )
            }
            IconButton(onClick = onNext) {
                Icon(
                    painter = painterResource(id = R.drawable.skip_next),
                    contentDescription = stringResource(R.string.tts_next),
                )
            }

            Spacer(modifier = Modifier.size(8.dp))

            IconButton(onClick = onHighlightColor) {
                Box {
                    Icon(
                        painter = painterResource(id = R.drawable.palette),
                        contentDescription = stringResource(R.string.tts_highlight_color),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(8.dp)
                            .border(0.5.dp, Color.Black.copy(alpha = 0.25f), CircleShape)
                            .clip(CircleShape)
                            .background(Color(highlightColor.tint))
                    )
                }
            }

            IconButton(onClick = onPreferences) {
                Icon(
                    painter = painterResource(id = R.drawable.settings),
                    contentDescription = stringResource(R.string.tts_settings),
                )
            }
        }
    }
}
