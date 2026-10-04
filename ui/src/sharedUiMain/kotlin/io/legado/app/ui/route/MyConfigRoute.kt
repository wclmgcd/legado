package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.main.my.MyConfigScreen
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.my
import org.jetbrains.compose.resources.stringResource

/**
 * 我的页设置 shared 路由入口: 渲染 [MyConfigScreen] 并桥接各子条目路由跳转。
 *
 * MyConfigScreen 为纯展示型 (无 ScreenModel/UiActions);
 * 主题模式切换 (applyDayNight) 通过 [PlatformCapabilityProviders] 桥接。
 *
 * 本 fork 移除了 WebDav「备份与恢复」与「Web 服务」两项 (改用轻阅读后端同步),
 * 所以原来那套 webService 开关态 / 长按菜单 (复制地址、浏览器打开) 的桥接代码一并去掉了。
 */
@Composable
fun MyConfigRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    // push 打开时自带顶栏 + 返回按钮 (对照其他 push 型路由如 ThemeConfigRoute;
    // tab 态不经本路由, 由 MainRoute 的 MyTabTitleBar 提供无返回顶栏)
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = stringResource(Res.string.my),
            onBack = { navigator.pop() },
        )
        MyConfigScreen(
            onThemeModeChange = {
                PlatformCapabilityProviders.get().applyDayNight()
            },
            onThemeSetting = { navigator.push(AppRoute.ThemeConfig) },
            onQReadBackend = { navigator.push(AppRoute.QReadBackend) },
            onOtherSetting = { navigator.push(AppRoute.OtherConfig) },
            onBookSourceManage = { navigator.push(AppRoute.BookSourceManage) },
            onReplaceManage = { navigator.push(AppRoute.ReplaceRule) },
            onSourceFilterRuleManage = { navigator.push(AppRoute.SourceFilterRule) },
            onTxtTocRuleManage = { navigator.push(AppRoute.TxtTocRule) },
            onDictRuleManage = { navigator.push(AppRoute.DictRule) },
            onRuleSubManage = { navigator.push(AppRoute.RuleSub) },
            onBookmark = { navigator.push(AppRoute.Bookmark()) },
            onReadRecord = { navigator.push(AppRoute.ReadRecord) },
            onSourceToolbox = { navigator.push(AppRoute.SourceToolbox) },
            onAbout = { navigator.push(AppRoute.About) },
        )
    }
}
