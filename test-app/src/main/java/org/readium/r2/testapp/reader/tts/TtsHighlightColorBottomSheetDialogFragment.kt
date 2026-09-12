/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.reader.tts

import android.app.Dialog
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.readium.r2.testapp.R
import org.readium.r2.testapp.reader.ReaderViewModel
import org.readium.r2.testapp.utils.compose.ComposeBottomSheetDialogFragment
import org.readium.r2.testapp.utils.extensions.asStateWhenStarted

class TtsHighlightColorBottomSheetDialogFragment : ComposeBottomSheetDialogFragment() {

    private val viewModel: ReaderViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            // Keep the page readable so the highlight color change is visible immediately.
            window?.setDimAmount(0.1f)
        }

    @Composable
    override fun Content() {
        val tts = checkNotNull(viewModel.tts)
        val selected by tts.highlightColor.asStateWhenStarted()

        TtsHighlightColorPicker(
            selected = selected,
            onSelect = { color ->
                tts.setHighlightColor(color)
                dismiss()
            },
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )
    }
}

@Composable
fun TtsHighlightColorPicker(
    selected: TtsHighlightColor,
    onSelect: (TtsHighlightColor) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.tts_highlight_color_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TtsHighlightColor.entries.forEach { color ->
                TtsHighlightColorSwatch(
                    color = color,
                    selected = color == selected,
                    onClick = { onSelect(color) }
                )
            }
        }
    }
}

@Composable
private fun TtsHighlightColorSwatch(
    color: TtsHighlightColor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(color.label)
    val ringColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics {
                this.selected = selected
                contentDescription = label
            }
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = ringColor,
                shape = CircleShape
            )
            .padding(5.dp)
            .clip(CircleShape)
            .background(Color(color.tint))
            .clickable(
                role = Role.RadioButton,
                onClick = onClick
            )
    )
}
