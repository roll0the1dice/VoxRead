package org.readium.r2.testapp.reader

import android.provider.Settings
import android.view.Window
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.readium.r2.testapp.R
import org.readium.r2.testapp.reader.tts.TtsViewModel
import org.readium.r2.testapp.utils.extensions.asStateWhenStarted

private val ToolbarInk = Color(0xFF60656E)

@Composable
internal fun ReaderListenButton(model: TtsViewModel, onClick: () -> Unit) {
    val speaking by model.showControls.asStateWhenStarted()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(shape = CircleShape, color = ToolbarInk, contentColor = Color.White) {
            IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
                Icon(
                    painterResource(if (speaking) R.drawable.play_arrow else R.drawable.ic_baseline_headphones_24),
                    stringResource(R.string.epubactivity_read_aloud_start),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

@Composable
internal fun ReaderBottomToolbar(
    showSettings: Boolean,
    showTheme: Boolean,
    onOutline: () -> Unit,
    onProgress: () -> Unit,
    onTheme: () -> Unit,
    onBrightness: () -> Unit,
    onSettings: () -> Unit,
    showBrightness: Boolean,
    window: Window,
) {
    Surface(color = Color.White, contentColor = ToolbarInk) {
        Column {
            if (showBrightness) {
                var brightness by remember(window) {
                    val current = window.attributes.screenBrightness.takeIf { it >= 0f }
                        ?: (Settings.System.getInt(window.context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                    mutableFloatStateOf(current.coerceIn(0.01f, 1f))
                }
                val description = stringResource(R.string.reader_brightness)
                Slider(
                    value = brightness,
                    onValueChange = { value ->
                        brightness = value
                        window.attributes = window.attributes.apply { screenBrightness = value }
                    },
                    valueRange = 0.01f..1f,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                        .semantics { contentDescription = description },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarButton(R.drawable.ic_outline_menu_24, R.string.epubactivity_book_nav_options, onOutline)
                ToolbarButton(R.drawable.ic_reader_compass, R.string.reading_progress_slider, onProgress)
                if (showTheme) ToolbarButton(R.drawable.ic_reader_theme, R.string.reader_theme, onTheme)
                ToolbarButton(R.drawable.ic_reader_brightness, R.string.reader_brightness, onBrightness)
                if (showSettings) ToolbarButton(R.drawable.ic_outline_format_size_24, R.string.epubactivity_settings, onSettings)
            }
        }
    }
}

@Composable
private fun ToolbarButton(icon: Int, description: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(icon), stringResource(description), modifier = Modifier.size(28.dp))
    }
}
