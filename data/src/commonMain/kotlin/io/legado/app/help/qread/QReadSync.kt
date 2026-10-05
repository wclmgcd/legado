package io.legado.app.help.qread

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Server
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.systemCurrentTimeMillis
import io.legado.app.utils.toJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 轻阅读后端会话 (内存态)。
 *
 * # 为什么不落盘 token
 * 后端 `UserController.login` 对每个用户有 **20 个设备上限**, 超了会把该用户所有 token
 * 全部作废; 且管理员可在后台重置。持久化一个可能已失效的 token, 只会让首次同步莫名
 * 失败。所以这里只存内存, 每次冷启动用配置里的账号密码重新登录。
 *
 * 配置本身存在 [Server] 实体里 (`type = TYPE.QREAD`, `config` 是
 * [Server.QReadConfig] 的 JSON), 复用已有的「远程服务」数据表, 不新增偏好键。
 */
object QReadSession {

    private var server: Server? = null
    private var config: Server.QReadConfig? = null
    private var token: String? = null

    /** 是否已登录 (token 与配置都在)。 */
    val isConnected: Boolean get() = token != null && config != null

    val serverUrl: String? get() = config?.url
    val accessToken: String? get() = token
    val serverName: String? get() = server?.name
    val serverId: Long? get() = server?.id
    val account: String? get() = config?.username

    /**
     * 登录指定后端。成功后 [isConnected] 为 true。
     *
     * 用 [Result] 而不是抛异常: 调用方多在设置界面/启动流程, 需要把失败原因展示给用户。
     *
     * 注意后端 `/login` 的 `data` 是**对象** `{"accessToken":"..."}`, 取字段要用
     * `resp.data?.accessToken` —— 直接把它当字符串反序列化会抛
     * `Unexpected JSON token ... Expected beginning of the string, but got {`。
     */
    suspend fun login(server: Server): Result<Unit> {
        val cfg = server.getQReadConfig()
            ?: return Result.failure(IllegalStateException("该服务器不是轻阅读后端"))
        if (cfg.url.isBlank()) {
            return Result.failure(IllegalStateException("后端地址为空"))
        }
        val resp = QReadApi.login(cfg.url, cfg.username, cfg.password)
        val newToken = resp.data?.accessToken
        if (!resp.isSuccess || newToken.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException(resp.errorMsg.ifBlank { "登录失败，请检查账号密码与后端版本" })
            )
        }
        this.server = server
        this.config = cfg
        this.token = newToken
        return Result.success(Unit)
    }

    /** 清空会话 (保留 [Server] 配置)。 */
    fun logout() {
        token = null
        server = null
        config = null
    }

    /** 已配置的轻阅读后端列表 (供设置界面选择)。 */
    suspend fun availableServers(): List<Server> =
        AppDbProviders.get().serverDao.all().filter { it.type == Server.TYPE.QREAD }

    /** 保存或更新一个轻阅读后端配置, 返回落库后的实体。 */
    suspend fun saveServer(
        name: String,
        url: String,
        username: String,
        password: String,
        id: Long? = null,
    ): Server {
        val entity = Server(
            id = id ?: systemCurrentTimeMillis(),
            name = name,
            type = Server.TYPE.QREAD,
            config = GSON.toJson(Server.QReadConfig(url.trim(), username, password)),
        )
        AppDbProviders.get().serverDao.insert(entity)
        return entity
    }
}

/**
 * 轻阅读后端 ↔ legado 本地库 的同步编排。
 *
 * # 同步方向
 * | 数据 | 方向 | 说明 |
 * |---|---|---|
 * | 书源 | 后端 → 本地 | 后端为准, 按 bookSourceUrl 覆盖 |
 * | 书架 | 后端 → 本地 | 后端为准; 进度只前进不后退 |
 * | 进度 | 本地 → 后端 | 阅读时/手动触发上传 |
 * | 搜索记录 | 双向合并 | 按词取并集, 不会互相覆盖 |
 * | 阅读记录 | 双向合并 | 同上 |
 *
 * # 并发
 * 全部同步入口共用一把 [mutex], 避免「同步中用户又点了一次」导致同一页数据被写入两遍。
 * 单次同步失败只记 [AppLog], 不中断其它阶段 —— 同步是尽力而为, 不该让一次网络抖动
 * 把整轮同步废掉。
 */
