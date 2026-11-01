package com.roll0the1dice.voxread.desktop.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import com.roll0the1dice.voxread.desktop.data.db.BooksDao
import com.roll0the1dice.voxread.desktop.data.model.Book
import com.roll0the1dice.voxread.desktop.data.model.ReadingProgressionData
import com.roll0the1dice.voxread.desktop.tts.*
import com.roll0the1dice.voxread.desktop.ui.theme.PrimaryBlue
import com.roll0the1dice.voxread.desktop.ui.theme.TextPrimary
import com.roll0the1dice.voxread.desktop.ui.theme.TextSecondary
import com.roll0the1dice.voxread.desktop.util.Chapter
import com.roll0the1dice.voxread.desktop.util.EpubContentReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.ByteArrayInputStream
import java.io.File
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

private data class IndexedSentence(
    val text: String,
    val utteranceIndex: Int,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isCode: Boolean = false
)

private sealed class ReaderBlock {
    abstract val utteranceIndices: List<Int>

    data class Heading(
        val level: Int,
        val sentences: List<IndexedSentence>
    ) : ReaderBlock() {
        override val utteranceIndices: List<Int> = sentences.map { it.utteranceIndex }
    }

    data class Paragraph(
        val sentences: List<IndexedSentence>,
        val isQuote: Boolean = false,
        val bulletPrefix: String? = null
    ) : ReaderBlock() {
        override val utteranceIndices: List<Int> = sentences.map { it.utteranceIndex }
    }

    data class Code(
        val codeText: String,
        val utteranceIndex: Int? = null
    ) : ReaderBlock() {
        override val utteranceIndices: List<Int> = listOfNotNull(utteranceIndex)
    }

    data class MathFormula(
        val displayFormula: String,
        val spokenText: String,
        val utteranceIndex: Int
    ) : ReaderBlock() {
        override val utteranceIndices: List<Int> = listOf(utteranceIndex)
    }

    data class ImageItem(
        val bitmap: ImageBitmap,
        val caption: String? = null
    ) : ReaderBlock() {
        override val utteranceIndices: List<Int> = emptyList()
    }
}

