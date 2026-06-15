package com.titan.automation.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * PermissionViewModel — tracks real-time status of all permissions required
 * before TITAN can operate.
 *
 * Permissions & why each is needed:
 *   1. Accessibility Service  — gesture dispatch (taps, swipes, multi-touch)
 *   2. SYSTEM_ALERT_WINDOW    — floating overlay control panel
 *   3. MediaProjection        — screen capture on API 29 only;
 *                               API 30+ uses AccessibilityService.takeScreenshot()
 *   4. POST_NOTIFICATIONS     — engine status bar notification (API 33+)
 *
 * Usage:
 *   - Call [refresh] in Activity.onResume() to sync after returning from Settings
 *   - Call [markProjectionGranted] after the MediaProjection result callback fires
 *   - Observe [permissions] in Compose to gate the main UI behind [OnboardingScreen]
 */
@HiltViewModel
class PermissionViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _permissions = MutableStateFlow(PermissionState())
    val permissions: StateFlow<PermissionState> = _permissions.asStateFlow()

    init { refresh() }

    /** Re-check all permissions. Call in Activity.onResume(). */
    fun refresh() {
        val current = _permissions.value
        _permissions.value = PermissionState(
            overlayGranted       = Settings.canDrawOverlays(context),
            accessibilityGranted = isAccessibilityServiceEnabled(),
            notificationsGranted = isNotificationsGranted(),
            // Preserve projection flag if already granted this session; it can't be
            // re-verified from static APIs after the user dismisses the dialog
            projectionGranted    = current.projectionGranted
        )
    }

    /**
     * Called from Activity when MediaProjection result callback fires with RESULT_OK.
     * This is a session-only flag — it resets if the process is killed.
     */
    fun markProjectionGranted() {
        _permissions.value = _permissions.value.copy(projectionGranted = true)
    }

    // ── Checks ────────────────────────────────────────────────────────────────

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    private fun isNotificationsGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
}

// ── State ─────────────────────────────────────────────────────────────────────

/**
 * Snapshot of all TITAN permission states.
 *
 * [allGranted] is the gate used by [RootScreen] to switch between
 * [OnboardingScreen] and the main app UI. It passes as soon as every
 * *required* permission is granted:
 *   - Accessibility (always required)
 *   - Overlay       (always required)
 *   - MediaProjection (required on API 29 only; API 30+ uses Accessibility screenshot)
 *   - Notifications is optional — never blocks the gate
 */
data class PermissionState(
    val overlayGranted       : Boolean = false,
    val accessibilityGranted : Boolean = false,
    val notificationsGranted : Boolean = true,
    val projectionGranted    : Boolean = false
) {
    /** True when every required permission is present. */
    val allGranted: Boolean get() {
        val projectionOk = projectionGranted || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        return overlayGranted && accessibilityGranted && projectionOk
    }

    /** How many *required* permissions are still missing (shown as subtitle in onboarding). */
    val missingCount: Int get() {
        val projectionOk = projectionGranted || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        return listOf(overlayGranted, accessibilityGranted, projectionOk).count { !it }
    }
}
