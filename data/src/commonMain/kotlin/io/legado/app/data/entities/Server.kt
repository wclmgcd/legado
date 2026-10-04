package io.legado.app.data.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import io.legado.app.utils.decodeOrNull
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.serialization.Serializable

/**
 * 服务器
 *
 * 已从 androidMain 下沉 commonMain; 原 getConfigJsonObject() 依赖 org.json.JSONObject
 * (Android 平台特有), 已抽取到 androidMain/ServerExt.kt 作为扩展函数,
 * commonMain 只保留纯数据模型。
 */
@Serializable
@Entity(tableName = "servers")
data class Server(
    @PrimaryKey
    var id: Long = systemCurrentTimeMillis(),
    var name: String = "",
    var type: TYPE = TYPE.WEBDAV,
    var config: String? = null,
    var sortNumber: Int = 0
) {

    enum class TYPE {
        WEBDAV,

        /**
         * 轻阅读后端 (autobcb/read)。
         *
         * 轻阅读把书源规则引擎放在服务端, 客户端只是展示层; 把后端登记成一种
         * [Server] 后, legado 就能把它当作「数据源 + 同步中心」使用。
         * 配置存 [QReadConfig], 接口实现在 `io.legado.app.help.qread` 包。
         */
        QREAD
    }

    // 不覆写 equals/hashCode: 只比 id 会让 collectAsState 吞掉改名/改地址;
    // 实例禁止作 HashSet 元素 / HashMap key (config 是 var)

    fun getWebDavConfig(): WebDavConfig? {
        // GSON.fromJsonObject<WebDavConfig>(config).getOrNull() → KS_JSON.decodeOrNull<WebDavConfig>(config)
        return if (type == TYPE.WEBDAV) decodeOrNull<WebDavConfig>(config) else null
    }

    /** 轻阅读后端配置; `type` 不匹配时返回 null。 */
    fun getQReadConfig(): QReadConfig? {
        return if (type == TYPE.QREAD) decodeOrNull<QReadConfig>(config) else null
    }

    @Serializable
    data class WebDavConfig(
        var url: String,
        var username: String,
        var password: String
    )

    /**
     * 轻阅读后端连接配置。
     *
     * 只存「地址 + 账号密码」, **不存 accessToken** —— 后端 token 有 20 设备上限
     * (见后端 `UserController.login`), 且可被后台重置, 每次启动重新登录更稳。
     */
    @Serializable
    data class QReadConfig(
        /** 形如 `http://192.168.1.10:8080`, 不要带 `/api/...` 后缀。 */
        var url: String,
        var username: String,
        var password: String
    )

}
