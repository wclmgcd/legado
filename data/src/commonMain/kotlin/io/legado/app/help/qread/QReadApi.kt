package io.legado.app.help.qread

import io.legado.app.help.http.KmpHttpClient
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.get
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.postForm
import io.legado.app.help.http.postJson
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 轻阅读后端 HTTP 客户端。
 *
 * # 设计约束
 * - **无状态**: 所有函数显式收 `serverUrl` / `accessToken`, 不持有全局会话;
 *   会话状态由 [QReadSession] 管 (见 QReadSync.kt)。
 * - **不抛异常**: 网络/解析失败一律折成 `isSuccess = false` 的 [QReadResponse],
 *   错误信息进 `errorMsg`。同步是后台行为, 抛异常会污染调用方的协程作用域。
 * - **commonMain**: 只用 `help/http` 的 KMP 抽象, 不 import okhttp3.*,
 *   保证 iOS / 鸿蒙 target 可编译。
 *
 * # 路由约定
 * 后端路由前缀是 `/api/{v}`, `v` 是**接口版本号**, 不是路径占位符。
 * 后端 `BaseController.apiversion = 5`, 传别的值会被直接拒绝
 * ("当前后端不支持您的app，请联系管理员更新后端")。见 [API_VERSION]。
 *
 * # 请求方式
 * 后端用 Solon 框架, `@Mapping` 未指定 method 时接受所有 HTTP 方法,
 * 因此查询类接口用 GET 带 query 即可; 登录走 POST form, 与后端
 * `login(username, password, model)` 形参一一对应。
 *
 * # 两步分页协议 (最容易踩的坑)
 * 书架 / 书源 / 替换规则 / RSS / TTS 的列表接口都是**两步**:
 * 1. `GET /getXxxPage` → 返回 `{page, md5}`, **并把每页数据写进服务端缓存**;
 * 2. `GET /getXxxNew?accessToken&md5&page=N` → 由后端 `@Cache` 注解命中缓存返回数据。
 *
 * `getXxxNew` 方法体本身只 `return JsonResponse(false)`, 真正的数据来自 `@Cache` 拦截。
 * 所以**跳过第 1 步直接调第 2 步只会拿到 `isSuccess=false`**。
 *
 * 缓存 key 形如 `"getXxxNew:${accessToken}${md5}${page}"` (见后端
 * `BookshelfController` / `SourceController`), 因此 `md5` 必须与第 1 步返回的**完全一致**。
 *
 * # 已实测确认的响应形状 (后端 3.4.4)
 * | 接口 | `data` 形状 |
 * |---|---|
 * | `/login` | **对象** `{"accessToken":"..."}` |
 * | `/getUserInfo` | 对象 `{"userInfo":{username,phone,email}}` |
 * | `/getBookshelfPage` | 对象 `{page, md5}` |
 * | `/getBookSourcesPage` | 对象 `{page, md5}` |
 * | `/getBookshelfNew` | 数组 (完整 Booklist) |
 * | `/getBookSourcesNew` | 数组 (**只有 9 个字段的精简投影**) |
 * | `/getgroupNew` | 数组 (BookGroup) |
 * | `/getbookSourcejson` | **字符串**, 内容是完整书源 JSON 数组 |
 * | `/getBookread` | **字符串** (逗号分隔的已读章节) |
 * | `/getitem` | 字符串 / **字段整个缺失** |
 * | `/saveBookProgress` | **字符串** (逗号分隔的已读章节) |
 */
object QReadApi {

    /** 接口版本, 必须与后端 `BaseController.apiversion` 一致。 */
    const val API_VERSION: Int = 5

    /**
     * 宽松解析器。
     *
     * 不用全局 `KS_JSON`: 后端字段增减不受我们控制, 必须 `ignoreUnknownKeys`
     * 才不至于因后端加字段而整包解析失败。
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client: KmpHttpClient
        get() = OkHttpClientProviders.get().okHttpClient

    /** 拼 `/api/{v}` 前缀, 容忍地址带不带结尾斜杠。 */
    private fun apiBase(serverUrl: String): String =
        serverUrl.trim().trimEnd('/') + "/api/$API_VERSION"