object QReadSync {

    private val mutex = Mutex()

    /** 搜索记录在后端 KV 里的 key。改这个值等于放弃已有的云端记录。 */
    private const val KV_SEARCH_HISTORY = "legado.searchHistory"

    /** 阅读记录在后端 KV 里的 key。 */
    private const val KV_READ_RECORD = "legado.readRecord"

    /**
     * 书源批量导出的分批大小。
     *
     * 后端 `getbookSourcejson` 是把数组里每个源的 json **直接拼成一个字符串**返回,
     * 没有分页也没有大小保护 —— 一次传 150 个会得到几 MB 的响应。分批更稳, 也便于
     * 单批失败时只丢一批而不是全部。
     */
    private const val SOURCE_EXPORT_BATCH = 30

    /** 一轮同步的结果, 供 UI 提示。 */
    data class Result(
        val sourceCount: Int = 0,
        val bookCount: Int = 0,
        val groupCount: Int = 0,
        val searchCount: Int = 0,
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = error == null
    }

    /** 全量同步: 书源 → 分组 → 书架 → 搜索记录。 */
    suspend fun syncAll(): Result = mutex.withLock {
        if (!QReadSession.isConnected) {
            return@withLock Result(error = "未连接轻阅读后端")
        }
        return@withLock try {
            val sourceCount = syncBookSourcesLocked()
            val shelf = syncBookshelfLocked()
            val searchCount = syncSearchHistoryLocked()
            Result(
                sourceCount = sourceCount,
                bookCount = shelf.first,
                groupCount = shelf.second,
                searchCount = searchCount,
            )
        } catch (e: Exception) {
            AppLog.put("轻阅读同步失败\n${e.message}", e)
            Result(error = e.message ?: "同步失败")
        }
    }

    /** 仅同步书源。返回写入条数。 */
    suspend fun syncBookSources(): Int = mutex.withLock { syncBookSourcesLocked() }

    /** 仅同步书架 (含分组)。返回 (书籍数, 分组数)。 */
    suspend fun syncBookshelf(): Pair<Int, Int> = mutex.withLock { syncBookshelfLocked() }

    /** 仅同步搜索记录。返回合并后条数。 */
    suspend fun syncSearchHistory(): Int = mutex.withLock { syncSearchHistoryLocked() }

    // ---------------------------------------------------------------------
    // 具体实现 (调用方必须已持有 mutex 且已登录)
    // ---------------------------------------------------------------------