private data class ParsedChapterData(
    val blocks: List<ReaderBlock>,
    val utterances: List<SpokenUtterance>
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun ReaderScreen(
    book: Book,
    booksDao: BooksDao? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val progressionWriteLock = remember(book.id) { Any() }
    val progressionWriteVersion = remember(book.id) { AtomicLong() }
    val contentReader = remember(book.href) { EpubContentReader(File(book.href)) }
    val chapters = contentReader.chapters

    val savedProgression = remember(book.id, book.progression) { book.parsedProgression }
    val initialChapterIdx = remember(book.id, savedProgression, chapters.size) {
        if (savedProgression == null || chapters.isEmpty()) {
            0
        } else {
            resolveSavedChapterIndex(
                chapters.map { it.href }, savedProgression.chapterHref, savedProgression.chapterIndex
            )
        }
    }

    val ttsPlayer = remember { EdgeTtsPlayer() }

    var currentChapterIndex by remember(book.id, book.progression) { mutableStateOf(initialChapterIdx) }
    var currentFontSize by remember(book.id, book.progression) { mutableStateOf(savedProgression?.fontSize ?: 18) }
    var currentTheme by remember(book.id, book.progression) { mutableStateOf(savedProgression?.theme ?: "light") } // "light", "sepia", "dark"

    var isTtsPlaying by remember { mutableStateOf(false) }
    var isTtsActive by remember { mutableStateOf(false) }
    var showTtsOverlay by remember { mutableStateOf(false) }
    var showOutlineDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showProgressBar by remember { mutableStateOf(false) }
    var showRestoredBanner by remember(book.id, book.progression) { mutableStateOf(savedProgression != null) }

    var ttsVoice by remember { mutableStateOf(EdgeTtsConstants.DEFAULT_VOICE) }
    var ttsSpeed by remember { mutableStateOf(1.0) }
    var ttsGain by remember(book.id, book.progression) { mutableStateOf(savedProgression?.ttsGain ?: EdgeTtsConstants.DEFAULT_GAIN_FACTOR) }
    var ttsDrc by remember(book.id, book.progression) { mutableStateOf(savedProgression?.ttsDrc ?: EdgeTtsConstants.DEFAULT_DRC_ENABLED) }
    var ttsHighlightColor by remember { mutableStateOf(TtsHighlightColor.Default) }
    var sleepTimerMinutes by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(ttsGain, ttsDrc) {
        ttsPlayer.updatePlaybackGain(ttsGain, ttsDrc)
    }

    var chapterBlocks by remember { mutableStateOf<List<ReaderBlock>>(emptyList()) }
    var chapterUtterances by remember { mutableStateOf<List<SpokenUtterance>>(emptyList()) }
    var currentUtteranceIndex by remember(book.id, book.progression) { mutableStateOf(savedProgression?.utteranceIndex ?: 0) }
    var selectedUtteranceIndex by remember { mutableStateOf<Int?>(null) }
    var currentWordRange by remember { mutableStateOf<IntRange?>(null) }
    var isLoadingChapter by remember { mutableStateOf(true) }
    var autoPlayOnChapterLoad by remember { mutableStateOf(false) }
    var isFirstChapterLoad by remember(book.id, book.progression) { mutableStateOf(savedProgression != null) }
    var isRestoringInitialPosition by remember(book.id, book.progression) { mutableStateOf(savedProgression != null) }

    val initialBlockIdx = remember(book.id, book.progression) { savedProgression?.blockIndex ?: 0 }
    val initialScrollOffset = remember(book.id, book.progression) { savedProgression?.scrollOffset ?: 0 }
    val lazyListState = remember(book.id, book.progression) {
        LazyListState(
            firstVisibleItemIndex = initialBlockIdx,
            firstVisibleItemScrollOffset = initialScrollOffset
        )
    }
    val currentChapter = chapters.getOrNull(currentChapterIndex) ?: Chapter(book.displayName, "")

    // Auto-dismiss restored toast after 3.5 seconds
    LaunchedEffect(showRestoredBanner) {
        if (showRestoredBanner) {
            delay(3500L)
            showRestoredBanner = false
        }
    }

    // Build and persist current reading progression
    fun buildCurrentProgression(): ReadingProgressionData {
        val totalCh = chapters.size.coerceAtLeast(1)
        val blkIdx = lazyListState.firstVisibleItemIndex
        val scrOffset = lazyListState.firstVisibleItemScrollOffset
        val chapFrac = when {
            isTtsActive && chapterUtterances.isNotEmpty() ->
                (currentUtteranceIndex.toDouble() / chapterUtterances.size.toDouble()).coerceIn(0.0, 1.0)
            chapterBlocks.size > 1 ->
                (blkIdx.toDouble() / (chapterBlocks.size - 1).toDouble()).coerceIn(0.0, 1.0)
            else -> 0.0
        }
        val totalFrac = ((currentChapterIndex.toDouble() + chapFrac) / totalCh.toDouble()).coerceIn(0.0, 1.0)
        return ReadingProgressionData(
            chapterIndex = currentChapterIndex,
            chapterHref = chapters.getOrNull(currentChapterIndex)?.href.orEmpty(),
            chapterTitle = chapters.getOrNull(currentChapterIndex)?.title.orEmpty(),
            blockIndex = blkIdx,
            scrollOffset = scrOffset,
            utteranceIndex = currentUtteranceIndex,
            chapterProgression = chapFrac,
            totalProgression = totalFrac,
            updatedAt = System.currentTimeMillis(),
            fontSize = currentFontSize,
            theme = currentTheme,
            ttsGain = ttsGain,
            ttsDrc = ttsDrc
        )
    }

    fun persistProgressionSync() {
        val bookId = book.id ?: return
        val dao = booksDao ?: return
        if (isLoadingChapter || isRestoringInitialPosition) return
        try {
            val json = buildCurrentProgression().toJsonString()
            val version = progressionWriteVersion.incrementAndGet()
            synchronized(progressionWriteLock) {
                if (version == progressionWriteVersion.get()) dao.updateProgression(bookId, json)
            }
        } catch (_: Throwable) {
        }
    }

    fun persistProgressionAsync() {
        val bookId = book.id ?: return
        val dao = booksDao ?: return
        if (isLoadingChapter || isRestoringInitialPosition) return
        val json = buildCurrentProgression().toJsonString()
        val version = progressionWriteVersion.incrementAndGet()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                synchronized(progressionWriteLock) {
                    // A queued scroll save must not overwrite the final position on exit.
                    if (version == progressionWriteVersion.get()) dao.updateProgression(bookId, json)
                }
            } catch (_: Throwable) {
            }
        }
    }

    // Disposal can run after several chapter changes. Keep the final save tied to
    // the latest composition rather than the chapter captured when opening the book.
    val saveLatestProgression by rememberUpdatedState { persistProgressionSync() }
    DisposableEffect(ttsPlayer) {
        onDispose {
            ttsPlayer.stop()
            saveLatestProgression()
        }
    }

    // Observe manual scrolling to sync currentUtteranceIndex and persist progression
    LaunchedEffect(lazyListState, isLoadingChapter, isRestoringInitialPosition) {
        if (isLoadingChapter || isRestoringInitialPosition) return@LaunchedEffect
        snapshotFlow {
            lazyListState.firstVisibleItemIndex to lazyListState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .debounce(250L)
            .collectLatest { (firstBlockIdx, _) ->
                if (!isLoadingChapter && !isRestoringInitialPosition && chapterBlocks.isNotEmpty()) {
                    if (!isTtsPlaying && !isTtsActive) {
                        val visibleBlock = chapterBlocks.getOrNull(firstBlockIdx)
                            ?: chapterBlocks.drop(firstBlockIdx).firstOrNull { it.utteranceIndices.isNotEmpty() }
                        visibleBlock?.utteranceIndices?.firstOrNull()?.let { uttIdx ->
                            currentUtteranceIndex = uttIdx
                        }
                    }
                    persistProgressionAsync()
                }
            }
    }

    // Persist progression when TTS utterance, font size, theme, or TTS gain/DRC changes
    LaunchedEffect(currentUtteranceIndex, currentFontSize, currentTheme, ttsGain, ttsDrc) {
        if (!isLoadingChapter && !isRestoringInitialPosition) {
            persistProgressionAsync()
        }
    }

    // Sleep timer countdown
    LaunchedEffect(sleepTimerMinutes, isTtsPlaying) {
        val minutes = sleepTimerMinutes
        if (minutes != null && minutes > 0 && isTtsPlaying) {
            delay(minutes * 60_000L)
            ttsPlayer.stop()
            isTtsPlaying = false
            isTtsActive = false
            currentWordRange = null
            sleepTimerMinutes = null
        }
    }

    fun formatRate(speed: Double): String {
        val pct = ((speed - 1.0) * 100).toInt()
        return if (pct >= 0) "+$pct%" else "$pct%"
    }

    fun playUtterance(index: Int) {
        if (chapterUtterances.isEmpty()) return
        if (index < 0 || index >= chapterUtterances.size) {
            if (currentChapterIndex + 1 < chapters.size) {
                autoPlayOnChapterLoad = true
                currentChapterIndex += 1
            } else {
                ttsPlayer.stop()
                isTtsPlaying = false
                isTtsActive = false
                currentWordRange = null
            }
            return
        }

        currentUtteranceIndex = index
        currentWordRange = null
        isTtsActive = true
        val utt = chapterUtterances[index]

        // Prefetch next 2 sentences for zero-latency transition
        val rateStr = formatRate(ttsSpeed)
        val volumeStr = TtsAudioProcessor.gainToSsmlVolume(ttsGain)
        for (nextIdx in (index + 1)..(index + 2)) {
            chapterUtterances.getOrNull(nextIdx)?.let { nextUtt ->
                ttsPlayer.prefetch(nextUtt.text, ttsVoice, rateStr, volume = volumeStr)
            }
        }

        ttsPlayer.play(
            text = utt.text,
            voice = ttsVoice,
            rate = rateStr,
            volume = volumeStr,
            onStart = {
                isTtsPlaying = true
                isTtsActive = true
            },
            onWordBoundary = { boundary ->
                currentWordRange = boundary.startChar until boundary.endChar
            },
            onFinish = {
                currentWordRange = null
                playUtterance(index + 1)
            },
            onError = {
                currentWordRange = null
                playUtterance(index + 1)
            }
        )
    }

    // Auto-scroll to the block containing currentUtteranceIndex when TTS advances
    LaunchedEffect(currentUtteranceIndex, isTtsActive) {
        if (isTtsActive && chapterBlocks.isNotEmpty()) {
            val blockIdx = chapterBlocks.indexOfFirst { currentUtteranceIndex in it.utteranceIndices }
            if (blockIdx >= 0) {
                val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                val isVisible = visibleItems.any { it.index == blockIdx }
                if (!isVisible || visibleItems.lastOrNull()?.index == blockIdx) {
                    lazyListState.animateScrollToItem((blockIdx - 1).coerceAtLeast(0))
                }
            }
        }
    }

    // Parse chapter HTML into pure Compose blocks + TTS utterances
    LaunchedEffect(currentChapterIndex) {
        isLoadingChapter = true
        val parsed = withContext(Dispatchers.IO) {
            parseChapterToComposeBlocks(contentReader, currentChapterIndex)
        }
        chapterBlocks = parsed.blocks
        chapterUtterances = parsed.utterances
        currentWordRange = null
        selectedUtteranceIndex = null
        isLoadingChapter = false

        if (isFirstChapterLoad && savedProgression != null) {
            isFirstChapterLoad = false
            val targetUtt = savedProgression.utteranceIndex
                .coerceIn(0, (parsed.utterances.size - 1).coerceAtLeast(0))
            currentUtteranceIndex = targetUtt
            val targetBlock = when {
                savedProgression.hasBlockPosition ->
                    savedProgression.blockIndex.coerceIn(0, (parsed.blocks.size - 1).coerceAtLeast(0))
                targetUtt > 0 ->
                    parsed.blocks.indexOfFirst { targetUtt in it.utteranceIndices }.coerceAtLeast(0)
                savedProgression.chapterProgression > 0 && parsed.blocks.isNotEmpty() ->
                    (savedProgression.chapterProgression * (parsed.blocks.size - 1)).toInt().coerceIn(0, parsed.blocks.size - 1)
                else -> 0
            }
            val targetOffset = savedProgression.scrollOffset.coerceAtLeast(0)
            if (parsed.blocks.isNotEmpty() && (targetBlock > 0 || targetOffset > 0)) {
                lazyListState.scrollToItem(targetBlock, targetOffset)
            }
            kotlinx.coroutines.yield()
            if (parsed.blocks.isNotEmpty() && (targetBlock > 0 || targetOffset > 0)) {
                lazyListState.scrollToItem(targetBlock, targetOffset)
            }
            delay(350L)
            isRestoringInitialPosition = false
        } else {
            isFirstChapterLoad = false
            isRestoringInitialPosition = false
            currentUtteranceIndex = 0
            if (parsed.blocks.isNotEmpty()) {
                lazyListState.scrollToItem(0)
            }
            if (savedProgression != null || currentChapterIndex > 0) {
                persistProgressionAsync()
            }
        }

        if (autoPlayOnChapterLoad) {
            autoPlayOnChapterLoad = false
            if (parsed.utterances.isNotEmpty()) {
                playUtterance(0)
            } else if (currentChapterIndex + 1 < chapters.size) {
                autoPlayOnChapterLoad = true
                currentChapterIndex += 1
            }
        }
    }

    val progressFraction = if (chapterUtterances.isNotEmpty()) {
        currentUtteranceIndex.toFloat() / chapterUtterances.size.toFloat()
    } else 0f

    val totalBookFraction = remember(
        currentChapterIndex,
        chapters.size,
        lazyListState.firstVisibleItemIndex,
        chapterBlocks.size,
        currentUtteranceIndex,
        chapterUtterances.size,
        isTtsActive
    ) {
        val totalCh = chapters.size.coerceAtLeast(1)
        val chapFrac = when {
            isTtsActive && chapterUtterances.isNotEmpty() ->
                (currentUtteranceIndex.toFloat() / chapterUtterances.size.toFloat()).coerceIn(0f, 1f)
            chapterBlocks.size > 1 ->
                (lazyListState.firstVisibleItemIndex.toFloat() / (chapterBlocks.size - 1).toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
        ((currentChapterIndex.toFloat() + chapFrac) / totalCh.toFloat()).coerceIn(0f, 1f)
    }
    val totalBookPercent = (totalBookFraction * 100f).roundToInt().coerceIn(0, 100)

    val readerBg = when (currentTheme) {
        "sepia" -> Color(0xFFFBF0D9)
        "dark" -> Color(0xFF1A1C1E)
        else -> Color.White
    }
    val readerFg = when (currentTheme) {
        "sepia" -> Color(0xFF443425)
        "dark" -> Color(0xFFDDE1E6)
        else -> Color(0xFF252A32)
    }
    val codeBg = when (currentTheme) {
        "sepia" -> Color(0xFFF2E4C6)
        "dark" -> Color(0xFF26292E)
        else -> Color(0xFFF4F6F8)
    }

    Box(modifier = modifier.fillMaxSize().background(readerBg)) {
        if (chapterBlocks.isEmpty() && !isLoadingChapter) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("本章无正文内容", color = readerFg.copy(alpha = 0.6f), fontSize = 16.sp)
                    if (currentChapterIndex + 1 < chapters.size) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { currentChapterIndex += 1 },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                        ) {
                            Text("前往下一章")
                        }
                    }
                }
            }
        } else {
            val scrollAdapter = rememberScrollbarAdapter(lazyListState)
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = PaddingValues(top = 80.dp, bottom = 100.dp, start = 48.dp, end = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(chapterBlocks) { _, block ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth()) {
                                ReaderBlockView(
                                    block = block,
                                    fontSize = currentFontSize,
                                    textColor = readerFg,
                                    codeBg = codeBg,
                                    activeUtteranceIndex = if (isTtsActive) currentUtteranceIndex else -1,
                                    selectedUtteranceIndex = selectedUtteranceIndex ?: -1,
                                    activeWordRange = if (isTtsActive) currentWordRange else null,
                                    isTtsPlaying = isTtsPlaying,
                                    highlightColor = ttsHighlightColor,
                                    onSentenceClick = { clickedUttIdx ->
                                        selectedUtteranceIndex = clickedUttIdx
                                        currentUtteranceIndex = clickedUttIdx
                                    },
                                    onStartReadFromHere = { startUttIdx ->
                                        selectedUtteranceIndex = null
                                        playUtterance(startUttIdx)
                                    }
                                )
                            }
                        }
                    }
                }

                VerticalScrollbar(
                    adapter = scrollAdapter,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp, top = 72.dp, bottom = 72.dp)
                )
            }
        }

        if (isLoadingChapter) {
            Box(Modifier.fillMaxSize().background(readerBg), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PrimaryBlue)
            }
        }

        // Top Floating Capsule Bar (对齐 Android VisualReaderFragment.kt)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 20.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.94f),
                shadowElevation = 4.dp
            ) {
                IconButton(
                    onClick = {
                        ttsPlayer.stop()
                        persistProgressionSync()
                        onClose()
                    }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回书架",
                        tint = TextPrimary
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White.copy(alpha = 0.94f),
                shadowElevation = 4.dp,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "${book.displayName}  ·  ${currentChapter.title}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.94f),
                shadowElevation = 4.dp
            ) {
                IconButton(onClick = { showFontDialog = true }) {
                    Icon(
                        Icons.Default.FormatSize,
                        contentDescription = "字体与主题",
                        tint = TextPrimary
                    )
                }
            }
        }

        // Restored Reading Position Banner ("已继续上一次阅读")
        AnimatedVisibility(
            visible = showRestoredBanner && savedProgression != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 74.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF1E293B).copy(alpha = 0.92f),
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "已继续上一次阅读：${currentChapter.title} (${savedProgression?.percent ?: 0}%)",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "从头阅读",
                        color = PrimaryBlue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            showRestoredBanner = false
                            isRestoringInitialPosition = false
                            currentChapterIndex = 0
                            currentUtteranceIndex = 0
                            coroutineScope.launch {
                                lazyListState.scrollToItem(0)
                                persistProgressionAsync()
                            }
                        }
                    )
                }
            }
        }

        // Interactive Reading Progress Slider Card (对齐 Android ReadingProgressBar.kt)
        AnimatedVisibility(
            visible = showProgressBar,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 82.dp, start = 48.dp, end = 48.dp)
                .widthIn(max = 520.dp)
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.97f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentChapter.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "全书 $totalBookPercent%",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue
                        )
                    }
                    Slider(
                        value = currentChapterIndex.toFloat(),
                        onValueChange = { newVal ->
                            val targetIdx = newVal.roundToInt().coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
                            if (targetIdx != currentChapterIndex) {
                                ttsPlayer.stop()
                                isTtsPlaying = false
                                currentChapterIndex = targetIdx
                            }
                        },
                        valueRange = 0f..(chapters.size - 1).coerceAtLeast(1).toFloat(),
                        steps = (chapters.size - 2).coerceAtLeast(0),
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryBlue,
                            activeTrackColor = PrimaryBlue
                        )
                    )
                    Text(
                        text = "第 ${currentChapterIndex + 1} / ${chapters.size.coerceAtLeast(1)} 章",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }

        // Bottom Floating Toolbar (目录、上一章、进度跳转、下一章)
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.White.copy(alpha = 0.95f),
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = { showOutlineDialog = true }) {
                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "目录", tint = TextPrimary)
                }

                IconButton(
                    onClick = {
                        if (currentChapterIndex > 0) {
                            val wasPlaying = isTtsPlaying
                            ttsPlayer.stop()
                            isTtsPlaying = false
                            autoPlayOnChapterLoad = wasPlaying
                            currentChapterIndex -= 1
                        }
                    },
                    enabled = currentChapterIndex > 0
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "上一章", tint = if (currentChapterIndex > 0) TextPrimary else Color.LightGray)
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (showProgressBar) PrimaryBlue.copy(alpha = 0.12f) else Color.Transparent,
                    modifier = Modifier.clickable { showProgressBar = !showProgressBar }
                ) {
                    Text(
                        text = "${currentChapterIndex + 1} / ${chapters.size.coerceAtLeast(1)}  ·  $totalBookPercent%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (showProgressBar) PrimaryBlue else TextSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                IconButton(
                    onClick = {
                        if (currentChapterIndex + 1 < chapters.size) {
                            val wasPlaying = isTtsPlaying
                            ttsPlayer.stop()
                            isTtsPlaying = false
                            autoPlayOnChapterLoad = wasPlaying
                            currentChapterIndex += 1
                        }
                    },
                    enabled = currentChapterIndex + 1 < chapters.size
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "下一章", tint = if (currentChapterIndex + 1 < chapters.size) TextPrimary else Color.LightGray)
                }
            }
        }

        // Floating Listen Button (FAB: 56dp 圆形悬浮按钮，对齐 Android ReaderListenButton)
        FloatingActionButton(
            onClick = {
                showTtsOverlay = true
                if (!isTtsPlaying && !isTtsActive && chapterUtterances.isNotEmpty()) {
                    playUtterance(currentUtteranceIndex)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 20.dp)
                .size(56.dp)
                .shadow(elevation = 8.dp, shape = CircleShape),
            containerColor = PrimaryBlue,
            contentColor = Color.White,
            shape = CircleShape
        ) {
            Icon(
                if (isTtsActive) Icons.Default.PlayArrow else Icons.Default.Headphones,
                contentDescription = "AI 阅读",
                modifier = Modifier.size(28.dp)
            )
        }

        // Font Size & Theme Dialog
        if (showFontDialog) {
            AlertDialog(
                onDismissRequest = { showFontDialog = false },
                title = { Text("阅读设置", fontWeight = FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("字号大小: ${currentFontSize}px", fontSize = 14.sp, color = TextSecondary)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { if (currentFontSize > 14) currentFontSize -= 2 },
                                enabled = currentFontSize > 14
                            ) {
                                Text("A-")
                            }
                            Text("$currentFontSize", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            OutlinedButton(
                                onClick = { if (currentFontSize < 32) currentFontSize += 2 },
                                enabled = currentFontSize < 32
                            ) {
                                Text("A+")
                            }
                        }

                        Spacer(Modifier.height(20.dp))
                        Text("阅读背景", fontSize = 14.sp, color = TextSecondary)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            ThemeChip("默认白", currentTheme == "light") { currentTheme = "light" }
                            ThemeChip("羊皮纸", currentTheme == "sepia") { currentTheme = "sepia" }
                            ThemeChip("夜间黑", currentTheme == "dark") { currentTheme = "dark" }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showFontDialog = false }) { Text("完成") }
                }
            )
        }

        // Outline Dialog (目录)
        if (showOutlineDialog) {
            AlertDialog(
                onDismissRequest = { showOutlineDialog = false },
                title = { Text("书籍目录 (${chapters.size} 节)", fontWeight = FontWeight.Bold) },
                text = {
                    Box(modifier = Modifier.fillMaxWidth().height(380.dp)) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(chapters) { idx, ch ->
                                val isSelected = idx == currentChapterIndex
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val wasPlaying = isTtsPlaying
                                            ttsPlayer.stop()
                                            isTtsPlaying = false
                                            autoPlayOnChapterLoad = wasPlaying
                                            currentChapterIndex = idx
                                            showOutlineDialog = false
                                        },
                                    color = if (isSelected) Color(0xFFE0F2FE) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "${idx + 1}. ${ch.title}",
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) PrimaryBlue else TextPrimary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showOutlineDialog = false }) { Text("关闭") }
                }
            )
        }

        // Fullscreen TtsPlayerOverlay Animated
        AnimatedVisibility(
            visible = showTtsOverlay,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            TtsPlayerOverlay(
                book = book,
                chapterTitle = currentChapter.title,
                isPlaying = isTtsPlaying,
                progressFraction = progressFraction,
                currentVoice = ttsVoice,
                currentSpeed = ttsSpeed,
                currentGain = ttsGain,
                drcEnabled = ttsDrc,
                currentHighlightColor = ttsHighlightColor,
                sleepTimerMinutes = sleepTimerMinutes,
                onPlayPause = {
                    if (isTtsPlaying) {
                        ttsPlayer.pause()
                        isTtsPlaying = false
                    } else {
                        if (ttsPlayer.isPaused) {
                            ttsPlayer.resume()
                            isTtsPlaying = true
                        } else {
                            playUtterance(currentUtteranceIndex)
                        }
                    }
                },
                onPrevious = {
                    if (currentUtteranceIndex > 0) {
                        playUtterance(currentUtteranceIndex - 1)
                    } else if (currentChapterIndex > 0) {
                        autoPlayOnChapterLoad = true
                        currentChapterIndex -= 1
                    }
                },
                onNext = {
                    if (currentUtteranceIndex + 1 < chapterUtterances.size) {
                        playUtterance(currentUtteranceIndex + 1)
                    } else if (currentChapterIndex + 1 < chapters.size) {
                        autoPlayOnChapterLoad = true
                        currentChapterIndex += 1
                    }
                },
                onSeek = { frac ->
                    if (chapterUtterances.isNotEmpty()) {
                        val targetIdx = (frac * (chapterUtterances.size - 1)).toInt().coerceIn(0, chapterUtterances.size - 1)
                        playUtterance(targetIdx)
                    }
                },
                onVoiceChange = { newVoice ->
                    ttsVoice = newVoice
                    if (isTtsPlaying) playUtterance(currentUtteranceIndex)
                },
                onSpeedChange = { newSpeed ->
                    ttsSpeed = newSpeed
                    if (isTtsPlaying) playUtterance(currentUtteranceIndex)
                },
                onGainChange = { newGain ->
                    ttsGain = newGain
                    ttsPlayer.updatePlaybackGain(newGain, ttsDrc)
                },
                onDrcChange = { newDrc ->
                    ttsDrc = newDrc
                    ttsPlayer.updatePlaybackGain(ttsGain, newDrc)
                },
                onHighlightColorChange = { ttsHighlightColor = it },
                onSleepTimerChange = { sleepTimerMinutes = it },
                onStopTts = {
                    ttsPlayer.stop()
                    isTtsPlaying = false
                    isTtsActive = false
                    currentWordRange = null
                },
                onDismiss = { showTtsOverlay = false },
                onOutline = { showOutlineDialog = true }
            )
        }
    }
}

