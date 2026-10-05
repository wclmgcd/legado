package io.legado.app.constant

/**
 * 主界面底栏 tab 的字符串标识。
 *
 * # 为什么没有 HOME
 * 本 fork 早期 (bac7a7abb) 把原版的「订阅」换成了自定义「主页」聚合页。
 * 2026-10 按用户要求改回原版形态: **书架 / 发现 / 订阅 / 我的**, 主页入口整体移除。
 *
 * 旧偏好里可能残留 `bottomNavItemOrder = "home,bookshelf,discovery,my"`,
 * 与新的默认集合不一致时会被 computeVisibleTags 判为非法并回落默认顺序, 无需额外迁移。
 */
object BottomNavTag {
    const val BOOKSHELF = "bookshelf"
    const val DISCOVERY = "discovery"
    const val RSS = "rss"
    const val MY = "my"
}
