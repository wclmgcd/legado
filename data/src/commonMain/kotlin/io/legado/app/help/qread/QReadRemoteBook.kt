package io.legado.app.help.qread

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookListPage
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 远端解析引擎 —— 把书源的 **搜索 / 发现 / 目录 / 正文** 交给轻阅读后端执行。
 *
 * # 背景: 两种解析模式
 * | | 谁跑规则 | 登录态在哪 | 依赖 |
 * |---|---|---|---|
 * | **本地解析** (legado 原生) | 客户端 JS 引擎 (`AnalyzeUrl` + `evalJS`) | 本地 CookieManager / 源变量 | 只依赖目标网站 |
 * | **远端解析** (本文件) | 服务端 Rhino 引擎 | 服务端 `CookieStore(user.id)` | 依赖轻阅读后端可用 |
 *
 * 同一个书源两种跑法都能出结果。由 [BookSource.remoteParse] 逐条开关:
 * 从轻阅读同步来的书源自动置 true (见 [QReadMapper.applyServerState]),
 * 本地导入的默认 false, 保持 legado 原生行为不变。
 *
 * # 为什么映射成本几乎为零
 * 轻阅读后端 fork 的就是 legado 的规则引擎, 它的 `book.model.SearchBook` /
 * `book.model.BookChapter` 与 legado 的同名实体字段**几乎一一对应**
 * (后端多 `userid`/`imageDecode` 等, 少 `chapterWordCount`/`respondTime` 等),
 * 而 `GSON` = `KS_JSON` 开了 `ignoreUnknownKeys = true` —— 所以后端返回的 JSON
 * 可以**直接反序列化成 legado 实体**, 不需要手写字段映射 (手写必漏字段)。
 *
 * # 章节顺序
 * 不在这里做任何顺序调整: 后端用的就是同一套规则引擎, 它给出的顺序与 legado 本地
 * 解析一致, 因此直接交给 [io.legado.app.model.webBook.BookChapterList.updateBook]
 * (它会按 `book.config.reverseToc` 处理并重排 index), 与本地路径行为完全一致。
 */
object QReadRemoteBook {

    /** 后端 `search(..., type)`: 1 = searchBook(搜索), 2 = exploreBook(发现)。 */
    private const val TYPE_SEARCH = 1
    private const val TYPE_EXPLORE = 2

    /** 后端未连接时的统一文案 (调用方会把它展示给用户)。 */
    const val ERR_NOT_CONNECTED = "轻阅读后端未连接, 该源需要远端解析"

    /** 取当前会话 (地址 + token), 未连接直接抛 —— 远端解析没有本地兜底可言。 */
    private fun requireSession(): Pair<String, String> {
        val url = QReadSession.serverUrl
        val token = QReadSession.accessToken
        if (url.isNullOrBlank() || token.isNullOrBlank()) {
            throw IllegalStateException(ERR_NOT_CONNECTED)
        }
        return url to token
    }

    /**
     * 搜索 / 发现。
     *
     * @param key 搜索词 (搜索) 或发现规则的 url (发现), 对应 [WebBook] 的 `key` 形参
     * @param isSearch true = 搜索, false = 发现
     */
    suspend fun search(
        bookSource: BookSource,
        key: String,
        page: Int,
        isSearch: Boolean,
    ): BookListPage {
        val (url, token) = requireSession()
        val resp = QReadApi.searchBook(
            serverUrl = url,
            accessToken = token,
            bookSourceUrl = bookSource.bookSourceUrl,
            key = key,
            page = page,
            type = if (isSearch) TYPE_SEARCH else TYPE_EXPLORE,
        )
        if (!resp.isSuccess) {
            throw IllegalStateException(resp.errorMsg.ifBlank { "远端搜索失败" })
        }
        val arr = resp.data as? JsonArray ?: return BookListPage(ArrayList(), false)
        val books = ArrayList<SearchBook>(arr.size)
        arr.forEach { element ->
            GSON.fromJsonObject<SearchBook>(element.toString())
                .onFailure { AppLog.put("远端搜索结果解析失败: ${it.message}") }
                .getOrNull()
                ?.let { book ->
                    // 后端正常情况下已填好 origin/originName, 但个别路径可能留空;
                    // 留空会导致加入书架后按 origin 找不到书源, 这里兜底补上。
                    if (book.origin.isBlank()) book.origin = bookSource.bookSourceUrl
                    if (book.originName.isBlank()) book.originName = bookSource.bookSourceName
                    books.add(book)
                }
        }
        // 后端不返回「还有没有下一页」, 用「本页非空」乐观判定:
        // 最坏是多发一次空请求, 但绝不会漏页 (比按 size 猜阈值稳)。
        return BookListPage(books, hasNextPage = books.isNotEmpty())
    }

    /**
     * 取目录。返回后端解析出的原始顺序, 由调用方交给
     * [io.legado.app.model.webBook.BookChapterList.updateBook] 落库。
     */
    suspend fun getChapterList(bookSource: BookSource, book: Book): List<BookChapter> {
        val (url, token) = requireSession()
        val resp = QReadApi.getChapterList(
            serverUrl = url,
            accessToken = token,
            bookSourceUrl = bookSource.bookSourceUrl,
            bookUrl = book.bookUrl,
        )
        if (!resp.isSuccess) {
            throw IllegalStateException(resp.errorMsg.ifBlank { "远端取目录失败" })
        }
        val arr = resp.data as? JsonArray ?: return emptyList()
        val chapters = ArrayList<BookChapter>(arr.size)
        arr.forEach { element ->
            GSON.fromJsonObject<BookChapter>(element.toString())
                .onFailure { AppLog.put("远端目录解析失败: ${it.message}") }
                .getOrNull()
                ?.let { chapter ->
                    if (chapter.bookUrl.isBlank()) chapter.bookUrl = book.bookUrl
                    chapters.add(chapter)
                }
        }
        return chapters
    }

    /**
     * 取正文。后端 `/getBookContent` 的 `data` 是**字符串** (正文), 不是对象。
     *
     * @param index 章节序号 (用 [BookChapter.index], 与后端目录的 index 同一套)
     */
    suspend fun getContent(book: Book, bookChapter: BookChapter): String {
        val (url, token) = requireSession()
        val resp = QReadApi.getBookContent(
            serverUrl = url,
            accessToken = token,
            bookUrl = book.bookUrl,
            index = bookChapter.index,
        )
        if (!resp.isSuccess) {
            throw IllegalStateException(resp.errorMsg.ifBlank { "远端取正文失败" })
        }
        return (resp.data as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}