@Composable
private fun ReaderBlockView(
    block: ReaderBlock,
    fontSize: Int,
    textColor: Color,
    codeBg: Color,
    activeUtteranceIndex: Int,
    selectedUtteranceIndex: Int,
    activeWordRange: IntRange?,
    isTtsPlaying: Boolean,
    highlightColor: TtsHighlightColor,
    onSentenceClick: (Int) -> Unit,
    onStartReadFromHere: (Int) -> Unit
) {
    val hasSelectedSentence = selectedUtteranceIndex >= 0 && selectedUtteranceIndex in block.utteranceIndices
    var lastPointerUttIndex by remember { mutableStateOf(block.utteranceIndices.firstOrNull() ?: -1) }

    // Unified right-click / selection ContextMenu (与系统「复制文本」在同一个菜单中)
    ContextMenuDataProvider(
        items = {
            val targetUtt = when {
                hasSelectedSentence -> selectedUtteranceIndex
                lastPointerUttIndex >= 0 -> lastPointerUttIndex
                else -> block.utteranceIndices.firstOrNull() ?: -1
            }
            if (targetUtt >= 0) {
                listOf(
                    ContextMenuItem("从此处开始朗读") {
                        onStartReadFromHere(targetUtt)
                    }
                )
            } else {
                emptyList()
            }
        }
    ) {
        // Pure Box without any layout/DOM insertion when clicking sentences
        Box(modifier = Modifier.fillMaxWidth()) {
            when (block) {
                is ReaderBlock.Heading -> {
                    val headingSize = when (block.level) {
                        1 -> (fontSize + 8).sp
                        2 -> (fontSize + 5).sp
                        3 -> (fontSize + 3).sp
                        else -> (fontSize + 1).sp
                    }
                    val annotated = buildHighlightedAnnotatedString(
                        sentences = block.sentences,
                        activeUtteranceIndex = activeUtteranceIndex,
                        selectedUtteranceIndex = selectedUtteranceIndex,
                        activeWordRange = activeWordRange,
                        isTtsPlaying = isTtsPlaying,
                        highlightColor = highlightColor,
                        codeBg = codeBg
                    )
                    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                    SelectionContainer {
                        Text(
                            text = annotated,
                            style = TextStyle(
                                fontSize = headingSize,
                                lineHeight = (headingSize.value * 1.45f).sp,
                                fontWeight = FontWeight.Bold,
                                color = textColor
                            ),
                            onTextLayout = { layoutResult = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, bottom = 4.dp)
                                .pointerInput(annotated) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        val downPos = down.position
                                        val downTime = System.currentTimeMillis()
                                        layoutResult?.let { layout ->
                                            val charIdx = layout.getOffsetForPosition(downPos)
                                            annotated.getStringAnnotations(tag = "UTT", start = charIdx, end = charIdx)
                                                .firstOrNull()?.item?.toIntOrNull()?.let { lastPointerUttIndex = it }
                                        }
                                        val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                                        if (up != null && (up.position - downPos).getDistance() < 8f && (System.currentTimeMillis() - downTime) < 450L) {
                                            layoutResult?.let { layout ->
                                                val charIdx = layout.getOffsetForPosition(up.position)
                                                val uttIdx = annotated.getStringAnnotations(tag = "UTT", start = charIdx, end = charIdx)
                                                    .firstOrNull()?.item?.toIntOrNull()
                                                if (uttIdx != null) {
                                                    lastPointerUttIndex = uttIdx
                                                    onSentenceClick(uttIdx)
                                                }
                                            }
                                        }
                                    }
                                }
                        )
                    }
                }

                is ReaderBlock.Paragraph -> {
                    val annotated = buildHighlightedAnnotatedString(
                        sentences = block.sentences,
                        activeUtteranceIndex = activeUtteranceIndex,
                        selectedUtteranceIndex = selectedUtteranceIndex,
                        activeWordRange = activeWordRange,
                        isTtsPlaying = isTtsPlaying,
                        highlightColor = highlightColor,
                        codeBg = codeBg
                    )
                    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (block.isQuote) {
                                    Modifier
                                        .background(codeBg.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                } else Modifier
                            )
                    ) {
                        if (block.bulletPrefix != null) {
                            Text(
                                text = block.bulletPrefix,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.75f).sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryBlue,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            SelectionContainer {
                                Text(
                                    text = annotated,
                                    style = TextStyle(
                                        fontSize = fontSize.sp,
                                        lineHeight = (fontSize * 1.75f).sp,
                                        color = if (block.isQuote) textColor.copy(alpha = 0.85f) else textColor,
                                        fontStyle = if (block.isQuote) FontStyle.Italic else FontStyle.Normal
                                    ),
                                    onTextLayout = { layoutResult = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .pointerInput(annotated) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                                val downPos = down.position
                                                val downTime = System.currentTimeMillis()
                                                layoutResult?.let { layout ->
                                                    val charIdx = layout.getOffsetForPosition(downPos)
                                                    annotated.getStringAnnotations(tag = "UTT", start = charIdx, end = charIdx)
                                                        .firstOrNull()?.item?.toIntOrNull()?.let { lastPointerUttIndex = it }
                                                }
                                                val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                                                if (up != null && (up.position - downPos).getDistance() < 8f && (System.currentTimeMillis() - downTime) < 450L) {
                                                    layoutResult?.let { layout ->
                                                        val charIdx = layout.getOffsetForPosition(up.position)
                                                        val uttIdx = annotated.getStringAnnotations(tag = "UTT", start = charIdx, end = charIdx)
                                                            .firstOrNull()?.item?.toIntOrNull()
                                                        if (uttIdx != null) {
                                                            lastPointerUttIndex = uttIdx
                                                            onSentenceClick(uttIdx)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                )
                            }
                        }
                    }
                }

                is ReaderBlock.Code -> {
                    val isHighlighted = block.utteranceIndex != null && block.utteranceIndex == activeUtteranceIndex
                    val bgColor = if (isHighlighted) {
                        highlightColor.composeColor.copy(alpha = 0.35f)
                    } else codeBg

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = bgColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = block.utteranceIndex != null) {
                                block.utteranceIndex?.let {
                                    onSentenceClick(it)
                                }
                            }
                    ) {
                        Text(
                            text = block.codeText,
                            fontSize = (fontSize - 2).coerceAtLeast(12).sp,
                            lineHeight = ((fontSize - 2).coerceAtLeast(12) * 1.55f).sp,
                            fontFamily = FontFamily.Monospace,
                            color = textColor,
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(14.dp)
                        )
                    }
                }

                is ReaderBlock.MathFormula -> {
                    val isHighlighted = block.utteranceIndex == activeUtteranceIndex
                    val isSelected = block.utteranceIndex == selectedUtteranceIndex
                    val borderColor = when {
                        isHighlighted -> highlightColor.composeColor
                        isSelected -> PrimaryBlue
                        else -> PrimaryBlue.copy(alpha = 0.3f)
                    }
                    val bgColor = when {
                        isHighlighted -> highlightColor.composeColor.copy(alpha = if (isTtsPlaying) 0.40f else 0.22f)
                        isSelected -> PrimaryBlue.copy(alpha = 0.14f)
                        else -> codeBg.copy(alpha = 0.5f)
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = bgColor,
                        border = BorderStroke(if (isHighlighted || isSelected) 2.dp else 1.dp, borderColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSentenceClick(block.utteranceIndex)
                            }
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = block.displayFormula,
                                fontSize = (fontSize + 1).sp,
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Medium,
                                color = textColor
                            )
                            if (isHighlighted && block.spokenText.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "朗读: ${block.spokenText}",
                                    fontSize = 12.sp,
                                    color = textColor.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }

                is ReaderBlock.ImageItem -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            bitmap = block.bitmap,
                            contentDescription = block.caption ?: "Book Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 520.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Fit
                        )
                        if (!block.caption.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = block.caption,
                                fontSize = (fontSize - 3).coerceAtLeast(11).sp,
                                color = textColor.copy(alpha = 0.7f),
                                fontStyle = FontStyle.Italic
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildHighlightedAnnotatedString(
    sentences: List<IndexedSentence>,
    activeUtteranceIndex: Int,
    selectedUtteranceIndex: Int,
    activeWordRange: IntRange?,
    isTtsPlaying: Boolean,
    highlightColor: TtsHighlightColor,
    codeBg: Color
): AnnotatedString {
    return buildAnnotatedString {
        sentences.forEachIndexed { idx, sent ->
            val startOffset = length
            append(sent.text)
            val endOffset = length

            addStringAnnotation(
                tag = "UTT",
                annotation = sent.utteranceIndex.toString(),
                start = startOffset,
                end = endOffset
            )

            if (sent.isBold) {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), startOffset, endOffset)
            }
            if (sent.isItalic) {
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), startOffset, endOffset)
            }
            if (sent.isCode) {
                addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBg
                    ),
                    startOffset,
                    endOffset
                )
            }

            if (sent.utteranceIndex == selectedUtteranceIndex && sent.utteranceIndex != activeUtteranceIndex) {
                addStyle(
                    SpanStyle(background = PrimaryBlue.copy(alpha = 0.18f)),
                    startOffset,
                    endOffset
                )
            }

            if (sent.utteranceIndex == activeUtteranceIndex) {
                val sentenceBg = highlightColor.composeColor.copy(alpha = if (isTtsPlaying) 0.42f else 0.24f)
                addStyle(
                    SpanStyle(background = sentenceBg),
                    startOffset,
                    endOffset
                )

                if (activeWordRange != null) {
                    val wStart = (startOffset + activeWordRange.first).coerceIn(startOffset, endOffset)
                    val wEnd = (startOffset + activeWordRange.last + 1).coerceIn(wStart, endOffset)
                    if (wEnd > wStart) {
                        addStyle(
                            SpanStyle(
                                background = highlightColor.composeColor.copy(alpha = 0.88f),
                                fontWeight = FontWeight.SemiBold
                            ),
                            wStart,
                            wEnd
                        )
                    }
                }
            }

            if (idx < sentences.lastIndex) {
                append(" ")
            }
        }
    }
}

