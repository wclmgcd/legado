package io.legado.app.ui.route

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Server
import io.legado.app.help.qread.QReadSession
import io.legado.app.help.qread.QReadSync
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.import.remote.ServerConfigDialog
import io.legado.app.ui.book.import.remote.ServersViewModelShared
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 轻阅读后端 (autobcb/read) 配置与同步页。
 *
 * # 为什么单独开一页
 * 后端不是 WebDav 数据源, 而是「书源 + 书架 + 记录」的同步中心。原来只能从
 * 「书架 → 添加远程书籍 → 远程服务 → 服务器配置」绕进去, 那个入口是给 WebDav 文件浏览用的,
 * 藏得深且语义不符, 用户根本找不到。
 *
 * # 数据来源
 * 复用已有的 `servers` 表 (只筛 [Server.TYPE.QREAD]), 不新增偏好键、不新建表 ——
 * 与 [RemoteBookRoute] 里那份配置是同一批数据, 两边改都同步。
 *
 * # 操作
 * - 「新建后端」/「编辑」: 复用 [ServerConfigDialog] (它已支持 WEBDAV / QREAD 两种类型);
 *   新建时预置 `type = QREAD`, 省得再点一次类型切换。
 * - 「立即同步」: `QReadSession.login` → `QReadSync.syncAll`, 拉取书源 / 分组 / 书架 / 搜索记录。
 *
 * # token 说明
 * 后端对每用户有 20 个设备上限, 超了会作废该用户全部 token, 且管理员可后台重置。
 * 所以 token 只存内存 (见 [QReadSession]), 每次同步都重新登录 —— 这是刻意的, 不是漏了持久化。
 */
@Composable
fun QReadBackendRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val appDb = remember { AppDbProviders.get() }

    // 订阅 servers 表后本地筛类型; 数据量极小 (个位数), 不值得给 DAO 加查询
    val allServers by appDb.serverDao.observeAll().collectAsState(initial = emptyList())
    val servers = allServers.filter { it.type == Server.TYPE.QREAD }

    val serversVm = remember(scope) { ServersViewModelShared(scope) }

    var showConfigDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<Server?>(null) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    // 当前生效的后端: 显式选中的, 否则列表第一个
    val current = servers.firstOrNull { it.id == selectedId } ?: servers.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(title = "轻阅读后端", onBack = { navigator.pop() })

        Text(
            text = "轻阅读后端提供书源、书架与记录的同步。" +
                "地址形如 http://192.168.1.10:8080（不要带 /api 后缀），" +
                "账号密码与轻阅读 App 一致。",
            color = colors.secondaryText,
            modifier = Modifier.padding(DesignTokens.spacingDefault),
        )

        LazyColumn(Modifier.weight(1f)) {
            if (servers.isEmpty()) {
                item {
                    Text(
                        text = "还没有配置后端，点下面的「新建后端」添加。",
                        color = colors.secondaryText,
                        modifier = Modifier.padding(DesignTokens.spacingDefault),
                    )
                }
            } else {
                items(items = servers, key = { it.id }) { item ->
                    QReadServerRow(
                        item = item,
                        current = item.id == current?.id,
                        onSelect = { selectedId = item.id },
                        onEdit = {
                            editingServer = item
                            showConfigDialog = true
                        },
                        onDelete = { serversVm.delete(item) },
                    )
                }
            }
        }

        // 状态行 (登录/同步结果)
        if (status.isNotEmpty()) {
            Text(
                text = status,
                color = colors.secondaryText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DesignTokens.spacingDefault),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(DesignTokens.spacingDefault),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTextButton(
                text = "新建后端",
                onClick = {
                    editingServer = null
                    showConfigDialog = true
                },
            )
            Spacer(Modifier.weight(1f))
            AppTextButton(
                text = if (syncing) "同步中…" else "立即同步",
                enabled = !syncing && current != null,
                onClick = {
                    val target = current ?: return@AppTextButton
                    syncing = true
                    status = "正在连接 ${target.name}…"
                    scope.launch {
                        // 整段 (登录 + 同步 + 回写 UI) 包在 NonCancellable 里:
                        // scope 来自 rememberCoroutineScope(), 用户一切走页面就被取消。
                        // 同步是多阶段写库, 取消在中间会留下「书源有了、书架一本没有」的半成品,
                        // 而且作用域已死连 toast 都弹不出来, 用户完全看不到发生了什么。
                        withContext(NonCancellable) {
                            val login = QReadSession.login(target)
                            val msg = if (login.isSuccess) {
                                val r = QReadSync.syncAll()
                                if (r.isSuccess) "同步完成：${r.summary}"
                                else "同步失败：${r.error}"
                            } else {
                                "登录失败：${login.exceptionOrNull()?.message ?: "未知错误"}"
                            }
                            status = msg
                            syncing = false
                            Toasters.get().toast(msg)
                        }
                    }
                },
            )
        }
    }

    if (showConfigDialog) {
        // 新建时预置 QREAD 类型, 用户只需填地址/账号/密码
        val target = editingServer ?: Server(type = Server.TYPE.QREAD)
        ServerConfigDialog(
            server = target,
            // 直接走 DAO 而不用 ServerConfigViewModelShared.save: 后者内部会先 delete
            // 上一次保存的 mServer (它的 init/mServer 机制是为「单次弹窗」场景设计的),
            // 在本页「连续新建多个后端」时会误删前一个。
            // ServerDao.insert 是 @Insert(onConflict = REPLACE), 新增/编辑同一条路径,
            // 编辑时 getServer() 用 server?.copy() 保住了原 id, 不会插出重复行。
            onSave = { server ->
                scope.launch {
                    appDb.serverDao.insert(server)
                    selectedId = server.id
                    showConfigDialog = false
                }
            },
            onDismiss = { showConfigDialog = false },
        )
    }
}

/**
 * 单条后端列表项: 名称 + 地址, 点击设为「当前后端」。
 *
 * `●` 标记当前项 —— 不用单选钮是为了跟 [ServerConfigDialog] 的服务器列表区分开:
 * 那边是「选一个 WebDav 数据源」, 这边是「选一个同步目标」。
 */
@Composable
private fun QReadServerRow(
    item: Server,
    current: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.viewHeightXl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(onClick = onSelect)
                .padding(horizontal = DesignTokens.spacingDefault),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = (if (current) "● " else "") + item.name,
                color = if (current) colors.accent else colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.getQReadConfig()?.url.orEmpty(),
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AppTextButton(text = "编辑", onClick = onEdit)
        AppTextButton(text = "删除", onClick = onDelete, color = colors.secondaryText)
    }
}
