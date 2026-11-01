package com.roll0the1dice.voxread.desktop.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roll0the1dice.voxread.desktop.data.model.Book
import com.roll0the1dice.voxread.desktop.tts.EdgeTtsConstants
import com.roll0the1dice.voxread.desktop.tts.EdgeVoiceOption
import com.roll0the1dice.voxread.desktop.tts.TtsAudioProcessor
import com.roll0the1dice.voxread.desktop.tts.TtsHighlightColor
import com.roll0the1dice.voxread.desktop.ui.theme.PlayerBackground
import com.roll0the1dice.voxread.desktop.ui.theme.PrimaryBlue
import com.roll0the1dice.voxread.desktop.ui.theme.TextPrimary
import com.roll0the1dice.voxread.desktop.ui.theme.TextSecondary
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

@Composable
fun TtsPlayerOverlay(
    book: Book,
    chapterTitle: String,
    isPlaying: Boolean,
    progressFraction: Float,
    currentVoice: String,
    currentSpeed: Double,
    currentGain: Double = EdgeTtsConstants.DEFAULT_GAIN_FACTOR,
    drcEnabled: Boolean = EdgeTtsConstants.DEFAULT_DRC_ENABLED,
    currentHighlightColor: TtsHighlightColor,
    sleepTimerMinutes: Int?,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onVoiceChange: (String) -> Unit,
    onSpeedChange: (Double) -> Unit,
    onGainChange: (Double) -> Unit = {},
    onDrcChange: (Boolean) -> Unit = {},
    onHighlightColorChange: (TtsHighlightColor) -> Unit,
    onSleepTimerChange: (Int?) -> Unit,
    onStopTts: () -> Unit,
    onDismiss: () -> Unit,
    onOutline: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showTimerDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showGainDialog by remember { mutableStateOf(false) }
    var showVoiceDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf<Float?>(null) }

    val coverBitmap = remember(book.cover) {
        book.cover?.let { path ->
            val file = File(path)
            if (file.exists()) {
                try {
                    ImageIO.read(file)?.toComposeImageBitmap()
                } catch (_: Throwable) {
                    null
                }
            } else null
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = PlayerBackground,
        contentColor = TextSecondary
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val coverHeight = (maxHeight * 0.28f).coerceIn(120.dp, 240.dp)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "收起播放器",
                            modifier = Modifier.size(32.dp),
                            tint = TextPrimary
                        )
                    }

                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = Color.White,
                            shadowElevation = 2.dp
                        ) {
                            Text(
                                text = "AI 阅读",
                                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 15.sp
                            )
                        }
                    }

                    IconButton(onClick = { showVoiceDialog = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "朗读设置",
                            tint = TextPrimary
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Cover Image
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    shadowElevation = 8.dp,
                    color = Color.White
                ) {
                    if (coverBitmap != null) {
                        Image(
                            bitmap = coverBitmap,
                            contentDescription = book.displayName,
                            modifier = Modifier
                                .height(coverHeight)
                                .width(coverHeight * 0.70f)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .height(coverHeight)
                                .width(coverHeight * 0.70f)
                                .background(Color.White)
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = book.displayName,
                                textAlign = TextAlign.Center,
                                color = TextSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Chapter Title
                Text(
                    text = if (chapterTitle.isNotBlank()) chapterTitle else book.displayName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                )

                Spacer(Modifier.height(28.dp))

                // Quick Action Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    PlayerActionItem(
                        icon = Icons.Default.Timer,
                        label = sleepTimerMinutes?.let { "${it}分钟" } ?: "定时关闭",
                        onClick = { showTimerDialog = true }
                    )
                    PlayerActionItem(
                        icon = Icons.Default.Headphones,
                        label = "音色",
                        onClick = { showVoiceDialog = true }
                    )
                    PlayerActionItem(
                        icon = Icons.Default.Speed,
                        label = "语速 ${formatSpeed(currentSpeed)}x",
                        onClick = { showSpeedDialog = true }
                    )
                    PlayerActionItem(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        label = "增益 ${(currentGain * 100).roundToInt()}%",
                        onClick = { showGainDialog = true }
                    )
                    PlayerActionItem(
                        icon = Icons.Default.Palette,
                        label = currentHighlightColor.label,
                        onClick = { showColorDialog = true }
                    )
                }

                Spacer(Modifier.height(28.dp))

                // Progress Bar & Percentage
                val currentFraction = dragProgress ?: progressFraction
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "朗读进度", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.weight(1f))
                    Text(text = "${(currentFraction * 100).toInt()}%", fontSize = 12.sp, color = TextSecondary)
                }

                Slider(
                    value = currentFraction.coerceIn(0f, 1f),
                    onValueChange = { dragProgress = it },
                    onValueChangeFinished = {
                        dragProgress?.let { onSeek(it) }
                        dragProgress = null
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = PrimaryBlue,
                        activeTrackColor = PrimaryBlue,
                        inactiveTrackColor = Color(0xFFD1D5DB)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(20.dp))

                // Playback Control Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerActionItem(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        label = "原文",
                        onClick = onDismiss
                    )

                    IconButton(
                        onClick = onPrevious,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "上一句",
                            modifier = Modifier.size(36.dp),
                            tint = TextPrimary
                        )
                    }

                    // Main Play/Pause Button (76dp)
                    Surface(
                        shape = CircleShape,
                        color = PrimaryBlue,
                        shadowElevation = 6.dp,
                        modifier = Modifier.size(76.dp)
                    ) {
                        IconButton(
                            onClick = onPlayPause,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "暂停" else "播放",
                                modifier = Modifier.size(44.dp),
                                tint = Color.White
                            )
                        }
                    }

                    IconButton(
                        onClick = onNext,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "下一句",
                            modifier = Modifier.size(36.dp),
                            tint = TextPrimary
                        )
                    }

                    PlayerActionItem(
                        icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                        label = "目录",
                        onClick = onOutline
                    )
                }

                Spacer(Modifier.height(28.dp))

                // Bottom Floating Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    shadowElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = book.displayName,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = TextPrimary,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "返回原文后继续收听",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        TextButton(
                            onClick = {
                                onStopTts()
                                onDismiss()
                            }
                        ) {
                            Text("结束朗读", color = PrimaryBlue, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }

        // Timer Dialog
        if (showTimerDialog) {
            val timerOptions = listOf(null to "关闭定时", 15 to "15分钟", 30 to "30分钟", 60 to "60分钟")
            AlertDialog(
                onDismissRequest = { showTimerDialog = false },
                title = { Text("定时关闭", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        timerOptions.forEach { (minutes, label) ->
                            TextButton(
                                onClick = {
                                    onSleepTimerChange(minutes)
                                    showTimerDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    label,
                                    color = if (sleepTimerMinutes == minutes) PrimaryBlue else TextPrimary,
                                    fontWeight = if (sleepTimerMinutes == minutes) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showTimerDialog = false }) { Text("取消") }
                }
            )
        }

        // Speed Dialog
        if (showSpeedDialog) {
            val speeds = listOf(0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
            AlertDialog(
                onDismissRequest = { showSpeedDialog = false },
                title = { Text("语速设置", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        speeds.forEach { speed ->
                            TextButton(
                                onClick = {
                                    onSpeedChange(speed)
                                    showSpeedDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "${formatSpeed(speed)}x",
                                    color = if (currentSpeed == speed) PrimaryBlue else TextPrimary,
                                    fontWeight = if (currentSpeed == speed) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSpeedDialog = false }) { Text("取消") }
                }
            )
        }

        // Audio Gain & DRC Dialog
        if (showGainDialog) {
            val presets = listOf(
                1.0 to "100% 原始",
                1.5 to "150% 增强",
                2.0 to "200% 视频级",
                2.5 to "250% 强力",
                3.0 to "300% 极响"
            )
            val effectiveDb = TtsAudioProcessor.effectiveDecibels(currentGain, drcEnabled)
            val dbStr = if (effectiveDb >= 0) "+%.1f dB".format(effectiveDb) else "%.1f dB".format(effectiveDb)
            val ssmlVol = TtsAudioProcessor.gainToSsmlVolume(currentGain)

            AlertDialog(
                onDismissRequest = { showGainDialog = false },
                title = { Text("音量增益与响度增强", fontWeight = FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "增益倍率: ${(currentGain * 100).roundToInt()}%",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "等效 $dbStr · SSML $ssmlVol",
                                color = PrimaryBlue,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        Slider(
                            value = currentGain.toFloat().coerceIn(0.5f, 3.0f),
                            onValueChange = { newVal ->
                                val stepped = (newVal * 20f).roundToInt() / 20.0
                                onGainChange(stepped.coerceIn(0.5, 3.0))
                            },
                            valueRange = 0.5f..3.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = PrimaryBlue,
                                activeTrackColor = PrimaryBlue,
                                inactiveTrackColor = Color(0xFFD1D5DB)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        // Preset chips
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            presets.forEach { (gainVal, label) ->
                                val selected = kotlin.math.abs(currentGain - gainVal) < 0.04
                                FilterChip(
                                    selected = selected,
                                    onClick = { onGainChange(gainVal) },
                                    label = {
                                        Text(
                                            label,
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = Color(0xFFE5E7EB))
                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "动态范围压缩 (DRC 响度均衡)",
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "自动拉升轻音细语并防爆音限幅，对齐流媒体视频（-14 LUFS）",
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = drcEnabled,
                                onCheckedChange = { onDrcChange(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = PrimaryBlue
                                )
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showGainDialog = false }) { Text("完成") }
                }
            )
        }

        // Voice Dialog
        if (showVoiceDialog) {
            AlertDialog(
                onDismissRequest = { showVoiceDialog = false },
                title = { Text("选择发音人", fontWeight = FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                        EdgeTtsConstants.BUILT_IN_VOICES.forEach { voice ->
                            TextButton(
                                onClick = {
                                    onVoiceChange(voice.shortName)
                                    showVoiceDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        voice.displayName,
                                        fontWeight = if (currentVoice == voice.shortName) FontWeight.Bold else FontWeight.Medium,
                                        color = if (currentVoice == voice.shortName) PrimaryBlue else TextPrimary
                                    )
                                    Text(voice.description, fontSize = 11.sp, color = TextSecondary)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showVoiceDialog = false }) { Text("取消") }
                }
            )
        }

        // Highlight Color Dialog
        if (showColorDialog) {
            AlertDialog(
                onDismissRequest = { showColorDialog = false },
                title = { Text("高亮跟随颜色", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        TtsHighlightColor.entries.forEach { color ->
                            TextButton(
                                onClick = {
                                    onHighlightColorChange(color)
                                    showColorDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(color.composeColor)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        color.label,
                                        fontWeight = if (currentHighlightColor == color) FontWeight.Bold else FontWeight.Normal,
                                        color = if (currentHighlightColor == color) PrimaryBlue else TextPrimary
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showColorDialog = false }) { Text("取消") }
                }
            )
        }
    }
}

@Composable
private fun PlayerActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp), tint = TextSecondary)
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
    }
}

private fun formatSpeed(speed: Double): String =
    if (speed == speed.toLong().toDouble()) String.format("%.0f", speed)
    else String.format("%.2f", speed).trimEnd('0').trimEnd('.')
