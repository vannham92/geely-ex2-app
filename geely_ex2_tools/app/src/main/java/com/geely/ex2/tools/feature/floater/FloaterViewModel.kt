package com.geely.ex2.tools.feature.floater

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.geely.ex2.tools.data.floater.FloaterAppStarter
import com.geely.ex2.tools.data.floater.FloaterSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FloaterUiState(
    val isEnabled: Boolean = false,
    val hasOverlayPermission: Boolean = false,
)

class FloaterViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(FloaterUiState())
    val uiState: StateFlow<FloaterUiState> = _uiState.asStateFlow()

    fun onResume() {
        refreshState()
    }

    /** UI phải kiểm tra [FloaterUiState.hasOverlayPermission] trước khi gọi hàm này với `true`;
     * nếu chưa có quyền, điều hướng người dùng sang màn cấp quyền overlay hệ thống trước. */
    fun onEnabledChanged(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled && !FloaterAppStarter.hasOverlayPermission(app)) {
            refreshState()
            return
        }
        FloaterSettings.setEnabled(app, enabled)
        if (enabled) {
            FloaterAppStarter.startServiceIfEnabled(app, "UI floater toggle")
        } else {
            FloaterAppStarter.stopService(app, "UI floater toggle")
        }
        refreshState()
    }

    fun refreshState() {
        val app = getApplication<Application>()
        val hasPermission = FloaterAppStarter.hasOverlayPermission(app)
        _uiState.update {
            it.copy(
                isEnabled = FloaterSettings.isEnabled(app) && hasPermission,
                hasOverlayPermission = hasPermission,
            )
        }
    }
}