    // ---------------------------------------------------------------------
    // 账号
    // ---------------------------------------------------------------------

    /**
     * 登录。
     *
     * 返回的 `data` 是**对象** `{"accessToken":"..."}`, 不是裸字符串
     * (对应后端 `UserController.login` 的 `.Data(mapOf("accessToken" to tocken.id))`)。
     * 取 token 用 `resp.data?.accessToken`。
     *
     * @param model 设备标识, 后端仅作记录; 传 `legado-android` 便于在后台区分设备。
     */
    suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
        model: String = "legado-android",
    ): QReadResponse<QReadLoginResult> = request {
        client.newCallStrResponse {
            url("${apiBase(serverUrl)}/login")
            postForm(
                mapOf(
                    "username" to username,
                    "password" to password,
                    "model" to model,
                )
            )
        }.body
    }

    /** 校验 token 是否仍有效, 返回用户名等信息。 */
    suspend fun getUserInfo(
        serverUrl: String,
        accessToken: String,
    ): QReadResponse<JsonElement> = requestJson("/getUserInfo", serverUrl, accessToken)

    // ---------------------------------------------------------------------
    // 书源
    // ---------------------------------------------------------------------

    /**
     * 书源分页准备 —— **必须先调**, 否则 [getBookSourcesNew] 只会返回空。
     *
     * 返回 `{page, md5}`; `md5` 是本次书源快照标识 (后端 `sourcemd5 + source`)。
     */
    suspend fun getBookSourcesPage(
        serverUrl: String,
        accessToken: String,
    ): QReadResponse<QReadPageInfo> = request {
        client.newCallStrResponse {
            get("${apiBase(serverUrl)}/getBookSourcesPage", mapOf("accessToken" to accessToken))
        }.body
    }

    /**
     * 取书源列表某一页 (页码从 1 开始)。须先用 [getBookSourcesPage] 拿到 md5。
     *
     * 返回的是**精简投影** (见 [QReadSourceBrief]) —— 不要拿它直接建 legado 的 `BookSource`,
     * 那样只有名字没有规则。它的用途有两个:
     * 1. 取 `bookSourceUrl`, 去 [getBookSourceJson] 换回完整规则;
     * 2. 取 `enabled` / `enabledExplore` / `bookSourceGroup` —— 这三个在后端是**表列**,
     *    是权威值, 必须用来覆盖 json 里的旧快照 (后台禁用只改列不改 json)。
     */
    suspend fun getBookSourcesNew(
        serverUrl: String,
        accessToken: String,
        md5: String,
        page: Int,
    ): QReadResponse<List<QReadSourceBrief>> = request {
        client.newCallStrResponse {
            get(
                "${apiBase(serverUrl)}/getBookSourcesNew",
                mapOf(
                    "accessToken" to accessToken,
                    "md5" to md5,
                    "page" to page.toString(),
                )
            )
        }.body
    }

    /**
     * 批量导出**完整**书源 JSON。
     *
     * 这是拿书源的**唯一正确接口**: 后端 `SourceController.getbookSourcejson`
     * 把每个 `bookSource.json` 拼成一个 JSON 数组**字符串**返回。
     *
     * @param urls 书源 URL 列表 (后端 `@Body ids` 的形参名叫 ids, 但底层 SQL 是
     *   `WHERE book_source_url = #{bookSourceUrl}`, 所以传的是 **bookSourceUrl**)。
     *   单次不宜过多 —— 后端不做分片, 数组多大就拼多大, 调用方自行分批。
     * @return `data` 是**字符串**, 内容形如 `[ {完整书源}, {完整书源} ]`。
     *   交给 `GSON.parseToJsonElement(...)` 拆成数组后**逐条**
     *   `GSON.fromJsonObject<BookSource>(...)` 解析即可 —— 后端存的就是 legado
     *   格式的 `bookSource.json`, 字段与 legado 实体一一对应
     *   (含 `ruleSearch`/`ruleToc`/`ruleContent`/`header`/`jsLib` 等), **无需映射**。
     *   不要用 `fromJsonArray<BookSource>` 整批解析: 一条畸形会让整批全丢。
     */
    suspend fun getBookSourceJson(
        serverUrl: String,
        accessToken: String,
        urls: List<String>,
    ): QReadResponse<String> = request {
        client.newCallStrResponse {
            // 先用 get() 把 URL + query 建好 (它会正确编码参数), 再用 postJson() 覆盖
            // 请求方法与 body —— OkHttp 的 Request.Builder 后调者生效, 最终是 POST + JSON body。
            get(
                "${apiBase(serverUrl)}/getbookSourcejson",
                mapOf("accessToken" to accessToken)
            )
            // 后端 @Body 收 JSON 数组, 必须是 application/json
            postJson(GSON.toJson(urls))
        }.body
    }

    // ---------------------------------------------------------------------
    // 书架 / 分组
    // ---------------------------------------------------------------------

    /**
     * 书架分页准备: 返回总页数与快照标识。
     *
     * 必须**先**调本接口再调 [getBookshelf], 理由见类注释。
     */
    suspend fun getBookshelfPage(
        serverUrl: String,
        accessToken: String,
    ): QReadResponse<QReadPageInfo> = request {
        client.newCallStrResponse {
            get("${apiBase(serverUrl)}/getBookshelfPage", mapOf("accessToken" to accessToken))
        }.body
    }

    /** 取书架某一页 (页码从 1 开始)。须先用 [getBookshelfPage] 拿到 md5。 */
    suspend fun getBookshelf(
        serverUrl: String,
        accessToken: String,
        md5: String,
        page: Int,
    ): QReadResponse<List<QReadBook>> = request {
        client.newCallStrResponse {
            get(
                "${apiBase(serverUrl)}/getBookshelfNew",
                mapOf(
                    "accessToken" to accessToken,
                    "md5" to md5,
                    "page" to page.toString(),
                )
            )
        }.body
    }

    /** 取分组列表 (同样依赖 [getBookshelfPage] 写入的缓存)。 */
    suspend fun getBookGroups(
        serverUrl: String,
        accessToken: String,
        md5: String,
    ): QReadResponse<List<QReadGroup>> = request {
        client.newCallStrResponse {
            get(
                "${apiBase(serverUrl)}/getgroupNew",
                mapOf("accessToken" to accessToken, "md5" to md5)
            )
        }.body
    }

    // ---------------------------------------------------------------------
    // 阅读进度
    // ---------------------------------------------------------------------

    /**
     * 上传阅读进度。
     *
     * 后端按 `url` 在**服务端书架**里找同书; 找不到会静默忽略 (仍返回 success),
     * 所以只存在于 legado 本地、后端没有的书, 进度不会同步。
     *
     * @param index 章节序号
     * @param pos   **章节内比例 (0.0 ~ 1.0)**, 不是字符偏移 —— 见
     *   [QReadBook.durChapterPos]。后端只存不解释, 但传超过 2 的值会被
     *   `getBookshelfPage` 钳成 0 (等于丢进度), 所以调用方必须换算好再传。
     * @param isnew `"1"` 表示新书 (后端额外记一条 sgread), 否则传 null
     *
     * 返回的 `data` 实测是**字符串** (逗号分隔的已读章节), 但这里统一用
     * `JsonElement` 接住 —— 与 [requestJson] 的其它调用点保持一致, 调用方只看
     * `isSuccess` / `errorMsg`, 不解析 `data`。
     */
    suspend fun saveBookProgress(
        serverUrl: String,
        accessToken: String,
        bookUrl: String,
        index: Int,
        pos: Double,
        title: String?,
        isnew: String? = null,
    ): QReadResponse<JsonElement> {
        val query = mutableMapOf(
            "url" to bookUrl,
            "index" to index.toString(),
            "pos" to pos.toString(),
        )
        title?.takeIf { it.isNotBlank() }?.let { query["title"] = it }
        isnew?.let { query["isnew"] = it }
        return requestJson("/saveBookProgress", serverUrl, accessToken, query)
    }

    /** 拉取某本书的已读章节 (逗号分隔的章节序号)。书不存在时返回 `isSuccess=false`。 */
    suspend fun getBookRead(
        serverUrl: String,
        accessToken: String,
        bookUrl: String,
    ): QReadResponse<JsonElement> =
        requestJson("/getBookread", serverUrl, accessToken, mapOf("url" to bookUrl))

    // ---------------------------------------------------------------------
    // 键值存储 (搜索记录 / 阅读记录 / 其它自定义项)
    // ---------------------------------------------------------------------

    /**
     * 读自定义项。
     *
     * 后端 `ItemController` 提供按用户维度的 **name → value 字符串** 存储, 轻阅读自己
     * 就用它放搜索记录、阅读设置等。legado 侧把记录序列化成 JSON 整体塞进一个 key 即可,
     * 不必让后端加表。
     *
     * **key 不存在时 `data` 字段整个缺失** (不是 null), 所以返回类型必须可空。
     */
    suspend fun getItem(
        serverUrl: String,
        accessToken: String,
        name: String,
    ): QReadResponse<JsonElement> =
        requestJson("/getitem", serverUrl, accessToken, mapOf("name" to name))

    /** 写自定义项。 */
    suspend fun setItem(
        serverUrl: String,
        accessToken: String,
        name: String,
        value: String,
    ): QReadResponse<JsonElement> =
        requestJson("/setitem", serverUrl, accessToken, mapOf("name" to name, "value" to value))

    // ---------------------------------------------------------------------
    // 书源执行代理 (Phase 2 用, 接口先落地)
    // ---------------------------------------------------------------------

    /**
     * 用后端书源引擎搜索。
     *
     * @param type 0=搜索, 1=发现 (对应后端 `search(..., type)`)
     */
    suspend fun searchBook(
        serverUrl: String,
        accessToken: String,
        bookSourceUrl: String,
        key: String,
        page: Int = 1,
        type: Int = 0,
    ): QReadResponse<JsonElement> = requestJson(
        "/searchBook", serverUrl, accessToken,
        mapOf(
            "bookSourceUrl" to bookSourceUrl,
            "key" to key,
            "page" to page.toString(),
            "type" to type.toString(),
        )
    )

    /** 用后端引擎取目录。 */
    suspend fun getChapterList(
        serverUrl: String,
        accessToken: String,
        bookSourceUrl: String,
        bookUrl: String,
    ): QReadResponse<JsonElement> = requestJson(
        "/getChapterList", serverUrl, accessToken,
        mapOf("bookSourceUrl" to bookSourceUrl, "url" to bookUrl)
    )

    /** 用后端引擎取正文。 */
    suspend fun getBookContent(
        serverUrl: String,
        accessToken: String,
        bookUrl: String,
        index: Int,
    ): QReadResponse<JsonElement> = requestJson(
        "/getBookContent", serverUrl, accessToken,
        mapOf("url" to bookUrl, "index" to index.toString())
    )

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    /** GET + `accessToken` + 动态 query, 结果按 `JsonElement` 接住。 */
    private suspend fun requestJson(
        path: String,
        serverUrl: String,
        accessToken: String,
        query: Map<String, String> = emptyMap(),
    ): QReadResponse<JsonElement> = request {
        client.newCallStrResponse {
            get("${apiBase(serverUrl)}$path", query + ("accessToken" to accessToken))
        }.body
    }

    /**
     * 统一收口: 发请求 → 解包 → 任何异常折成失败响应。
     *
     * 失败响应**只有** `isSuccess=false` + `errorMsg`, 调用方据此判断,
     * 不要依赖 `data` 非空来判成功 (后端 success 但 data 为空是合法情况)。
     */
    private suspend inline fun <reified T> request(block: suspend () -> String?): QReadResponse<T> {
        return try {
            val body = block()
            if (body.isNullOrBlank()) {
                QReadResponse(isSuccess = false, errorMsg = "空响应")
            } else {
                json.decodeFromString<QReadResponse<T>>(body)
            }
        } catch (e: Exception) {
            QReadResponse(isSuccess = false, errorMsg = e.message ?: "解析失败")
        }
    }
}