private fun parseChapterToComposeBlocks(
    contentReader: EpubContentReader,
    chapterIndex: Int
): ParsedChapterData {
    val rawHtml = contentReader.readChapterContent(chapterIndex)
    val chapterHref = contentReader.chapters.getOrNull(chapterIndex)?.href ?: ""
    val doc = Jsoup.parse(rawHtml)
    val body = doc.body()

    val blocks = mutableListOf<ReaderBlock>()
    val utterances = mutableListOf<SpokenUtterance>()

    fun registerUtterance(text: String, isMath: Boolean = false): Int {
        val idx = utterances.size
        utterances.add(
            SpokenUtterance(
                text = text,
                href = chapterHref,
                cssSelector = "",
                isMath = isMath,
                hasInlineMath = false,
                mathSelector = "",
                mathId = "",
                speechMap = SpeechMap(emptyList())
            )
        )
        return idx
    }

    fun splitIntoSentences(text: String): List<String> {
        val cleaned = text.replace("\\s+".toRegex(), " ").trim()
        if (cleaned.isEmpty()) return emptyList()
        val iterator = BreakIterator.getSentenceInstance(Locale.CHINA)
        iterator.setText(cleaned)
        val result = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val s = cleaned.substring(start, end).trim()
            if (s.isNotEmpty()) {
                result.add(s)
            }
            start = end
            end = iterator.next()
        }
        return if (result.isEmpty()) listOf(cleaned) else result
    }

    fun decodeImage(src: String): ImageBitmap? {
        val bytes = contentReader.readResourceBytes(chapterIndex, src) ?: return null
        return try {
            ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }

    fun extractParagraphSentences(element: Element): List<IndexedSentence> {
        // Replace math nodes inline with spoken representation if any
        for (mathEl in element.select("math")) {
            val bundle = MathSpeechMarkup.structure(mathEl.outerHtml())
            val spoken = bundle.spokenText.ifBlank {
                MathSpeechMarkup.latexToChineseSpeech(MathSpeechMarkup.toMathmlLatex(mathEl))
            }
            mathEl.replaceWith(TextNode(" $spoken "))
        }
        val text = element.text().replace("\\s+".toRegex(), " ").trim()
        if (text.isEmpty()) return emptyList()

        val rawSentences = splitIntoSentences(text)
        return rawSentences.mapNotNull { sent ->
            if (sent.any { it.isLetterOrDigit() } || sent.length > 1) {
                val uttIdx = registerUtterance(sent)
                IndexedSentence(text = sent, utteranceIndex = uttIdx)
            } else null
        }
    }

    val containerTags = setOf(
        "body", "div", "section", "article", "main", "header", "footer", "aside", "nav",
        "figure", "fieldset", "form", "dd", "dl", "dt", "tbody", "thead", "tfoot", "tr"
    )
    val headingTags = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    fun walkNode(node: Node, isQuote: Boolean = false) {
        when (node) {
            is TextNode -> {
                val txt = node.text().trim()
                if (txt.isNotEmpty()) {
                    val sentences = splitIntoSentences(txt).map { s ->
                        IndexedSentence(s, registerUtterance(s))
                    }
                    if (sentences.isNotEmpty()) {
                        blocks.add(ReaderBlock.Paragraph(sentences, isQuote = isQuote))
                    }
                }
            }
            is Element -> {
                val tag = node.normalName()
                when {
                    tag in setOf("script", "style", "head", "meta", "link") -> {}

                    tag == "img" || tag == "image" -> {
                        val src = node.attr("src").ifBlank { node.attr("xlink:href") }.ifBlank { node.attr("href") }
                        if (src.isNotBlank()) {
                            decodeImage(src)?.let { bmp ->
                                val alt = node.attr("alt").takeIf { it.isNotBlank() }
                                blocks.add(ReaderBlock.ImageItem(bmp, alt))
                            }
                        }
                    }

                    tag == "svg" -> {
                        val imgNode = node.selectFirst("image, img")
                        if (imgNode != null) {
                            val src = imgNode.attr("xlink:href").ifBlank { imgNode.attr("href") }.ifBlank { imgNode.attr("src") }
                            if (src.isNotBlank()) {
                                decodeImage(src)?.let { bmp ->
                                    blocks.add(ReaderBlock.ImageItem(bmp, null))
                                }
                            }
                        }
                    }

                    tag == "math" -> {
                        val bundle = MathSpeechMarkup.structure(node.outerHtml())
                        val latex = MathSpeechMarkup.toMathmlLatex(node)
                        val spoken = bundle.spokenText.ifBlank { MathSpeechMarkup.latexToChineseSpeech(latex) }
                        if (spoken.isNotBlank() || latex.isNotBlank()) {
                            val uttIdx = registerUtterance(spoken.ifBlank { latex }, isMath = true)
                            blocks.add(
                                ReaderBlock.MathFormula(
                                    displayFormula = latex.ifBlank { node.text() },
                                    spokenText = spoken,
                                    utteranceIndex = uttIdx
                                )
                            )
                        }
                    }

                    tag == "pre" -> {
                        val codeText = node.wholeText().trimEnd()
                        if (codeText.isNotEmpty()) {
                            blocks.add(ReaderBlock.Code(codeText, null))
                        }
                    }

                    tag in headingTags -> {
                        // Also check for any images inside heading
                        for (img in node.select("img, image")) {
                            val src = img.attr("src").ifBlank { img.attr("xlink:href") }
                            decodeImage(src)?.let { blocks.add(ReaderBlock.ImageItem(it, null)) }
                        }
                        val level = tag.removePrefix("h").toIntOrNull() ?: 2
                        val sentences = extractParagraphSentences(node.clone())
                        if (sentences.isNotEmpty()) {
                            blocks.add(ReaderBlock.Heading(level, sentences))
                        }
                    }

                    tag == "ul" || tag == "ol" -> {
                        val isOrdered = tag == "ol"
                        var itemIdx = 1
                        for (child in node.children()) {
                            if (child.normalName() == "li") {
                                val prefix = if (isOrdered) "$itemIdx." else "•"
                                itemIdx += 1
                                val sentences = extractParagraphSentences(child.clone())
                                if (sentences.isNotEmpty()) {
                                    blocks.add(ReaderBlock.Paragraph(sentences, isQuote = isQuote, bulletPrefix = prefix))
                                }
                            } else {
                                walkNode(child, isQuote)
                            }
                        }
                    }

                    tag == "blockquote" -> {
                        for (child in node.childNodes()) {
                            walkNode(child, isQuote = true)
                        }
                    }

                    tag == "p" || tag == "li" || tag == "figcaption" || tag == "caption" || tag == "td" || tag == "th" -> {
                        for (img in node.select("img, image")) {
                            val src = img.attr("src").ifBlank { img.attr("xlink:href") }
                            if (src.isNotBlank()) {
                                decodeImage(src)?.let { bmp ->
                                    blocks.add(ReaderBlock.ImageItem(bmp, img.attr("alt").takeIf { it.isNotBlank() }))
                                }
                            }
                        }
                        val sentences = extractParagraphSentences(node.clone())
                        if (sentences.isNotEmpty()) {
                            blocks.add(
                                ReaderBlock.Paragraph(
                                    sentences = sentences,
                                    isQuote = isQuote,
                                    bulletPrefix = if (tag == "li") "•" else null
                                )
                            )
                        }
                    }

                    tag in containerTags -> {
                        val hasBlockChildren = node.children().any {
                            it.normalName() in containerTags ||
                                    it.normalName() in headingTags ||
                                    it.normalName() in setOf("p", "pre", "ul", "ol", "blockquote", "table", "figure", "img", "svg", "math")
                        }
                        if (hasBlockChildren) {
                            for (child in node.childNodes()) {
                                walkNode(child, isQuote)
                            }
                        } else {
                            for (img in node.select("img, image")) {
                                val src = img.attr("src").ifBlank { img.attr("xlink:href") }
                                if (src.isNotBlank()) {
                                    decodeImage(src)?.let { bmp ->
                                        blocks.add(ReaderBlock.ImageItem(bmp, img.attr("alt").takeIf { it.isNotBlank() }))
                                    }
                                }
                            }
                            val sentences = extractParagraphSentences(node.clone())
                            if (sentences.isNotEmpty()) {
                                blocks.add(ReaderBlock.Paragraph(sentences, isQuote = isQuote))
                            }
                        }
                    }

                    else -> {
                        if (node.children().isNotEmpty()) {
                            for (child in node.childNodes()) {
                                walkNode(child, isQuote)
                            }
                        } else {
                            val sentences = extractParagraphSentences(node.clone())
                            if (sentences.isNotEmpty()) {
                                blocks.add(ReaderBlock.Paragraph(sentences, isQuote = isQuote))
                            }
                        }
                    }
                }
            }
        }
    }

    walkNode(body)

    return ParsedChapterData(blocks = blocks, utterances = utterances)
}

@Composable
private fun ThemeChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) PrimaryBlue else Color(0xFFF1F5F9),
        contentColor = if (isSelected) Color.White else TextPrimary,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
