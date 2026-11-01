package com.roll0the1dice.voxread.desktop.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.roll0the1dice.voxread.desktop.ui.theme.BorderLight
import com.roll0the1dice.voxread.desktop.ui.theme.PrimaryBlue
import com.roll0the1dice.voxread.desktop.ui.theme.TextSecondary

enum class NavTab(val title: String) {
    Bookshelf("书架"),
    Catalogs("网络书库"),
    About("关于")
}

@Composable
fun BottomNavBar(
    currentTab: NavTab,
    onTabSelected: (NavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier,
        containerColor = Color.White,
        tonalElevation = 8.dp
    ) {
        NavigationBarItem(
            icon = { Icon(Icons.Default.Book, contentDescription = "书架") },
            label = { Text("书架") },
            selected = currentTab == NavTab.Bookshelf,
            onClick = { onTabSelected(NavTab.Bookshelf) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = PrimaryBlue,
                selectedTextColor = PrimaryBlue,
                indicatorColor = Color(0xFFE0F2FE),
                unselectedIconColor = TextSecondary,
                unselectedTextColor = TextSecondary
            )
        )

        NavigationBarItem(
            icon = { Icon(Icons.Default.Explore, contentDescription = "网络书库") },
            label = { Text("网络书库") },
            selected = currentTab == NavTab.Catalogs,
            onClick = { onTabSelected(NavTab.Catalogs) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = PrimaryBlue,
                selectedTextColor = PrimaryBlue,
                indicatorColor = Color(0xFFE0F2FE),
                unselectedIconColor = TextSecondary,
                unselectedTextColor = TextSecondary
            )
        )

        NavigationBarItem(
            icon = { Icon(Icons.Default.Info, contentDescription = "关于") },
            label = { Text("关于") },
            selected = currentTab == NavTab.About,
            onClick = { onTabSelected(NavTab.About) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = PrimaryBlue,
                selectedTextColor = PrimaryBlue,
                indicatorColor = Color(0xFFE0F2FE),
                unselectedIconColor = TextSecondary,
                unselectedTextColor = TextSecondary
            )
        )
    }
}
