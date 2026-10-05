package io.legado.app.help.qread

import kotlinx.serialization.Serializable

/**
 * 轻阅读后端 (autobcb/read) 接口数据模型。
 *
 * # 背景
 * 轻阅读把书源规则引擎放在**服务端** (Kotlin + Rhino), 客户端只是展示层; 而 legado 把引擎
 * 放在**客户端本地**。本包提供一套最小客户端, 让 legado 能把轻阅读后端当作
 * 「数据源 + 同步中心」使用。
 *
 * # 响应包装
 * 后端所有 `/api/{v}/xxx` 统一返回 [QReadResponse] 结构
 * (见后端 `web/response/JsonResponse.kt`):
 * ```
 * { "isSuccess": true, "errorMsg": "success", "data": ... }
 * ```
 * `data` 的具体类型随接口而变 (对象 / 数组 / 字符串 / null), 故无把握时用
 * `QReadResponse<JsonElement>` 兜底。
 *
 * **注意**: `data` 缺失时后端会**整个字段不输出** (Snack 不序列化 null),
 * 例如 `{"isSuccess":true,"errorMsg":"success"}`。所以 `data` 必须声明为可空 + 有默认值。
 *
 * # 字段兼容性 (重要)
 * 轻阅读的 [QReadBook] 与 legado 的 `io.legado.app.data.entities.Book` **同源**
 * (轻阅读 fork 了 legado 的规则引擎), 字段名一一对应, 但有 2 处**类型/语义不同**,
 * 必须显式转换, 见 [QReadMapper]:
 * 1. 书架 `durChapterPos` 后端是 **章节内比例 (Double, 0.0~1.0)**, legado 是**正文字符偏移 (Int)**;
 * 2. 书架 `type` 后端是**单值索引** (0 文本 / 1 音频 / 2 图片), legado 是 **`BookType` 位掩码**
 *    (text=8 / audio=32 / image=64)。
 *
 * 书源则**无需任何转换** —— 见 [QReadSourceBrief] 的说明。
 */

/** 后端统一响应包装。`data` 为空 / 缺失时保持 null。 */
@Serializable
data class QReadResponse<T>(
    val isSuccess: Boolean = false,
    val errorMsg: String = "",
    val data: T? = null,
)

/**
 * `/login` 响应体。
 *
 * 实测 (后端 3.4.4) 返回:
 * ```
 * {"isSuccess":true,"errorMsg":"success","data":{"accessToken":"8a90dc55-..."}}
 * ```
 * 即 `data` 是**对象**而不是裸字符串 —— 对应后端
 * `UserController.login` 的 `JsonResponse(true,"success").Data(mapOf("accessToken" to tocken.id))`。
 */
@Serializable
data class QReadLoginResult(
    val accessToken: String = "",
)

/**
 * 分页准备响应 (`/getBookshelfPage`、`/getBookSourcesPage` 等共用同一形状)。
 *
 * 后端流程是两步: 先调 `getXxxPage` 拿到总页数与 `md5` (本次快照标识, 后端同时把每页数据
 * 写进服务端缓存), 再按页调 `getXxxNew(accessToken, md5, page)`。
 * 缓存 key 里带 md5, 数据变更后 md5 变化即自然失效。
 *
 * **跳过第一步直接调 `getXxxNew` 会拿到空结果** —— 这是个容易踩的坑。
 */
@Serializable
data class QReadPageInfo(
    val page: Int = 1,
    val md5: String = "",
)

/**
 * `/getBookSourcesNew` 列表项 —— **精简投影**, 只有 9 个字段。
 *
 * # 为什么不能用它当书源用
 * 后端 `SourceController.getBookSourcesPage` 写进缓存的每条记录只有:
 * ```
 * checkKeyWord / variableComment / bookSourceGroup / loginUrl / loginUi
 * bookSourceName / bookSourceUrl / enabledExplore / enabled
 * ```
 * **没有 `ruleSearch` / `ruleToc` / `ruleContent` / `ruleBookInfo` / `ruleExplore` /
 * `searchUrl` / `exploreUrl` / `header` / `jsLib` 等** —— 这是给「书源列表」界面渲染用的。
 *
 * 所以本类型**不能**直接建 `BookSource`, 会得到一堆没有规则的废源。
 *
 * # 但它有三个字段是「权威值」(关键)
 * 本类型有两个用途, 缺一不可:
 * 1. 取 `bookSourceUrl` → 调 [QReadApi.getBookSourceJson] 批量换回完整规则;
 * 2. 取 [enabled] / [enabledExplore] / [bookSourceGroup] → **覆盖** json 里的同名字段。
 *
 * 因为这三个在后端是**独立表列** (后台点「禁用」、改分组改的就是列), 而 `bookSource.json`
 * 里的是「上传书源那一刻」的旧快照 —— 后端 `getbookSourcejson` 只拼 json 列, 完全不带列。
 * 只走 json 会让**后台禁用的书源同步过来全是启用的**。合流见 [QReadMapper.applyServerState]。
 *
 * # 为什么这里**不**声明 loginUrl / loginUi
 * 它们确实在后端列表里, 但那份 `loginUi` 是后端**执行完 JS 之后**的结果
 * (`getBookSourcesPage` 里 `loginUi = s.getloginUi(false)`), 而 legado 需要的是**原始规则**
 * (legado 有自己的 JS 引擎, 会自己执行)。json 列里存的正是原始值, 所以这两个字段
 * 一律以 json 为准, 不从本投影取。
 */
