package org.readium.r2.testapp.reader.tts

import android.graphics.Bitmap
import java.math.BigDecimal
import java.math.RoundingMode
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.testapp.R
import org.readium.r2.testapp.reader.indexForProgressFraction
import org.readium.r2.testapp.reader.readingProgress
import org.readium.r2.testapp.utils.extensions.asStateWhenStarted

private val PlayerBackground = Color(0xFFF4F5F7)
private val PlayerInk = Color(0xFF60656E)
private val PlayerBlue = Color(0xFF00A5F5)

@OptIn(ExperimentalReadiumApi::class)
@Composable
internal fun TtsPlayerScreen(
    model: TtsViewModel,
    publication: Publication,
    positions: List<Locator>,
    fallbackLocator: Locator,
    onDismiss: () -> Unit,
    onOutline: () -> Unit,
    onPreferences: () -> Unit,
    onHighlightColor: () -> Unit,
) {
    val playing by model.isPlaying.asStateWhenStarted()
    val ready by model.showControls.asStateWhenStarted()
    val location by model.position.asStateWhenStarted()
    val timer by model.sleepTimerMinutes.asStateWhenStarted()
    val preferences by model.preferencesModel.editor.asStateWhenStarted()
    val editor = preferences as? TtsPreferencesEditor
    val progress = readingProgress(location ?: fallbackLocator, positions.size)
    var showTimer by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val cover by produceState<Bitmap?>(null, publication) {
        value = withContext(Dispatchers.IO) { publication.cover() }
    }
    val title = publication.metadata.title.orEmpty()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onDismiss)
        Surface(modifier = Modifier.fillMaxSize(), color = PlayerBackground, contentColor = PlayerInk) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val coverHeight = (maxHeight * 0.29f).coerceIn(120.dp, 240.dp)
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(painterResource(R.drawable.ic_player_collapse), "收起播放器", Modifier.size(28.dp))
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Surface(shape = RoundedCornerShape(24.dp), color = Color.White) {
                                Text("AI 阅读", Modifier.padding(horizontal = 32.dp, vertical = 10.dp),
                                    fontWeight = FontWeight.Bold, color = Color(0xFF252A32))
                            }
                        }
                        IconButton(onClick = onPreferences) {
                            Icon(painterResource(R.drawable.settings), "朗读设置")
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Surface(shape = RoundedCornerShape(2.dp), shadowElevation = 8.dp) {
                        if (cover != null) {
                            Image(cover!!.asImageBitmap(), title, Modifier.height(coverHeight).width(coverHeight * 0.70f),
                                contentScale = ContentScale.Fit)
                        } else {
                            Box(Modifier.height(coverHeight).width(coverHeight * 0.70f).background(Color.White),
                                contentAlignment = Alignment.Center) {
                                Text(title, Modifier.padding(16.dp), textAlign = TextAlign.Center, color = PlayerInk)
                            }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    key(progress.chapterTitle ?: title) {
                        Text(progress.chapterTitle ?: title, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            color = Color(0xFF252A32), maxLines = 1, softWrap = false,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
                    }
                    Spacer(Modifier.height(32.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        PlayerAction(R.drawable.ic_player_timer, timer?.let { "${it}分钟" } ?: "定时关闭", { showTimer = true })
                        PlayerAction(R.drawable.ic_baseline_headphones_24, "音色", onPreferences)
                        PlayerAction(R.drawable.ic_reader_compass,
                            "语速 ${formatSpeechSpeed(editor?.speed?.effectiveValue ?: 1.0)}x", { showSpeed = true })
                        PlayerAction(R.drawable.palette, "高亮颜色", onHighlightColor)
                    }
                    Spacer(Modifier.height(28.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("朗读进度", fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${((drag ?: progress.fraction.toFloat()) * 100).toInt()}%", fontSize = 12.sp)
                    }
                    Slider(
                        value = drag ?: progress.fraction.toFloat(),
                        onValueChange = { drag = it },
                        onValueChangeFinished = {
                            drag?.let { fraction ->
                                positions.getOrNull(indexForProgressFraction(fraction.toDouble(), positions.size))?.let(model::seekTo)
                            }
                            drag = null
                        },
                        enabled = ready && positions.isNotEmpty(),
                        colors = SliderDefaults.colors(thumbColor = PlayerBlue, activeTrackColor = PlayerBlue),
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        PlayerAction(R.drawable.ic_player_book, "原文", onDismiss)
                        IconButton(onClick = model::previous, enabled = ready) {
                            Icon(painterResource(R.drawable.skip_previous), "上一句", Modifier.size(32.dp))
                        }
                        Surface(shape = CircleShape, color = PlayerBlue, contentColor = Color.White) {
                            IconButton(onClick = { if (playing) model.pause() else model.play() },
                                enabled = ready, modifier = Modifier.size(76.dp)) {
                                Icon(painterResource(if (playing) R.drawable.pause else R.drawable.play_arrow),
                                    if (playing) "暂停" else "播放", Modifier.size(46.dp))
                            }
                        }
                        IconButton(onClick = model::next, enabled = ready) {
                            Icon(painterResource(R.drawable.skip_next), "下一句", Modifier.size(32.dp))
                        }
                        PlayerAction(R.drawable.ic_outline_menu_24, "目录", onOutline)
                    }
                    Spacer(Modifier.height(32.dp))
                    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = Color(0xFF252A32))
                                Text("返回原文后继续收听", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                            }
                            TextButton(onClick = { model.stop(); onDismiss() }) { Text("结束朗读", color = PlayerBlue) }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
        if (showTimer) {
            PlayerChoiceDialog("定时关闭", listOf("关闭定时", "15分钟", "30分钟", "60分钟"),
                onDismiss = { showTimer = false }) { index ->
                model.setSleepTimer(listOf(null, 15, 30, 60)[index]); showTimer = false
            }
        }
        if (showSpeed) {
            val speeds = listOf(0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
            PlayerChoiceDialog("语速", speeds.map { "${formatSpeechSpeed(it)}x" }, onDismiss = { showSpeed = false }) { index ->
                editor?.speed?.set(speeds[index]); model.preferencesModel.commit(); showSpeed = false
            }
        }
    }
}

@Composable
private fun PlayerAction(icon: Int, label: String, onClick: () -> Unit) {
    Column(Modifier.widthIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(icon), null, Modifier.size(26.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun PlayerChoiceDialog(title: String, choices: List<String>, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column { choices.forEachIndexed { index, label ->
            TextButton(onClick = { onSelect(index) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
        } } }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun formatSpeechSpeed(speed: Double): String =
    BigDecimal.valueOf(speed).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
