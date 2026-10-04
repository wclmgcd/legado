package io.legado.app.help.qread

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 轻阅读后端 (autobcb/read) 接口数据模型。
 *
 * # 背景
 * 轻阅读把书源规则引擎放在**服务端** (Java + Rhino), 客户端只是展示层; 而 legado 把引擎
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
 * # 字段兼容性 (重要)
 * 轻阅读的 [QReadBookSource] / [QReadBook] 与 legado 的
 * `io.legado.app.data.entities.BookSource` / `Book` **同源** (轻阅读 fork 了 legado 的
 * 规则引擎), 绝大多数字段名一一对应。但有 3 处必须做转换, 见 [QReadMapper]:
 * 1. 书源 `ruleXxx` 后端是**对象**, legado 存的是 **JSON 字符串**;
 * 2. 书架 `durChapterPos` 后端是 `Double`, legado 是 `Int`;
 * 3. 书架 `bookgroup` 后端是分组名字符串, legado 的 `Book.group` 是本地 `BookGroup.id`。
 */

/** 后端统一响应包装。`data` 为空时保持 null。 */
@Serializable
data class QReadResponse<T>(
    val isSuccess: Boolean = false,
    val errorMsg: String = "",
    val data: T? = null,
)

/**
 * `/getBookshelfPage` 响应: 书架分页准备信息。
 *
 * 后端流程是两步: 先调 `getBookshelfPage` 拿到总页数与 `md5` (本次书架快照标识),
 * 再按页调 `getBookshelfNew(accessToken, md5, page)` 取每页数据。
 * 缓存 key 里带 md5, 书架变更后 md5 变化即自然失效。
 */
@Serializable
data class QReadShelfPage(
    val page: Int = 1,
    val md5: String = "",
)

/**
 * 轻阅读书源。
 *
 * 与 legado `BookSource` 字段同名同义; 差异集中在 `ruleXxx`:
 * 后端返回的是**结构化对象** (ExploreRule/SearchRule/...), 而 legado 实体存的是
 * **该对象的 JSON 字符串**。用 [JsonObject] 接住原文, 由 [QReadMapper.toLegadoBookSource]
 * 转成字符串即可, 无需在本文件里为 5 种规则各建一套模型 (后端加字段也不会漏)。
 */
@Serializable
data class QReadBookSource(
    val bookSourceUrl: String = "",
    val bookSourceName: String = "",
    val bookSourceGroup: String? = null,
    val bookSourceType: Int = 0,
    val bookUrlPattern: String? = null,
    val customOrder: Int = 0,
    val enabled: Boolean = true,
    val enabledExplore: Boolean = true,
    val loginCheckJs: String? = null,
    val coverDecodeJs: String? = null,
    val bookSourceComment: String? = null,
    val variableComment: String? = null,
    val lastUpdateTime: Long = 0L,
    val respondTime: Long = 180000L,
    val weight: Int = 0,
    val exploreUrl: String? = null,
    val searchUrl: String? = null,
    val ruleExplore: JsonObject? = null,
    val ruleSearch: JsonObject? = null,
    val ruleBookInfo: JsonObject? = null,
    val ruleToc: JsonObject? = null,
    val ruleContent: JsonObject? = null,
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
    /** @see io.legado.app.constant.BookType */
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
    /** 后端是 Double (百分比/比例), legado `Book.durChapterPos` 是 Int (字符偏移)。 */
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
    val createtime: String? = null,
)
