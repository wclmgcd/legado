package io.legado.app.help.qread

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.serialization.Serializable

/**
 * 轻阅读后端模型 → legado 实体 的转换层。
 *
 * # 为什么需要转换
 * 两边模型同源 (轻阅读 fork 了 legado 的规则引擎), 绝大多数字段名一致, 但有三处
 * **类型/语义不同**, 直接反射拷贝会出错, 必须显式转换:
 *
 * | 字段 | 轻阅读后端 | legado | 处理 |
 * |---|---|---|---|
 * | 书源 `ruleXxx` | 结构化对象 | JSON **字符串** | [JsonObject.toString] 原样转 |
 * | 书架 `durChapterPos` | `Double` (章节内比例) | `Int` (正文字符偏移) | 归零, 见下 |
 * | 书架 `bookgroup` | 分组**名称** | `Book.group: Long` (分组 id) | 按名称查 id |
 *
 * # 关于 durChapterPos 的有损转换 (重要)
 * 后端 `BookshelfController.getBookshelfPage` 会把 `durChapterPos` 钳到 `0.0..2.0`,
 * 说明它是**章节内比例**, 而 legado 的 `durChapterPos` 是**正文字符索引**。
 * 两者换算需要正文长度, 同步阶段拿不到, 因此这里**只能丢弃** (置 0), 即:
 * 进度同步的粒度是「读到第几章」, 不是「第几章第几行」。
 * 这与官方 qreadwebdav 桥接插件的限制一致。若要精确到行, 需要 Phase 3 在
 * 拉取正文后按比例回算, 或改用 legado 侧 `variable` 存比例。
 */
object QReadMapper {

    /**
     * 书源转换。
     *
     * `ruleXxx` 直接把后端的 JSON 对象序列化成字符串塞给 legado —— legado 实体上的
     * `RawJsonStringSerializer` 正是为「rule 可能是对象也可能是字符串」设计的,
     * 所以不必为 5 种规则各建一套 Kotlin 模型, 后端加规则字段也不会漏。
     */
    fun toLegadoBookSource(src: QReadBookSource): BookSource = BookSource(
        bookSourceUrl = src.bookSourceUrl,
        bookSourceName = src.bookSourceName,
        bookSourceGroup = src.bookSourceGroup,
        bookSourceType = src.bookSourceType,
        bookUrlPattern = src.bookUrlPattern,
        customOrder = src.customOrder,
        enabled = src.enabled,
        enabledExplore = src.enabledExplore,
        loginCheckJs = src.loginCheckJs,
        coverDecodeJs = src.coverDecodeJs,
        bookSourceComment = src.bookSourceComment,
        variableComment = src.variableComment,
        lastUpdateTime = src.lastUpdateTime,
        respondTime = src.respondTime,
        weight = src.weight,
        exploreUrl = src.exploreUrl,
        searchUrl = src.searchUrl,
        ruleExplore = src.ruleExplore?.toString(),
        ruleSearch = src.ruleSearch?.toString(),
        ruleBookInfo = src.ruleBookInfo?.toString(),
        ruleToc = src.ruleToc?.toString(),
        ruleContent = src.ruleContent?.toString(),
    )

    /**
     * 书籍转换。
     *
     * @param groupId 已解析好的本地分组 id; 调用方需先用
     *   [QReadSync.resolveGroupIds] 把后端分组名映射成本地 id, 解析不到传 0 (未分组)。
     */
    fun toLegadoBook(src: QReadBook, groupId: Long): Book {
        val url = src.bookUrl.orEmpty()
        return Book(
            bookUrl = url,
            // 后端 tocUrl 可能为空, 用 bookUrl 兜底 —— legado 的目录解析以 tocUrl 为入口,
            // 留空会导致刷新目录直接失败。
            tocUrl = src.tocUrl?.takeIf { it.isNotBlank() } ?: url,
            origin = src.origin?.takeIf { it.isNotBlank() } ?: BookType.localTag,
            originName = src.originName ?: "",
            originOrder = src.originOrder ?: 0,
            name = src.name ?: "",
            author = src.author ?: "",
            kind = src.kind,
            customTag = src.customTag,
            coverUrl = src.coverUrl,
            customCoverUrl = src.customCoverUrl,
            intro = src.intro,
            customIntro = src.customIntro,
            charset = src.charset,
            type = src.type ?: BookType.text,
            group = groupId,
            latestChapterTitle = src.latestChapterTitle,
            latestChapterTime = src.latestChapterTime ?: 0L,
            lastCheckTime = src.lastCheckTime ?: 0L,
            lastCheckCount = src.lastCheckCount ?: 0,
            totalChapterNum = src.totalChapterNum ?: 0,
            durChapterTitle = src.durChapterTitle,
            durChapterIndex = src.durChapterIndex ?: 0,
            // 有损: 后端是章节内比例, legado 是字符偏移, 同步阶段无法换算, 见类注释。
            durChapterPos = 0,
            durChapterTime = src.durChapterTime ?: 0L,
            wordCount = src.wordCount,
        )
    }