    /**
     * 书源同步 —— **三步协议 + 两路合流**, 少一步都拿不到完整数据。
     *
     * ```
     * 1) GET  /getBookSourcesPage            -> {page, md5}   (后端顺带把每页写进缓存)
     * 2) GET  /getBookSourcesNew?md5&page=N  -> [精简投影...]  (拿 bookSourceUrl + 权威状态)
     * 3) POST /getbookSourcejson  body=[url] -> "[完整书源 JSON 数组]"  (拿规则)
     * ```
     *
     * # 为什么不能只用第 2 步
     * `/getBookSourcesNew` 返回的是**精简投影**, 每条只有 9 个字段
     * (`checkKeyWord` / `variableComment` / `bookSourceGroup` / `loginUrl` / `loginUi` /
     * `bookSourceName` / `bookSourceUrl` / `enabledExplore` / `enabled`) ——
     * **没有 `ruleSearch` / `ruleToc` / `ruleContent` / `ruleBookInfo` / `ruleExplore` /
     * `searchUrl` / `exploreUrl` / `header` / `jsLib`**。直接拿它建 `BookSource`
     * 会得到一堆「有名字没规则」的废源, 搜索、目录、正文全部不可用。
     *
     * # 为什么也不能只用第 3 步 (最容易漏的一环)
     * 后端把书源**分两处存**:
     * - `json` 列 (LONGTEXT): 完整规则 (`rule*` / `searchUrl` / `header` / `jsLib` /
     *   `loginUrl` / `loginUi` ...), 由 `Gson().toJson(bookSource)` 写入;
     * - 表**列** `enabled` / `enabledExplore` / `bookSourceGroup` / `sourceorder`:
     *   后台管理界面和 App 改的是这些。
     *
     * 而 `getbookSourcejson` 只拼 `json` 列 (`s = "$s ${bookSource.json}"`), **不带列**;
     * `json` 里的 `enabled` 只是「上传书源那一刻」的快照 —— 后端 `addorupdate` 更新时
     * 特意 `source.enabled = it.enabled` 保住列上的用户选择, 却仍把 json 覆盖成新内容。
     *
     * 所以**只走第 3 步会让后台禁用的书源同步过来全是启用的、分组回到上传时的旧值**。
     * 必须用第 2 步的 brief 覆盖这几个字段, 见 [QReadMapper.applyServerState]。
     *
     * # 为什么第 3 步不需要字段映射
     * 后端存的就是 legado 格式的 `bookSource.json` (轻阅读 fork 了 legado 的规则引擎),
     * 25+ 个字段与 legado 的 `BookSource` 实体一一对应, 连 `ruleSearch` 这种
     * 「JSON 对象 vs 字符串」的差异也由实体上的 `RawJsonStringSerializer` 处理掉了。
     * 所以拆成数组后**逐条** `GSON.fromJsonObject<BookSource>(...)` 即可,
     * **手写字段映射必漏字段** (但上面那 3 个状态字段是例外 —— 它们压根不在 json 里)。
     *
     * 以**后端为准** (而不是合并): 轻阅读的书源由服务端统一维护, 本地改动会在下次
     * 同步被覆盖, 这是预期行为 —— 想保留本地改动就不要用后端书源同步。
     */
    private suspend fun syncBookSourcesLocked(): Int {
        val url = QReadSession.serverUrl ?: return 0
        val token = QReadSession.accessToken ?: return 0

        // ---- 第 1 步: 分页准备 ----
        val pageResp = QReadApi.getBookSourcesPage(url, token)
        if (!pageResp.isSuccess) {
            AppLog.put("轻阅读书源准备失败: ${pageResp.errorMsg}")
            return 0
        }
        val md5 = pageResp.data?.md5.orEmpty()
        if (md5.isBlank()) {
            AppLog.put("轻阅读书源 md5 为空，跳过")
            return 0
        }
        val totalPages = (pageResp.data?.page ?: 1).coerceAtLeast(1)

        // ---- 第 2 步: 逐页取 URL + 服务端状态 ----
        // 用 LinkedHashMap 去重 (同一站点出现多次是正常的: http/https 前缀不同、同一书源被
        // 分组多次导入等), 重复传会让第 3 步的响应白白翻倍。
        //
        // 同时把 brief **留下来**: 它的 `enabled` / `enabledExplore` / `bookSourceGroup` 取自
        // 后端**表列**, 是权威值; 而第 3 步 json 里这几个字段是「上传书源那一刻」的旧快照。
        // 后端点「禁用」只改列不改 json, 所以只走 json 会让禁用状态全部丢失 ——
        // 合流逻辑见 [QReadMapper.applyServerState]。
        val briefByUrl = linkedMapOf<String, QReadSourceBrief>()
        for (page in 1..totalPages) {
            val resp = QReadApi.getBookSourcesNew(url, token, md5, page)
            if (!resp.isSuccess) {
                AppLog.put("轻阅读书源第 $page 页失败: ${resp.errorMsg}")
                continue
            }
            resp.data.orEmpty().forEach { brief ->
                brief.bookSourceUrl.takeIf { it.isNotBlank() }?.let { briefByUrl[it] = brief }
            }
        }
        if (briefByUrl.isEmpty()) return 0

        // ---- 第 3 步: 分批导出完整 JSON 并落库 ----
        val dao = AppDbProviders.get().bookSourceDao
        var written = 0
        var skipped = 0
        briefByUrl.keys.chunked(SOURCE_EXPORT_BATCH).forEach { batch ->
            val resp = QReadApi.getBookSourceJson(url, token, batch)
            if (!resp.isSuccess) {
                AppLog.put("轻阅读书源导出失败(${batch.size} 个): ${resp.errorMsg}")
                return@forEach
            }
            val jsonArray = resp.data
            if (jsonArray.isNullOrBlank()) return@forEach

            // 逐条解析, 不用 `GSON.fromJsonArray<BookSource>` —— 后者是「整批一起解」,
            // 132 个真实书源里只要有一个字段畸形, 整批 30 个全丢。
            // 逐条解析能保住其余, 并把坏的那条记进日志。
            val array = runCatching { GSON.parseToJsonElement(jsonArray) }
                .getOrNull() as? JsonArray
            if (array == null) {
                AppLog.put("轻阅读书源 JSON 不是数组, 跳过本批(${batch.size} 个)")
                return@forEach
            }
            val valid = array.mapNotNull { element ->
                // 用 GSON.fromJsonObject 而不是 kotlinx 的 Json.decodeFromJsonElement:
                // 后者是 kotlinx 的顶层扩展函数, 不显式 import 时编译器会匹配到需要传
                // DeserializationStrategy 的成员重载而报「Cannot infer type for value
                // parameter 'T'」。fromJsonObject 内部走 Json.decodeFromString (成员函数),
                // 全仓已验证可编译; 返回 Result, 坏的那条只记日志不影响其余。
                GSON.fromJsonObject<BookSource>(element.toString())
                    .onFailure { skipped++ }
                    .getOrNull()
                    ?.takeIf { it.bookSourceUrl.isNotBlank() }
                    // json 只给「规则」; 启用状态 / 启用发现 / 分组必须用第 2 步的 brief
                    // (后端表列 = 权威值) 覆盖, 否则后台禁用的书源同步过来全是启用的。
                    ?.also { src ->
                        briefByUrl[src.bookSourceUrl]?.let { QReadMapper.applyServerState(src, it) }
                    }
            }
            if (valid.isEmpty()) return@forEach
            dao.insert(*valid.toTypedArray())
            written += valid.size
        }
        if (skipped > 0) {
            AppLog.put("轻阅读书源同步: 有 $skipped 个源解析失败已跳过, 成功写入 $written 个")
        }
        return written
    }

