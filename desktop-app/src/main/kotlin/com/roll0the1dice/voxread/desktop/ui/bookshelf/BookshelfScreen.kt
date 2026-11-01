package com.roll0the1dice.voxread.desktop.ui.bookshelf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roll0the1dice.voxread.desktop.data.db.BooksDao
import com.roll0the1dice.voxread.desktop.data.model.Book
import com.roll0the1dice.voxread.desktop.ui.components.BookCard
import com.roll0the1dice.voxread.desktop.ui.theme.PrimaryBlue
import com.roll0the1dice.voxread.desktop.ui.theme.TextPrimary
import com.roll0the1dice.voxread.desktop.ui.theme.TextSecondary
import com.roll0the1dice.voxread.desktop.ui.theme.TextTertiary
import com.roll0the1dice.voxread.desktop.util.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter

@Composable
fun BookshelfScreen(
    booksDao: BooksDao,
    onOpenBook: (Book) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var bookToDelete by remember { mutableStateOf<Book?>(null) }

    fun refreshBooks() {
        coroutineScope.launch(Dispatchers.IO) {
            val list = booksDao.getAllBooks()
            withContext(Dispatchers.Main) {
                books = list
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshBooks()
    }

    fun pickAndImportEpub() {
        val dialog = FileDialog(null as Frame?, "导入 EPUB 电子书", FileDialog.LOAD).apply {
            filenameFilter = FilenameFilter { _, name -> name.lowercase().endsWith(".epub") }
            isMultipleMode = true
            isVisible = true
        }

        val files = dialog.files
        if (!files.isNullOrEmpty()) {
            coroutineScope.launch(Dispatchers.IO) {
                for (file in files) {
                    val book = EpubParser.importEpubFile(file)
                    booksDao.insertBook(book)
                }
                refreshBooks()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFFF8F9FA))) {
        if (books.isEmpty()) {
            // Empty State (对齐 Android fragment_bookshelf.xml)
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "书架还是空的",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "点击右下角「+」导入 .epub 电子书开始阅读",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
        } else {
            // Find the most recently read book with progression
            val lastReadPair = remember(books) {
                books.mapNotNull { b -> b.parsedProgression?.let { prog -> b to prog } }
                    .maxByOrNull { (b, prog) -> if (prog.updatedAt > 0L) prog.updatedAt else b.creation }
            }

            // Book Grid
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (lastReadPair != null) {
                    val (lastBook, lastProg) = lastReadPair
                    item(span = { GridItemSpan(maxLineSpan) }, key = "resume_banner_${lastBook.id}") {
                        ContextMenuArea(
                            items = {
                                listOf(
                                    ContextMenuItem("继续阅读 (${lastProg.percent}%)") { onOpenBook(lastBook) },
                                    ContextMenuItem("从书架删除") { bookToDelete = lastBook }
                                )
                            }
                        ) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenBook(lastBook) },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp, hoveredElevation = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = PrimaryBlue.copy(alpha = 0.12f),
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.MenuBook,
                                                contentDescription = null,
                                                tint = PrimaryBlue,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(16.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "继续上一次阅读",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = PrimaryBlue
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Surface(
                                                color = PrimaryBlue.copy(alpha = 0.10f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = "已读 ${lastProg.percent}%",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = PrimaryBlue,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = lastBook.displayName,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (lastProg.chapterTitle.isNotBlank()) {
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = "上次读至：${lastProg.chapterTitle}",
                                                fontSize = 12.sp,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(16.dp))

                                    Button(
                                        onClick = { onOpenBook(lastBook) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = PrimaryBlue,
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("继续阅读", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    OutlinedIconButton(
                                        onClick = { bookToDelete = lastBook },
                                        modifier = Modifier.size(36.dp),
                                        border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                                        colors = IconButtonDefaults.outlinedIconButtonColors(
                                            containerColor = Color(0xFFFEF2F2),
                                            contentColor = Color(0xFFEF4444)
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "从书架删除",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                items(books, key = { it.id ?: it.href }) { book ->
                    BookCard(
                        book = book,
                        onOpen = onOpenBook,
                        onDelete = { bookToDelete = it }
                    )
                }
            }
        }

        // FAB: 对齐 Android 原版 56dp 圆形悬浮按钮 (bookshelf_addBookFab)
        FloatingActionButton(
            onClick = { pickAndImportEpub() },
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
                Icons.Default.Add,
                contentDescription = "导入图书",
                modifier = Modifier.size(28.dp)
            )
        }

        // 模态弹窗：删除确认 (使用窗口内原生蒙层 Overlay，避开 Linux 下 JDialog 窗口层级/失焦问题)
        bookToDelete?.let { book ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { bookToDelete = null },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .widthIn(min = 320.dp, max = 460.dp)
                        .padding(24.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* 拦截点击，防止点击弹窗内容关闭 */ },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFFEE2E2),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = Color(0xFFEF4444),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "从书架移除",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            text = "确定从书架中移除《${book.displayName}》吗？\n\n此操作仅从书架移除记录，不会删除磁盘上的原始文件。",
                            fontSize = 14.sp,
                            color = TextSecondary,
                            lineHeight = 20.sp
                        )

                        Spacer(Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(
                                onClick = { bookToDelete = null },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("取消", color = TextSecondary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Button(
                                onClick = {
                                    val targetBook = book
                                    bookToDelete = null
                                    coroutineScope.launch(Dispatchers.IO) {
                                        if (targetBook.id != null) {
                                            booksDao.deleteBook(targetBook.id)
                                        } else {
                                            booksDao.deleteBookByHref(targetBook.href)
                                        }
                                        targetBook.cover?.let { coverPath ->
                                            try {
                                                File(coverPath).delete()
                                            } catch (_: Throwable) {}
                                        }
                                        refreshBooks()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFEF4444),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("确定删除", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
