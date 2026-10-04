package io.legado.app.ui.config

import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 其它设置页的动态摘要 (本 fork 去掉 Web 服务端口后剩六项)。 */
data class OtherConfigUiState(
    val userAgentSummary: String = "",
    val bookTreeUriSummary: String = "",
    val checkSourceSummary: String = "",
    val bitmapCacheSummary: String = "",
    val preDownloadSummary: String = "",
    val threadCountSummary: String = "",
)

/**
 * 其它设置页 shared ScreenModel，仅托管需要跨重组保留的摘要状态。
 * 点击动作由 Route 直接执行，避免 Screen → event → ScreenModel → Route 的纯转发链。
 */
class OtherConfigScreenModel : ScreenModel {

    private val _state = MutableStateFlow(OtherConfigUiState())
    val state: StateFlow<OtherConfigUiState> = _state.asStateFlow()

    fun updateUserAgentSummary(summary: String) {
        _state.update { it.copy(userAgentSummary = summary) }
    }

    fun updateBookTreeUriSummary(summary: String) {
        _state.update { it.copy(bookTreeUriSummary = summary) }
    }

    fun updateCheckSourceSummary(summary: String) {
        _state.update { it.copy(checkSourceSummary = summary) }
    }

    fun updateBitmapCacheSummary(summary: String) {
        _state.update { it.copy(bitmapCacheSummary = summary) }
    }

    fun updatePreDownloadSummary(summary: String) {
        _state.update { it.copy(preDownloadSummary = summary) }
    }

    fun updateThreadCountSummary(summary: String) {
        _state.update { it.copy(threadCountSummary = summary) }
    }
}