    /**
     * 书架同步: 分组 → 书籍。
     *
     * 后端是两步协议: `getBookshelfPage` 拿 `(总页数, md5)` 并把每页写进服务端缓存,
     * 再按页调 `getBookshelfNew`。跳过第一步会拿到空数组, 这点容易踩坑。
     *
     * @return (书籍数, 分组数)
     */
    private suspend fun syncBookshelfLocked(): Pair<Int, Int> {
        val url = QReadSession.serverUrl ?: return 0 to 0
        val token = QReadSession.accessToken ?: return 0 to 0

        val pageResp = QReadApi.getBookshelfPage(url, token)
        if (!pageResp.isSuccess) {
            AppLog.put("轻阅读书架准备失败: ${pageResp.errorMsg}")
            return 0 to 0
        }
        val md5 = pageResp.data?.md5.orEmpty()
        if (md5.isBlank()) {
            AppLog.put("轻阅读书架 md5 为空，跳过")
            return 0 to 0
        }
        val totalPages = (pageResp.data?.page ?: 1).coerceAtLeast(1)

        val (groupIdByName, groupCount) = resolveGroupIdsLocked(url, token, md5)

        val bookDao = AppDbProviders.get().bookDao
        var bookCount = 0
        for (page in 1..totalPages) {
            val resp = QReadApi.getBookshelf(url, token, md5, page)
            if (!resp.isSuccess) {
                AppLog.put("轻阅读书架第 $page 页失败: ${resp.errorMsg}")
                continue
            }
            resp.data.orEmpty().forEach { remote ->
                val remoteUrl = remote.bookUrl.orEmpty()
                if (remoteUrl.isBlank()) return@forEach
                val groupId = remote.bookgroup?.let { groupIdByName[it] } ?: 0L
                val local = bookDao.getBook(remoteUrl)
                if (local == null) {
                    bookDao.insert(QReadMapper.toLegadoBook(remote, groupId))
                } else {
                    val merged = QReadMapper.mergeInto(local, remote, groupId)
                    // 元数据总是以后端为准, 但进度只允许前进:
                    // 后端进度比本地旧时把进度字段回滚回去, 避免把用户刚读的章节拉走。
                    if (!QReadMapper.shouldOverwrite(local, remote)) {
                        merged.durChapterIndex = local.durChapterIndex
                        merged.durChapterPos = local.durChapterPos
                        merged.durChapterTitle = local.durChapterTitle
                        merged.durChapterTime = local.durChapterTime
                    }
                    bookDao.update(merged)
                }
                bookCount++
            }
        }
        return bookCount to groupCount
    }