@Serializable
data class QReadSourceBrief(
    val bookSourceUrl: String = "",
    val bookSourceName: String = "",
    /**
     * 分组**名称**; 后端列 `bookSourceGroup`, `null` 表示未分组。
     *
     * 注意后端的 `Snack` 序列化**会直接省略值为 null 的字段** (实测 50 条里只有 47 条
     * 带这个 key, 少的那 3 条就是未分组) —— 所以「key 缺失」和「值为 null」在这里
     * 是同一件事, 都由本字段的默认值 `null` 接住, 语义正确。
     */
    val bookSourceGroup: String? = null,
    /**
     * 是否启用。后端列 `enabled`, 后台「禁用」改的就是它。
     *
     * 声明成可空**不是为了接受 null**, 而是防御: 后端 `BaseSource.enabled` 是非空
     * `Boolean` (实测 50/50 都有值), 但一旦哪天返回 null / 省略该 key, 非空声明会让
     * kotlinx 抛 `SerializationException` → **整页 brief 解析失败 → 全部书源同步挂掉**。
     * 可空 + 调用侧 `?:` 兜底, 最坏也只是退化成「保留 json 里的值」。
     */
    val enabled: Boolean? = null,
    /** 是否启用发现。后端列 `enabledExplore`, 后端类型是**可空** `Boolean?`, 故必须可空。 */
    val enabledExplore: Boolean? = null,
)

/**
 * 轻阅读书架条目 (对应后端 `web/model/Booklist.kt`)。
 *
 * 注意可空性: 后端字段几乎全部是 Kotlin 可空类型, 反序列化时必须同样可空,
 * 否则某本书缺字段会导致**整页**解析失败。
 */
@Serializable
data class QReadBook(
    /**
     * 详情页 url。
     *
     * 声明为可空而非 `String = ""`: 后端该字段是 `String?`, 若某本书的 json 里
     * 显式出现 `"bookUrl": null`, 非空声明会让**整页**反序列化失败 (kotlinx 对
     * 非空类型遇到 null 直接抛)。可空 + 调用方兜底才安全。
     */
    val bookUrl: String? = null,
    val tocUrl: String? = null,
    /** 书源 URL; 本地书为 `loc_book`。 */
    val origin: String? = null,
    val originName: String? = null,
    val originOrder: Int? = null,
    val name: String? = null,
    val author: String? = null,
    val kind: String? = null,
    val customTag: String? = null,
    val coverUrl: String? = null,
    val customCoverUrl: String? = null,
    val intro: String? = null,
    val customIntro: String? = null,
    val charset: String? = null,
    /**
     * 书籍类型, **单值索引**: `0` 文本 / `1` 音频 / `2` 图片。
     *
     * 后端 `Booklist.type` 的 setter 会把写入值归一化:
     * `32,1 -> 1`(音频)、`64,2 -> 2`(图片)、其余 -> `0`(文本)。
     * **不能直接赋给 legado 的 `Book.type`** —— 那是 `BookType` 位掩码
     * (text=8 / audio=32 / image=64), 见 [QReadMapper.toLegadoBook]。
     */
    val type: Int? = null,
    /** 分组**名称** (不是 id), 与 legado 的 `Book.group: Long` 语义不同。 */
    val bookgroup: String? = null,
    val latestChapterTitle: String? = null,
    val latestChapterTime: Long? = null,
    val lastCheckTime: Long? = null,
    val lastCheckCount: Int? = null,
    val totalChapterNum: Int? = null,
    val durChapterTitle: String? = null,
    val durChapterIndex: Int? = null,
    /**
     * 章节内阅读进度, **比例** (0.0 ~ 1.0), 不是字符偏移。
     *
     * 依据: 后端 `BookshelfController.getBookshelfPage` 把该值钳到 `0.0..2.0`,
     * 实测线上数据全是干净分数 (1/9=0.1111、1/4=0.25、13/16=0.8125 ...),
     * 且后端只存不解释 —— 写什么读什么。官方客户端发的是比例。
     * legado 的 `Book.durChapterPos` 是**正文字符偏移**, 两者换算需要正文长度,
     * 同步阶段拿不到, 故 [QReadMapper.toLegadoBook] 只能置 0。
     */
    val durChapterPos: Double? = null,
    val durChapterTime: Long? = null,
    val wordCount: String? = null,
    /** 已读章节索引, 逗号分隔 (如 `"0,1,2"`); legado 用 BookChapter.read 存, 需另做映射。 */
    val readchapter: String? = null,
    val useReplaceRule: Boolean? = null,
)

/** 轻阅读分组 (对应后端 `web/model/BookGroup.kt`)。 */
@Serializable
data class QReadGroup(
    val id: String? = null,
    /** 分组名称。 */
    val bookgroup: String? = null,
    val grouporder: Int? = null,
    /** 后端用 Snack 按 `yyyy-MM-dd HH:mm:ss` 格式化, 所以是字符串不是时间戳。 */
    val createtime: String? = null,
)
