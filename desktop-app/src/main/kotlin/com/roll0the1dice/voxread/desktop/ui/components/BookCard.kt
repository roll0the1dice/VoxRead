package com.roll0the1dice.voxread.desktop.ui.components

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roll0the1dice.voxread.desktop.data.model.Book
import com.roll0the1dice.voxread.desktop.ui.theme.PrimaryBlue
import com.roll0the1dice.voxread.desktop.ui.theme.TextPrimary
import com.roll0the1dice.voxread.desktop.ui.theme.TextSecondary
import java.io.File
import javax.imageio.ImageIO

@Composable
fun BookCard(
    book: Book,
    onOpen: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

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

    val savedProg = remember(book.progression) { book.parsedProgression }

    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem(if (savedProg != null) "继续阅读 (${savedProg.percent}%)" else "打开阅读") {
                    onOpen(book)
                },
                ContextMenuItem("从书架删除") {
                    onDelete(book)
                }
            )
        }
    ) {
        Card(
            modifier = modifier.width(160.dp),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp, hoveredElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
            ) {
                // Book Cover (点击打开阅读，严格 2:3 比例 + 顶部 "已读 XX%" 胶囊徽标)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF1F5F9))
                        .shadow(elevation = 2.dp, shape = RoundedCornerShape(8.dp))
                        .clickable { onOpen(book) },
                    contentAlignment = Alignment.Center
                ) {
                    if (coverBitmap != null) {
                        Image(
                            bitmap = coverBitmap,
                            contentDescription = book.displayName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                text = book.displayName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                textAlign = TextAlign.Center,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // 顶部阅读进度胶囊徽标 (对齐 Android bookshelf_progressLabel "已读 XX%")
                    if (savedProg != null) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.62f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                        ) {
                            Text(
                                text = "已读 ${savedProg.percent}%",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        // 封面底部进度细条
                        LinearProgressIndicator(
                            progress = { savedProg.totalProgression.toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(3.dp),
                            color = PrimaryBlue,
                            trackColor = Color.Black.copy(alpha = 0.25f)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Title & Menu Button Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = book.displayName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 2,
                        lineHeight = 17.sp,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpen(book) }
                    )

                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "更多选项",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (savedProg != null) "继续上次阅读 (${savedProg.percent}%)" else "打开阅读",
                                        color = TextPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.MenuBook,
                                        contentDescription = null,
                                        tint = PrimaryBlue
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onOpen(book)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("从书架删除", color = Color(0xFFEF4444)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除",
                                        tint = Color(0xFFEF4444)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onDelete(book)
                                }
                            )
                        }
                    }
                }

                // Last read chapter or Author
                val subtitleText = savedProg?.chapterTitle?.takeIf { it.isNotBlank() }?.let { "读至: $it" }
                    ?: book.author
                subtitleText?.let { sub ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = sub,
                        fontSize = 11.sp,
                        color = if (savedProg != null) PrimaryBlue else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onOpen(book) }
                    )
                }
            }
        }
    }
}
