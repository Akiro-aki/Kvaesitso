package de.mm20.launcher2.globalactions

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import de.mm20.launcher2.permissions.PermissionsManager
import de.mm20.launcher2.preferences.ui.GestureSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.lang.ref.WeakReference

class LauncherAccessibilityService: AccessibilityService() {

    private val permissionManager: PermissionsManager by inject()
    private val gestureSettings: GestureSettings by inject()

    private var scope: CoroutineScope? = null
    private var edgeAppSwitcher: EdgeAppSwitcher? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            edgeAppSwitcher?.onWindowStateChanged(event.packageName?.toString())
        }
    }

    override fun onInterrupt() {

    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        permissionManager.reportAccessibilityServiceState(true)

        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also {
            it.launch {
                gestureSettings.edgeAppSwitch.collect { enabled ->
                    setEdgeAppSwitchEnabled(enabled)
                }
            }
        }
    }

    private fun setEdgeAppSwitchEnabled(enabled: Boolean) {
        if (enabled == (edgeAppSwitcher != null)) return
        if (enabled) {
            edgeAppSwitcher = EdgeAppSwitcher(this).also { it.show() }
        } else {
            edgeAppSwitcher?.hide()
            edgeAppSwitcher = null
        }
        // Only listen to window changes while they are needed to track recent apps.
        serviceInfo?.let {
            it.eventTypes = if (enabled) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED else 0
            serviceInfo = it
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        tearDown()
        permissionManager.reportAccessibilityServiceState(false)
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }

    private fun tearDown() {
        scope?.cancel()
        scope = null
        edgeAppSwitcher?.hide()
        edgeAppSwitcher = null
    }

    companion object {
        private var instance: WeakReference<LauncherAccessibilityService>? = null
        internal fun getInstance(): LauncherAccessibilityService? {
            return instance?.get()
        }
    }
}
