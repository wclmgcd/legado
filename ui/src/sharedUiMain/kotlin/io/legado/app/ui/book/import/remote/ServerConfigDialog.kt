package io.legado.app.ui.book.import.remote

// I18N KEYS (已注册于 ResourceProvider.jvm.kt / ios Localizable.strings):
//   "action_save" to "保存",
//   "name"        to "名称"
// Painter key (drawable):
//   - ic_save (保存按钮)

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.legado.app.data.entities.Server
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.KS_JSON
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.action_save
import legado.ui.generated.resources.ic_save
import legado.ui.generated.resources.name
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 服务器配置编辑对话框 (KMP 共享, app/desktop/iOS 复用)。
 *
 * 对照 app 端 [io.legado.app.ui.book.import.remote.ServerConfigDialog] (BaseComposeDialogFragment):
 * - 原版依赖 Fragment + viewModels + Bundle + savedInstanceState + GSON.toJson(HashMap),
 *   含 Android 专属 API (Fragment / Bundle / Context)。
 * - 桌面/iOS 端无 Fragment 体系, 改为纯 @Composable + [Dialog], 由调用方注入数据与回调。
 *
 * # API 设计 (与 [DictRuleEditDialog] 同模式)
 *
 * 数据与持久化全部由调用方注入, 本组件仅负责 UI 与交互:
 * - [server]: 待编辑的服务器 (null=新增), 调用方按 id 异步加载后传入
 * - [onSave]: 保存回调, 参数为组装后的 [Server] (调用方调 `ServerConfigViewModelShared.save`)
 * - [onDismiss]: 关闭对话框回调 (返回按钮 / 保存成功 / 点击外部)
 *
 * # 字段对齐 (对照 app 端原版)
 *
 * - name: 服务器名称 (对应 app `name`)
 * - url / username / password: WebDav 配置三字段 (对应 app `getWebDavConfig()` 返回的 HashMap);
 *   轻阅读后端 ([Server.TYPE.QREAD]) 复用同样三字段 (地址 / 账号 / 密码)
 * - TYPE 行: 点击在 WEBDAV / QREAD 间切换 (原版 TYPE spinner 仅 WEBDAV 单项, 现扩展一项)
 *
 * # 序列化
 *
 * 原版用 `GSON.toJson(HashMap<String, String>)` 序列化 config 字段;
 * 下沉后用 [KS_JSON] + [Server.WebDavConfig] serializer 序列化,
 * 输出 JSON `{"url":"...","username":"...","password":"..."}` 与原版完全兼容
 * ([Server.getWebDavConfig] 用 `decodeOrNull<WebDavConfig>(config)` 反序列化, 双向兼容)。
 * 轻阅读后端用 [Server.QReadConfig] serializer, 输出同形 JSON, 由
 * [Server.getQReadConfig] 反序列化。
 *
 * @param server 待编辑的服务器 (null=新增), 调用方按 id 异步加载后传入
 * @param onSave 保存回调, 参数为组装后的 Server (调用方调 `ServerConfigViewModelShared.save` 并关闭对话框)
 * @param onDismiss 关闭对话框回调 (返回按钮 / 点击外部)
 */
@Composable
fun ServerConfigDialog(
    server: Server?,
    onSave: (Server) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    // 表单字段 (keyed by server, 切换编辑目标时重置, 与 DictRuleEditDialog 同模式)
    var name by remember(server) { mutableStateOf(server?.name.orEmpty()) }
    // 服务器类型: WEBDAV (坚果云等) / QREAD (轻阅读后端, 见 io.legado.app.help.qread)。
    // 两种 config 的 JSON 结构恰好同形 (url/username/password), 故共用下面三个输入框,
    // 只在 getServer() 里按类型选不同的 serializer。
    var serverType by remember(server) { mutableStateOf(server?.type ?: Server.TYPE.WEBDAV) }
    val initWebDav = remember(server) { server?.getWebDavConfig() }
    val initQRead = remember(server) { server?.getQReadConfig() }
    var url by remember(server) { mutableStateOf(initQRead?.url ?: initWebDav?.url.orEmpty()) }
    var username by remember(server) { mutableStateOf(initQRead?.username ?: initWebDav?.username.orEmpty()) }
    var password by remember(server) { mutableStateOf(initQRead?.password ?: initWebDav?.password.orEmpty()) }

    /**
     * 组装当前输入框值为 Server, 与 app 端 getServer() 等价:
     * - 编辑已有服务器时 server?.copy() (保留原 id 等字段, 不污染传入实例);
     * - 新增时 new Server()。
     * - type 取当前选中的 [serverType]; config 按类型分别用 [Server.WebDavConfig] /
     *   [Server.QReadConfig] 的 serializer 序列化, 后者由 [Server.getQReadConfig] 反序列化。
     */
    fun getServer(): Server {
        val newServer = server?.copy() ?: Server()
        newServer.name = name
        newServer.type = serverType
        newServer.config = if (serverType == Server.TYPE.QREAD) {
            KS_JSON.encodeToString(
                Server.QReadConfig.serializer(),
                Server.QReadConfig(url = url, username = username, password = password),
            )
        } else {
            KS_JSON.encodeToString(
                Server.WebDavConfig.serializer(),
                Server.WebDavConfig(url = url, username = username, password = password),
            )
        }
        return newServer
    }

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            modifier = Modifier.appDialogSize(),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = "",
                    onBack = onDismiss,
                    actions = {
                        IconButton(onClick = { onSave(getServer()) }) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_save),
                                contentDescription = stringResource(Res.string.action_save),
                                tint = colors.primaryText,
                            )
                        }
                    },
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = DesignTokens.spacingDefault),
                ) {
                    AppUnderlineTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(Res.string.name),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 点一下在 WEBDAV / QREAD 间切换。
                    // QREAD = 轻阅读后端 (autobcb/read): 它不是 WebDav 数据源, 而是
                    // 「书源 + 书架 + 记录」的同步中心, 故在 ServersDialog 里不参与
                    // 「选为默认远程服务」的单选, 只提供同步入口。
                    Row(
                        Modifier
                            .padding(top = DesignTokens.spacingDefault)
                            .clickable {
                                serverType = if (serverType == Server.TYPE.WEBDAV) {
                                    Server.TYPE.QREAD
                                } else {
                                    Server.TYPE.WEBDAV
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("TYPE", color = colors.accent, modifier = Modifier.padding(DesignTokens.spacingDefault))
                        Text(serverType.name, color = colors.primaryText)
                    }
                    AppUnderlineTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = "url",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppUnderlineTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = "username",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppUnderlineTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "password",
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
