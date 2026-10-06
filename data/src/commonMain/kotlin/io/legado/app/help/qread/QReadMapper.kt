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
 * 两边模型同源 (轻阅读 fork 了 legado 的规则引擎), 字段名几乎一一对应, 但有 **2 处
 * 类型/语义不同**, 直接反射拷贝会出错, 必须显式转换:
 *
 * | 字段 | 轻阅读后端 | legado | 处理 |
 * |---|---|---|---|
 * | 书架 `durChapterPos` | `Double` **章节内比例 0.0~1.0** | `Int` **正文字符偏移** | 归零, 见下 |
 * | 书架 `type` | `Int` **单值索引** 0 文本 / 1 音频 / 2 图片 | `Int` **`BookType` 位掩码** | [bookTypeOf] |
 *
 * 另有一处**不是类型差异而是关联键差异**: 书架 `bookgroup` 是分组**名称**,
 * 而 legado 的 `Book.group` 是本地分组 **id**, 需要调用方先建映射。
 *
 * # 书源不需要转换 (重要)
 * 书源走 [QReadApi.getBookSourceJson], 后端直接返回 legado 格式的 `bookSource.json`
 * 数组字符串, 用 `GSON.parseToJsonElement(...)` 拆数组后**逐条**
 * `GSON.fromJsonObject<BookSource>(...)` 即可。**不要逐字段手写映射** ——
 * 后端书源有 25+ 个字段 (`header` / `jsLib` / `concurrentRate` / `enabledCookieJar` /
 * `loginUi` / `exploreUrl` ...), 手写映射必漏, 漏掉 `header`/`jsLib` 会让整批书源不可用。
 *
 * # 关于 durChapterPos 的有损转换 (已实测确认)
 * 后端 `BookshelfController.getBookshelfPage` 把 `durChapterPos` 钳到 `0.0..2.0`,
 * 线上数据全是干净分数 (1/9=0.1111、1/4=0.25、13/16=0.8125 ...), 且后端**只存不解释**
 * (实测写入 0.5 后读回就是 0.5) —— 所以它确实是**章节内比例**, 官方客户端发的也是比例。
 *
 * 而 legado 的 `durChapterPos` 是**正文字符索引**。两者换算需要正文长度, 同步阶段拿不到,
 * 因此**下载方向只能丢弃** (置 0), 即: 进度同步的粒度是「读到第几章」, 不是「第几章第几行」。
 *
 * 上传方向同理: legado 的字符偏移若直接当 `pos` 传上去, 超过 2 会被后端钳成 0 (等于丢进度),
 * 所以 [QReadSync.uploadBookProgress] 必须先按正文长度换算成比例, 见那里的说明。
 */
object QReadMapper {

    /**
     * 后端书籍类型索引 → legado `BookType` 位掩码。
     *
     * 后端 `Booklist.type` 的 setter 会把写入值归一化成 **0/1/2**
     * (`32,1 -> 1`、`64,2 -> 2`、其余 -> `0`), 而 legado 的 `Book.type` 是位掩码
     * (`text = 8`、`audio = 32`、`image = 64`)。
     *
     * **直接把 0/1/2 赋给 `Book.type` 会出问题**: `type = 0` 会让
     * `Book.isOnLineTxt` (= `!isLocal && isType(BookType.text)`) 判为 false,
     * 书会被当成「没有类型」, 后续按类型分流的逻辑 (阅读/听书/看图) 全部走偏。
     *
     * `else` 分支兜底: 若某个值本身就是合法的 `BookType` 位掩码 (早期版本可能直接存
     * legado 的值), 原样保留; 否则退回 `text`。
     */
    fun bookTypeOf(remoteType: Int?): Int = when (remoteType) {
        null, 0 -> BookType.text
        1, BookType.audio -> BookType.audio
        2, BookType.image -> BookType.image
        else -> remoteType.takeIf { it and BookType.allBookType != 0 } ?: BookType.text
    }

    /**
     * 把后端**表列**里的权威状态覆盖到「从 `bookSource.json` 解析出来的书源」上。
     *
     * # 为什么必须做这一步 (这是最容易漏的一环)
     * 后端的书源分两处存:
     *
     * | 存储位置 | 内容 | 谁在写 |
     * |---|---|---|
     * | `book_source.json` / `user_book_source.json` (**LONGTEXT**) | 完整书源规则 (`rule*`/`searchUrl`/`header`/`jsLib`/`loginUrl`/`loginUi` ...) | 上传书源时的**快照** |
     * | 表**列** `enabled` / `enabledExplore` / `bookSourceGroup` / `sourceorder` / `bookSourceType` | 启用状态、启用发现、分组、排序、类型 | 后台管理界面/App 改的是**列** |
     *
     * 后端 `getbookSourcejson` 只拼 `json` 列:
     * ```kotlin
     * val bookSource = ...toBaseSource()
     * s = "$s ${bookSource.json}"      // ← 完全没带 enabled 列
     * ```
     * 而 `/getBookSourcesNew` 才是从列里取值 (`"enabled" to it.enabled`)。
     *
     * 后果: 用户在后端点「禁用」只改列, `json` 里的 `enabled` 仍是上传时的 `true`
     * → 只走 json 同步会让**所有书源都变成启用**, 禁用状态全部丢失。同理分组。
     *
     * 所以 [QReadSync] 的书源同步是「json 拿规则 + brief 拿状态」两路合流, 这里负责合流。
     *
     * @param brief 来自 `/getBookSourcesNew`, 字段取自后端表列, 是**权威值**
     */
    fun applyServerState(src: BookSource, brief: QReadSourceBrief): BookSource {
        // legado 的 `enabled` / `enabledExplore` 是**非空** Boolean, 而 brief 里声明成了
        // 可空 (防御性, 见 [QReadSourceBrief.enabled] 的说明)。所以用 `?.let`:
        // 后端给了值就用后端的 (权威), 没给就保留 json 里的旧值 —— 绝不能因为一个 null
        // 就让整批同步抛异常。
        brief.enabled?.let { src.enabled = it }
        brief.enabledExplore?.let { src.enabledExplore = it }
        // 分组则相反, **直接赋值**而不是 `?:` 保留旧值: 后端列里 `null` 就是「未分组」,
        // 是有意义的语义 (Snack 会省略该 key, kotlinx 用默认值 null 接住)。
        // 保留 json 里的旧分组反而会同步出一个后端已经不存在的分组。
        src.bookSourceGroup = brief.bookSourceGroup
        // 轻阅读是**远端解析** (规则在服务端 Rhino 里跑), 所以从它同步来的书源一律标记为
        // 远端解析 —— 否则本地跑一遍同样的规则, 既拿不到服务端的登录态, 也白等一次网络往返。
        // 与其它字段一样**以远端为准**: 手动改成 false 后, 下次同步会被重置回 true
        // (这与本同步「不合并本地改动」的既有策略一致)。
        src.remoteParse = true
        return src
    }

    /**
     * 书籍转换。
     *
     * @param groupId 已解析好的本地分组 id; 调用方需先用
     *   [QReadSync.resolveGroupIdsLocked] 把后端分组名映射成本地 id, 解析不到传 0 (未分组)。
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
            // 后端是单值索引, legado 是位掩码 —— 必须转换, 见 bookTypeOf 的说明。
            type = bookTypeOf(src.type),
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