    /**
     * 把后端分组名映射成本地 [BookGroup.groupId], 缺的分组就地建出来。
     *
     * legado 的 groupId 必须是**互不相同的 2 的幂** (它用 `sum(groupId)` 当位图判断
     * 分组归属, 见 `BookGroupDao.idsSum`), 所以不能简单递增。这里自己维护已占用位,
     * 不能用 `getUnusedId()` —— 那个方法每次读库, 而新分组还没落库, 连调会拿到同一个 id。
     *
     * @return (分组名 → groupId, 新建分组数)
     */
    private suspend fun resolveGroupIdsLocked(
        url: String,
        token: String,
        md5: String,
    ): Pair<Map<String, Long>, Int> {
        val resp = QReadApi.getBookGroups(url, token, md5)
        if (!resp.isSuccess) return emptyMap<String, Long>() to 0

        val dao = AppDbProviders.get().bookGroupDao
        val existing = dao.all()
        val byName = existing.associateBy { it.groupName }.toMutableMap()
        val usedIds = existing.filter { it.groupId >= 0 }.map { it.groupId }.toMutableSet()
        var maxOrder = existing.maxOfOrNull { it.order } ?: 0

        val created = mutableListOf<BookGroup>()
        val result = mutableMapOf<String, Long>()

        resp.data.orEmpty().forEach { g ->
            val name = g.bookgroup?.takeIf { it.isNotBlank() } ?: return@forEach
            val hit = byName[name]
            if (hit != null) {
                result[name] = hit.groupId
                return@forEach
            }
            var id = 1L
            while (id in usedIds) id = id shl 1
            usedIds.add(id)
            val entity = BookGroup(
                groupId = id,
                groupName = name,
                order = g.grouporder ?: ++maxOrder,
            )
            created.add(entity)
            byName[name] = entity
            result[name] = id
        }

        if (created.isNotEmpty()) dao.insert(*created.toTypedArray())
        return result to created.size
    }

    /**
     * 搜索记录双向合并。
     *
     * 用「并集 + 取较大 usage / 较新时间」而不是「后写覆盖」: 两台设备各搜过的词都要留住,
     * 否则每次同步都会把对方的词删掉。
     */
    private suspend fun syncSearchHistoryLocked(): Int {
        val url = QReadSession.serverUrl ?: return 0
        val token = QReadSession.accessToken ?: return 0

        val dao = AppDbProviders.get().searchKeywordDao
        val local = dao.all()

        val remoteResp = QReadApi.getItem(url, token, KV_SEARCH_HISTORY)
        val remote = remoteResp.data.asStringOrNull()
            ?.let { GSON.fromJsonObject<QReadMapper.SearchHistoryPayload>(it).getOrNull() }
            ?.items
            .orEmpty()

        val merged = QReadMapper.mergeSearchKeywords(local, remote)
        if (merged.isEmpty()) return 0

        dao.deleteAll()
        dao.insert(*merged.toTypedArray())

        QReadApi.setItem(
            url, token, KV_SEARCH_HISTORY,
            GSON.toJson(QReadMapper.SearchHistoryPayload(items = merged)),
        )
        return merged.size
    }

