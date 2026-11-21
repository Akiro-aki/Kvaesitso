package de.mm20.launcher2.ui.launcher.scaffold

import android.Manifest
import android.app.WallpaperManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the system's static wallpaper inside the launcher, panned by the scaffold's wallpaper
 * offset, so that a static wallpaper scrolls with the pages.
 *
 * Since Android 13, the system's own static wallpaper (SystemUI's ImageWallpaper) ignores the
 * offsets that launchers report: its window is the size of the screen and it always shows the
 * same part of the image. Live wallpapers still handle the offsets themselves, so this only draws
 * static ones.
 *
 * Reading the wallpaper requires all files access on Android 11 and later. Without it, or for a
 * live wallpaper, nothing is drawn and the system's wallpaper shows as before. The system's
 * wallpaper stays behind the launcher window, so it still shows during app transitions, at the
 * same position as this one on the home page.
 *
 * @param blurRadius blur radius in px when a secondary page is fully open, scaled by the progress
 * towards it, replacing the window's blur behind, which only reaches the system's wallpaper.
 */
@Composable
internal fun StaticWallpaper(
    state: LauncherScaffoldState,
    wallpaperManager: WallpaperManager,
    blurRadius: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var reloadKey by remember { mutableIntStateOf(0) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }

    // Reloads when the wallpaper changes (Paperize changes it on a schedule)
    DisposableEffect(wallpaperManager) {
        val listener = WallpaperManager.OnColorsChangedListener { _, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) reloadKey++
        }
        wallpaperManager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose {
            wallpaperManager.removeOnColorsChangedListener(listener)
        }
    }

    // ... and whenever the launcher comes back, in case it changed in the meantime or all files
    // access was just granted.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) reloadKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(reloadKey) {
        val bitmap = withContext(Dispatchers.IO) {
            loadStaticWallpaper(context, wallpaperManager)
        }
        // WallpaperManager caches the bitmap until the wallpaper changes, so the same wallpaper
        // comes back as the same object and needs no redraw.
        if (bitmap !== source) {
            source = bitmap
            wallpaper = bitmap?.asImageBitmap()?.also { it.prepareToDraw() }
        }
    }

    val image = wallpaper ?: return

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                val radius = blurRadius * state.currentProgress.coerceIn(0f, 1f)
                renderEffect = if (radius >= 1f) {
                    BlurEffect(radius, radius, TileMode.Clamp)
                } else null
            }
    ) {
        // Scaled to fill the screen, like the system draws it; the overflow is what scrolls.
        val scale = max(size.width / image.width, size.height / image.height)
        val drawnWidth = image.width * scale
        val drawnHeight = image.height * scale
        val offset = state.wallpaperOffset()
        translate(
            left = -(drawnWidth - size.width) * offset.x,
            top = -(drawnHeight - size.height) * offset.y,
        ) {
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstSize = IntSize(drawnWidth.roundToInt(), drawnHeight.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/**
 * The home screen wallpaper as a bitmap, or null if it is a live wallpaper or cannot be read.
 */
private fun loadStaticWallpaper(context: Context, wallpaperManager: WallpaperManager): Bitmap? {
    if (wallpaperManager.wallpaperInfo != null) return null
    if (!canReadWallpaper(context)) return null
    return try {
        val drawable = wallpaperManager.drawable
        (drawable as? BitmapDrawable)?.bitmap
    } catch (e: SecurityException) {
        Log.w("StaticWallpaper", "Cannot read the wallpaper", e)
        null
    } catch (e: Exception) {
        Log.e("StaticWallpaper", "Could not load the wallpaper", e)
        null
    }
}

private fun canReadWallpaper(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Wallpaper offset (0..1 on each axis, 0.5 is the center) for the scaffold's current gesture and
 * progress, as reported to the system and used by [StaticWallpaper].
 *
 * Vertical page changes pan horizontally as well: most wallpapers only scroll along x (live
 * wallpapers like Paperize ignore the y offset entirely, and static wallpapers rarely have
 * vertical overflow). A wallpaper scaled to fill the screen only overflows on one axis, so it never
 * moves diagonally.
 *
 * Sideways swipes without an action add [LauncherScaffoldState.wallpaperPanShift].
 */
internal fun LauncherScaffoldState.wallpaperOffset(): Offset {
    val progress = currentProgress.coerceIn(0f, 1f)
    val pages = when (currentGesture) {
        Gesture.SwipeLeft -> Offset(0.5f + 0.5f * progress, 0.5f)
        Gesture.SwipeRight -> Offset(0.5f - 0.5f * progress, 0.5f)
        Gesture.SwipeUp -> Offset(0.5f + 0.5f * progress, 0.5f + 0.5f * progress)
        Gesture.SwipeDown, Gesture.SwipeDownRight -> Offset(
            0.5f - 0.5f * progress,
            0.5f - 0.5f * progress,
        )

        else -> Offset(0.5f, 0.5f)
    }
    // Plus wherever sideways swipes without an action panned it
    return Offset((pages.x + wallpaperPanShift).coerceIn(0f, 1f), pages.y)
}
