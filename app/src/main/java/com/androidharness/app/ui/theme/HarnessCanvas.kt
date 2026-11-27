package com.androidharness.app.ui.theme

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.androidharness.app.data.HarnessCanvasSettings
import com.androidharness.app.data.HarnessCanvasStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop

internal val LocalHarnessCanvasVisible = staticCompositionLocalOf { false }

@Composable
internal fun HarnessCanvasBackdrop(
    settings: HarnessCanvasSettings,
    isChat: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val file = remember(context, settings.imageId) { HarnessCanvasStore.image(context, settings.imageId) }
    val visible = file != null && settings.enabled && (if (isChat) settings.chat else settings.code)
    SideEffect { HarnessCanvasMemory.settings = settings }
    // A screen opened by navigation finds the decoded image already in memory, so its first
    // frame has the canvas instead of a bare surface that the image then fades over.
    val cacheKey = file?.let { "${it.path}:${it.lastModified()}" }
    val cached = if (visible) HarnessCanvasMemory.bitmapFor(cacheKey) else null
    val bitmap by produceState(cached, cacheKey, visible) {
        value = if (!visible) null else HarnessCanvasMemory.bitmapFor(cacheKey) ?: withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file!!.path)?.asImageBitmap() }.getOrNull()
        }?.also { HarnessCanvasMemory.remember(cacheKey, it) }
    }
    val parentBackdrop = LocalHarnessGlassBackdrop.current
    val wallpaperBackdrop = rememberHarnessGlassScene(backgroundOnly = true)
    val combinedBackdrop = if (parentBackdrop != null && wallpaperBackdrop != null) rememberCombinedBackdrop(parentBackdrop, wallpaperBackdrop) else null
    val screen = LocalHarnessGlassScene.current
    DisposableEffect(screen, combinedBackdrop) {
        screen?.background = combinedBackdrop
        onDispose { if (screen?.background === combinedBackdrop) screen?.background = null }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.matchParentSize().recordHarnessGlassScene(wallpaperBackdrop)) {
        val alpha = 1f - settings.dim.takeIf { it.isFinite() }.let { (it ?: .65f).coerceIn(0f, 1f) }
        val blurDp = MAX_CANVAS_BLUR_DP * settings.blur.takeIf { it.isFinite() }.let { (it ?: 0f).coerceIn(0f, 1f) }
        val animated = file?.takeIf { visible && Build.VERSION.SDK_INT >= 28 && it.extension in setOf("gif", "webp") }
        if (animated != null && Build.VERSION.SDK_INT >= 28) AnimatedCanvasImage(animated, alpha, blurDp)
        else Crossfade(bitmap, modifier = Modifier.fillMaxSize(), animationSpec = defaultEffectsSpec(), label = "Harness Canvas") { image ->
            if (image != null) Image(image, null, Modifier.fillMaxSize().blur(blurDp.dp, BlurredEdgeTreatment.Rectangle),
                contentScale = ContentScale.Crop, alpha = alpha)
        }
        }
        CompositionLocalProvider(LocalHarnessCanvasVisible provides (visible && bitmap != null),
            LocalHarnessGlassBackdrop provides (combinedBackdrop ?: parentBackdrop)) { content() }
    }
}

/** Blur radius at the slider's maximum. */
private const val MAX_CANVAS_BLUR_DP = 24f

/** Plays an animated GIF or WebP background, cropped to fill, with the same dimming and blur as a still. */
@RequiresApi(28)
@Composable
private fun AnimatedCanvasImage(file: File, alpha: Float, blurDp: Float) {
    val drawable by produceState<Drawable?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) { runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) }.getOrNull() }
    }
    val image = drawable ?: return
    DisposableEffect(image) {
        (image as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
        onDispose { (image as? AnimatedImageDrawable)?.stop() }
    }
    val blurPx = with(LocalDensity.current) { blurDp.dp.toPx() }
    AndroidView({ ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }, Modifier.fillMaxSize()) { view ->
        if (view.drawable !== image) view.setImageDrawable(image)
        view.alpha = alpha
        if (Build.VERSION.SDK_INT >= 31) view.setRenderEffect(
            if (blurPx > 0f) RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP) else null)
    }
}

/** Last canvas settings and decoded image, shared by every chat screen in this process. */
internal object HarnessCanvasMemory {
    @Volatile var settings: HarnessCanvasSettings? = null
    private var key: String? = null
    private var image: ImageBitmap? = null
    fun bitmapFor(cacheKey: String?): ImageBitmap? = synchronized(this) { image.takeIf { cacheKey != null && key == cacheKey } }
    fun remember(cacheKey: String?, bitmap: ImageBitmap) = synchronized(this) { key = cacheKey; image = bitmap }
}