    // ---------------------------------------------------------------------
    // 上传
    // ---------------------------------------------------------------------

    /**
     * 把 legado 的字符偏移换算成后端要的**章节内比例** (0.0 ~ 1.0)。
     *
     * 后端 `durChapterPos` 是比例 (实测: 线上数据全是 1/9、1/4、13/16 这类干净分数,
     * 写入什么读回什么, 且 `getBookshelfPage` 会把 `> 2` 的值钳成 0)。
     * legado 存的是**正文字符偏移**, 不换算直接传 (比如 1500) 会被后端钳成 0, 等于丢进度。
     *
     * 换算需要正文长度, 拿不到时返回 0.0 —— 宁可只同步到「第几章」, 也不要把
     * 一个会被后端判为非法的值写上去。
     */
    private fun toChapterRatio(charOffset: Int, chapterLength: Int?): Double {
        val len = chapterLength ?: return 0.0
        if (len <= 0) return 0.0
        return (charOffset.toDouble() / len).coerceIn(0.0, 1.0)
    }

    /**
     * 上传单本书的阅读进度。
     *
     * 后端按 `url` 在服务端书架里匹配, 匹配不到会静默忽略 (仍返回 success),
     * 所以本地独有、后端没有的书上传进度不会报错, 也不会生效。
     *
     * @param isNew 新加入书架的书传 `"1"`, 后端会额外记一条记录
     * @param chapterLength 当前章节的**正文字符数**, 用来把 legado 的字符偏移换算成
     *   后端要的章节内比例。传 null 表示拿不到 (如批量上传), 此时只同步章节号,
     *   章内进度按 0 处理。**接线点**: 阅读器保存进度时把当前章节文本长度传进来。
     */
    suspend fun uploadBookProgress(
        book: Book,
        isNew: Boolean = false,
        chapterLength: Int? = null,
    ) {
        val url = QReadSession.serverUrl ?: return
        val token = QReadSession.accessToken ?: return
        val resp = QReadApi.saveBookProgress(
            serverUrl = url,
            accessToken = token,
            bookUrl = book.bookUrl,
            index = book.durChapterIndex,
            pos = toChapterRatio(book.durChapterPos, chapterLength),
            title = book.durChapterTitle,
            isnew = if (isNew) "1" else null,
        )
        if (!resp.isSuccess) {
            AppLog.put("轻阅读进度上传失败: ${resp.errorMsg}")
        }
    }

    /**
     * 把本地全部书籍的进度推给后端 (手动「上传进度」用)。
     *
     * 批量场景拿不到每本书当前章节的正文长度, 所以章内进度一律按 0 上传 ——
     * 只同步「读到第几章」。要精确到章内位置, 走 [uploadBookProgress] 并传 chapterLength。
     */
    suspend fun uploadAllProgress(): Int = mutex.withLock {
        if (!QReadSession.isConnected) return@withLock 0
        var count = 0
        AppDbProviders.get().bookDao.all().forEach { book ->
            val url = QReadSession.serverUrl ?: return@withLock count
            val token = QReadSession.accessToken ?: return@withLock count
            val resp = QReadApi.saveBookProgress(
                serverUrl = url,
                accessToken = token,
                bookUrl = book.bookUrl,
                index = book.durChapterIndex,
                pos = 0.0,
                title = book.durChapterTitle,
            )
            if (resp.isSuccess) count++
        }
        count
    }
}

/** 从后端 KV 取回的 `JsonElement` 里抠出字符串; 非字符串 / null 一律返回 null。 */
private fun JsonElement?.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