    /**
     * 判断本地书是否需要被后端数据覆盖。
     *
     * 规则: 后端进度更靠后 (章节序号更大), 或同章节但后端 `durChapterTime` 更新,
     * 才覆盖。**只前进不后退**, 避免一次网络抖动把用户刚读的进度拉回去
     * (与 `AppWebDavShared.downloadAllBookProgress` 的冲突策略保持一致)。
     */
    fun shouldOverwrite(local: Book, remote: QReadBook): Boolean {
        val remoteIndex = remote.durChapterIndex ?: 0
        if (remoteIndex > local.durChapterIndex) return true
        if (remoteIndex < local.durChapterIndex) return false
        val remoteTime = remote.durChapterTime ?: 0L
        return remoteTime > local.durChapterTime
    }

    /** 把后端数据合并进已有的本地书籍 (保留本地阅读配置等字段)。 */
    fun mergeInto(local: Book, remote: QReadBook, groupId: Long): Book {
        val mapped = toLegadoBook(remote, groupId)
        return local.apply {
            name = mapped.name.ifBlank { local.name }
            author = mapped.author.ifBlank { local.author }
            tocUrl = mapped.tocUrl
            origin = mapped.origin
            originName = mapped.originName.ifBlank { local.originName }
            originOrder = mapped.originOrder
            kind = mapped.kind ?: local.kind
            customTag = mapped.customTag ?: local.customTag
            coverUrl = mapped.coverUrl ?: local.coverUrl
            customCoverUrl = mapped.customCoverUrl ?: local.customCoverUrl
            intro = mapped.intro ?: local.intro
            customIntro = mapped.customIntro ?: local.customIntro
            charset = mapped.charset ?: local.charset
            type = mapped.type
            group = groupId
            latestChapterTitle = mapped.latestChapterTitle ?: local.latestChapterTitle
            latestChapterTime = mapped.latestChapterTime
            lastCheckTime = mapped.lastCheckTime
            lastCheckCount = mapped.lastCheckCount
            totalChapterNum = mapped.totalChapterNum
            wordCount = mapped.wordCount ?: local.wordCount
            durChapterTitle = mapped.durChapterTitle ?: local.durChapterTitle
            durChapterIndex = mapped.durChapterIndex
            durChapterTime = mapped.durChapterTime
        }
    }

    /**
     * 书签转换。
     *
     * 注意两边**关联键不同**: 后端书签挂在 `bookUrl` 上, legado 的 [Bookmark] 用
     * `(bookName, bookAuthor)` 关联 (且有唯一索引), 所以调用方必须先把 url 换成
     * 书名/作者再调本函数。
     *
     * 另外 legado 的 [Bookmark] **没有 bookUrl 字段**, 不要往构造参数里塞。
     * `time` 是主键, 后端没给时间时用当前时间兜底, 否则多条会撞主键。
     */
    fun toLegadoBookmark(
        remote: QReadBookmark,
        bookName: String,
        bookAuthor: String,
    ): Bookmark = Bookmark(
        time = remote.time ?: systemCurrentTimeMillis(),
        bookName = bookName,
        bookAuthor = bookAuthor,
        chapterIndex = remote.index ?: 0,
        // 后端 pos 是章节内比例 (Double), legado chapterPos 是字符偏移, 有损, 见类注释。
        chapterPos = 0,
        chapterName = remote.name ?: "",
        bookText = remote.name ?: "",
        content = "",
    )

    /** 后端书签模型 (对应 `BookMarkController` 返回)。 */
    @Serializable
    data class QReadBookmark(
        val id: String? = null,
        val url: String? = null,
        val name: String? = null,
        val index: Int? = null,
        val pos: Double? = null,
        val time: Long? = null,
    )

    /**
     * 搜索记录 → 后端 KV 里存的 JSON 结构。
     *
     * 用 `word` 作主键与 legado 的 [SearchKeyword] 对齐, 便于双向合并。
     */
    @Serializable
    data class SearchHistoryPayload(
        val version: Int = 1,
        val items: List<SearchKeyword> = emptyList(),
    )

    /**
     * 搜索记录合并: 按 `word` 取并集, `usage` 取较大值, `lastUseTime` 取较新者。
     *
     * 不做「后写覆盖」—— 两台设备各自搜过的词都要留住, 否则每次同步都会丢词。
     */
    fun mergeSearchKeywords(
        local: List<SearchKeyword>,
        remote: List<SearchKeyword>,
    ): List<SearchKeyword> {
        val map = linkedMapOf<String, SearchKeyword>()
        local.forEach { map[it.word] = it }
        remote.forEach { r ->
            val l = map[r.word]
            map[r.word] = if (l == null) {
                r
            } else {
                SearchKeyword(
                    word = l.word,
                    usage = maxOf(l.usage, r.usage),
                    lastUseTime = maxOf(l.lastUseTime, r.lastUseTime),
                )
            }
        }
        return map.values.toList()
    }
}
