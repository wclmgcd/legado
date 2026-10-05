package io.legado.app.ui.main.rss

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isRss
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.rememberResponsiveColumns
import io.legado.app.ui.compose.platform.platformStatusBarPadding
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.toRouteRef
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.rss
import legado.ui.generated.resources.search_book_source
import org.jetbrains.compose.resources.stringResource

/**
 * 主界面「订阅」tab (第四个 tab, 2026-10 替换掉原来的自定义「主页」)。
 *
 * # 数据来源
 * 本 fork 没有独立的 RSS 实体 —— 一个 RSS 订阅源就是 **`book` 表里 `type` 含
 * [io.legado.app.constant.BookType.rss] 位的 Book** (见 `RssHelp` 顶部说明:
 * "app 端无独立 RssSource / RssArticle 实体, RSS 源 = Book(type |= BookType.rss)")。
 * 所以这里直接订阅 [io.legado.app.data.dao.BookDao.flowAll], 前端按 `isRss` 过滤:
 * 用户从「发现」页打开某个订阅源并加入书架后, 就会出现在这里。
 *
 * # 为什么不用 bookSourceDao
 * `book_sources_part` 视图 (BookSourceDao.flowAll 的返回) 的 SQL 里**没有
 * `bookSourceType` 列**, 无法筛出 RSS 书源; 而按「已订阅的书」这个语义走 book 表
 * 也更贴近原版 legado 的「订阅」页。
 *
 * # 点击
 * 直接进 [AppRoute.ReadRss] 阅读该订阅源。注意 flow 实体进路由前必须 `copy()`
 * (见 `toRouteRef` 的拷贝契约): 不 copy 会让路由与 DB flow 实体别名, 后续 DB 更新
 * 无法被正确捕捉。
 *
 * 布局参考 Qread-flutter 的 `rss_page.dart`: 顶部标题 + 搜索框, 下方 3 列网格
 * (宽屏按参考宽度自动加列, 复用 [rememberResponsiveColumns])。
 */
@Composable
fun RssTabContent(navigator: AppNavigator) {
    val colors = AppTheme.colors
    val dao = remember { AppDbProviders.get().bookDao }
    val allBooks by remember(dao) { dao.flowAll() }.collectAsState(emptyList())

    // 只保留 RSS 类型的书 (= 订阅源)。flowAll 是全表, 但 RSS 书通常很少, 前端过滤足够。
    val rssBooks = remember(allBooks) { allBooks.filter { it.isRss } }

    var searchKey by remember { mutableStateOf("") }
    val shown = remember(rssBooks, searchKey) {
        val key = searchKey.trim()
        if (key.isEmpty()) {
            rssBooks
        } else {
            rssBooks.filter {
                it.name.contains(key, ignoreCase = true) ||
                    it.author.contains(key, ignoreCase = true)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .platformStatusBarPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.rss),
                color = colors.primaryText,
                fontSize = 20.sp,
            )
            Spacer(Modifier.width(12.dp))
            AppSearchField(
                value = searchKey,
                onValueChange = { searchKey = it },
                hint = stringResource(Res.string.search_book_source),
                modifier = Modifier.weight(1f),
            )
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (rssBooks.isEmpty()) {
                        "还没有订阅\n在「发现」里打开一个订阅源, 加入书架后就会出现在这里"
                    } else {
                        "没有匹配的订阅"
                    },
                    color = colors.secondaryText,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = rememberResponsiveColumns(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.bookUrl }) { book ->
                    RssSourceCard(book) {
                        // DB-flow 实体进路由前必须 copy(), 否则路由与 flow 实体别名
                        navigator.push(AppRoute.ReadRss(book.copy().toRouteRef()))
                    }
                }
            }
        }
    }
}

/** 单个订阅源卡片: 图标 + 名称 + 作者(或地址)。 */
@Composable
private fun RssSourceCard(book: Book, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.fillet)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = rememberPainter("ic_bottom_rss_s"),
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = book.name,
            color = colors.primaryText,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (book.author.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = book.author,
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
