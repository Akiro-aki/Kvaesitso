package de.mm20.launcher2.globalactions

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs

/**
 * Switches between recent apps when the user swipes left or right along the bottom edge of the
 * screen, like the quick switch gesture of the system's gesture navigation.
 *
 * Some systems (Honor's MagicOS, Huawei's EMUI) only support that gesture while their own launcher
 * is the default home app. This rebuilds it from the launcher's accessibility service: a thin
 * overlay covers the gesture area at the bottom of the screen, and the order of recent apps is
 * tracked from window state changes, which the service receives for every app.
 *
 * The overlay only handles horizontal swipes. The system's own gestures (swipe up to go home, back
 * swipes from the sides) are recognized by input monitors that see every touch regardless of the
 * window under it and cancel ours, so they keep working. Taps on the overlay are forwarded to the
 * window underneath, so buttons drawn in the gesture area stay usable.
 */
internal class EdgeAppSwitcher(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val packageManager = service.packageManager
    private val handler = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    /**
     * Packages of recently used apps, most recent first.
     */
    private val recentPackages = ArrayDeque<String>()

    /**
     * Keeps [recentPackages] across restarts of the service, which happen whenever the launcher is
     * updated or its process is killed. Without it, only apps opened since then can be switched to.
     */
    private val prefs = service.getSharedPreferences("edge_app_switcher", Context.MODE_PRIVATE)

    init {
        prefs.getString(PrefRecents, null)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.let { recentPackages.addAll(it.take(MaxRecentApps)) }
    }

    private fun saveRecents() {
        prefs.edit().putString(PrefRecents, recentPackages.joinToString(",")).apply()
    }

    /**
     * The app in the foreground, or null if it is the home screen or recents.
     */
    private var foregroundPackage: String? = null

    private val launchableCache = mutableMapOf<String, Boolean>()

    private val homePackages: Set<String> by lazy {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .toSet() + service.packageName
    }

    private val imePackages: Set<String>
        get() = service.getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.map { it.packageName }
            ?.toSet()
            ?: emptySet()

    /**
     * While the user is switching through apps, the order of recent apps as it was when they
     * started, so that each switch does not reorder the list under them.
     */
    private var session: List<String?>? = null
    private var sessionIndex = 0
    private var lastSwitchTime = 0L

    private var overlay: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f

    fun onWindowStateChanged(packageName: String?) {
        packageName ?: return
        Log.d("EdgeAppSwitcher", "Window state changed: $packageName (foreground was $foregroundPackage)")
        if (packageName != service.packageName && packageName in homePackages) {
            // The system's own launcher, which still runs the system's recents screen. On Honor
            // and Huawei it also briefly opens it for each sideways swipe along the bottom edge,
            // then returns to the app (that is the flicker). Neither means that the user went
            // home, so the foreground app stays the same.
            Log.d("EdgeAppSwitcher", "System recents shown ($packageName)")
            systemRecentsVisible = true
            return
        }
        val recentsWasVisible = systemRecentsVisible
        systemRecentsVisible = false
        if (recentsWasVisible && pendingSwitch != null) {
            // The system's recents transition is over, so a switch no longer gets swallowed by it.
            handler.removeCallbacks(runPendingSwitch)
            handler.postDelayed(runPendingSwitch, AfterSystemRecentsDelayMs)
        }
        if (packageName in homePackages) {
            foregroundPackage = null
            return
        }
        // Skips the system UI, keyboards, and dialogs of packages without a launcher activity,
        // which the user would not expect to switch to.
        if (!isLaunchable(packageName)) return
        foregroundPackage = packageName
        recentPackages.remove(packageName)
        recentPackages.addFirst(packageName)
        while (recentPackages.size > MaxRecentApps) recentPackages.removeLast()
        saveRecents()
    }

    private fun isLaunchable(packageName: String): Boolean {
        return launchableCache.getOrPut(packageName) {
            packageName !in imePackages && packageManager.getLaunchIntentForPackage(packageName) != null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (overlay != null) return
        val view = View(service)
        view.setOnTouchListener { _, event ->
            onTouch(event)
            true
        }

        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            gestureAreaHeight(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.FILL_HORIZONTAL
            title = "EdgeAppSwitcher"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Cover the navigation bar instead of being placed above it.
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            windowManager.addView(view, lp)
            overlay = view
            layoutParams = lp
        } catch (e: Exception) {
            Log.e("EdgeAppSwitcher", "Could not add overlay", e)
        }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        pendingSwitch = null
        overlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("EdgeAppSwitcher", "Could not remove overlay", e)
            }
        }
        overlay = null
        layoutParams = null
    }

    /**
     * Height of the system's gesture area at the bottom of the screen.
     */
    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun gestureAreaHeight(): Int {
        val res = service.resources
        val height = listOf("navigation_bar_gesture_height", "navigation_bar_height")
            .firstNotNullOfOrNull { name ->
                val id = res.getIdentifier(name, "dimen", "android")
                if (id != 0) res.getDimensionPixelSize(id).takeIf { it > 0 } else null
            } ?: (DefaultGestureAreaHeightDp * density).toInt()
        return height.coerceIn(
            (MinGestureAreaHeightDp * density).toInt(),
            (MaxGestureAreaHeightDp * density).toInt(),
        )
    }

    private fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastX = downX
                lastY = downY
                downTime = event.eventTime
            }

            MotionEvent.ACTION_MOVE -> {
                lastX = event.rawX
                lastY = event.rawY
            }

            MotionEvent.ACTION_UP -> {
                onGestureEnd(event.rawX, event.rawY, event.eventTime, cancelled = false)
            }

            MotionEvent.ACTION_CANCEL -> {
                // The system took over the touch, which it does when it recognizes one of its own
                // gestures. If it was clearly a sideways swipe anyway, the system does not handle
                // that one, so it is still ours.
                onGestureEnd(lastX, lastY, event.eventTime, cancelled = true)
            }
        }
    }

    private fun onGestureEnd(x: Float, y: Float, time: Long, cancelled: Boolean) {
        val dx = x - downX
        val dy = y - downY
        val minSwipeDistance = MinSwipeDistanceDp * density
        val isHorizontal = abs(dx) > abs(dy) * HorizontalSwipeRatio
        Log.d(
            "EdgeAppSwitcher",
            "Gesture end: dx=$dx dy=$dy cancelled=$cancelled foreground=$foregroundPackage " +
                    "recents=${recentPackages.take(5)}"
        )

        when {
            abs(dx) >= minSwipeDistance && isHorizontal -> {
                scheduleSwitch(towardsOlder = dx > 0)
            }

            // If the system takes over the touch, it does so as soon as it has moved past its touch
            // slop, long before it covers the minimum swipe distance, so a cancelled touch has only
            // moved a few pixels. On the systems this is for, the system then fails to switch apps
            // itself, so a clearly sideways start is enough.
            cancelled && abs(dx) >= touchSlop && isHorizontal -> {
                scheduleSwitch(towardsOlder = dx > 0)
            }

            cancelled -> {}

            // Normally the system recognizes this as the home gesture and cancels our touch. In
            // case it did not, go home, so that the overlay never blocks the way home.
            -dy >= minSwipeDistance && -dy > abs(dx) -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }

            abs(dx) <= touchSlop && abs(dy) <= touchSlop -> {
                forwardTap(x, y, time - downTime)
            }
        }
    }

    /**
     * Direction of a switch that waits for the system's recents transition to end, or null.
     */
    private var pendingSwitch: Boolean? = null

    /**
     * Whether the system's recents screen (of the system's own launcher) is in the foreground.
     */
    private var systemRecentsVisible = false

    private val runPendingSwitch = Runnable {
        val towardsOlder = pendingSwitch ?: return@Runnable
        pendingSwitch = null
        switchApp(towardsOlder)
    }

    /**
     * Switches apps once the system is done with the swipe. On Honor and Huawei, the system
     * reacts to the same swipe by opening its recents screen for a moment and going back to the
     * app. An app started during that transition is swallowed by it, and the recents screen stays
     * open. So if the recents screen shows up, the switch waits until it is gone.
     */
    private fun scheduleSwitch(towardsOlder: Boolean) {
        handler.removeCallbacks(runPendingSwitch)
        pendingSwitch = towardsOlder
        handler.postDelayed({
            if (pendingSwitch == null) return@postDelayed
            if (systemRecentsVisible) {
                // Gone after about 100 ms. Returning to the same app sends no window state
                // change, so this usually runs on the timeout. In case the recents screen stays
                // open, the switch closes it.
                handler.postDelayed(runPendingSwitch, SystemRecentsTimeoutMs)
            } else {
                runPendingSwitch.run()
            }
        }, SystemRecentsWaitMs)
    }

    /**
     * Switches to the previous app if [towardsOlder], otherwise back to the next one, like
     * swiping right and left along the bottom edge does in the system's gesture navigation.
     */
    private fun switchApp(towardsOlder: Boolean) {
        val now = System.currentTimeMillis()
        val recents = session?.takeIf { now - lastSwitchTime < SessionTimeoutMs }?.toMutableList()
            ?: run {
                sessionIndex = 0
                val foreground = foregroundPackage
                if (foreground != null && recentPackages.firstOrNull() == foreground) {
                    recentPackages.toMutableList<String?>()
                } else {
                    // On the home screen, the most recent app is one swipe away.
                    (listOf<String?>(null) + recentPackages.filter { it != foreground }).toMutableList()
                }
            }

        var index = if (towardsOlder) sessionIndex + 1 else sessionIndex - 1
        Log.d(
            "EdgeAppSwitcher",
            "Switch towardsOlder=$towardsOlder session=$recents index=$sessionIndex -> $index"
        )
        while (index in recents.indices) {
            if (launch(recents[index], towardsOlder)) {
                overlay?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                session = recents
                sessionIndex = index
                lastSwitchTime = now
                return
            }
            // The app is gone, try the one after it.
            recents.removeAt(index)?.let {
                recentPackages.remove(it)
                saveRecents()
            }
            if (!towardsOlder) index--
        }
    }

    /**
     * Brings [packageName]'s task to the front, or goes home if it is null.
     */
    private fun launch(packageName: String?, towardsOlder: Boolean): Boolean {
        val intent = if (packageName == null) {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        } else {
            packageManager.getLaunchIntentForPackage(packageName) ?: return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val options = ActivityOptions.makeCustomAnimation(
            service,
            if (towardsOlder) R.anim.edge_switch_enter_from_left else R.anim.edge_switch_enter_from_right,
            if (towardsOlder) R.anim.edge_switch_exit_to_right else R.anim.edge_switch_exit_to_left,
        )
        Log.d("EdgeAppSwitcher", "Switching to ${packageName ?: "home"}")
        return try {
            service.startActivity(intent, options.toBundle())
            true
        } catch (e: Exception) {
            Log.e("EdgeAppSwitcher", "Could not switch to $packageName", e)
            false
        }
    }

    /**
     * Replays a tap (or long press) on the overlay on the window below it: the overlay stops
     * taking touches for a moment while the tap is dispatched as an accessibility gesture.
     */
    private fun forwardTap(x: Float, y: Float, duration: Long) {
        val view = overlay ?: return
        val lp = layoutParams ?: return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        windowManager.updateViewLayout(view, lp)

        handler.postDelayed({
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        duration.coerceIn(1L, MaxForwardedTapDurationMs)
                    )
                )
                .build()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    restoreTouchable()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    restoreTouchable()
                }
            }
            val dispatched = try {
                service.dispatchGesture(gesture, callback, handler)
            } catch (e: Exception) {
                false
            }
            if (!dispatched) restoreTouchable()
        }, WindowUpdateDelayMs)
    }

    private fun restoreTouchable() {
        handler.postDelayed({
            val view = overlay ?: return@postDelayed
            val lp = layoutParams ?: return@postDelayed
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try {
                windowManager.updateViewLayout(view, lp)
            } catch (e: Exception) {
                Log.e("EdgeAppSwitcher", "Could not update overlay", e)
            }
        }, WindowUpdateDelayMs)
    }

    companion object {
        private const val PrefRecents = "recents"

        private const val MaxRecentApps = 20

        /**
         * Swipes within this time of the last switch continue through the same order of recent
         * apps instead of going back to the previous one.
         */
        private const val SessionTimeoutMs = 2500L

        private const val MinSwipeDistanceDp = 40f
        private const val HorizontalSwipeRatio = 1.5f

        /**
         * Time to wait after a swipe for the system's recents screen to show up. On Honor it
         * opens about 30 ms after the touch ends.
         */
        private const val SystemRecentsWaitMs = 120L

        /**
         * Time to wait for the system's recents screen to close again. On Honor it closes after
         * about 100 ms, but returning to the same app sends no window state change, so this is
         * usually how long the switch waits.
         */
        private const val SystemRecentsTimeoutMs = 250L

        /**
         * Delay of a switch after the system's recents screen closed, for its transition to end.
         */
        private const val AfterSystemRecentsDelayMs = 80L

        private const val DefaultGestureAreaHeightDp = 24f
        private const val MinGestureAreaHeightDp = 16f
        private const val MaxGestureAreaHeightDp = 48f

        /**
         * Time for a change of the overlay's touchability to reach the input system.
         */
        private const val WindowUpdateDelayMs = 50L
        private const val MaxForwardedTapDurationMs = 3000L
    }
}
