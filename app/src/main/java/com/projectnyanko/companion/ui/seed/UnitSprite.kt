package com.projectnyanko.companion.ui.seed

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The unit artwork that ships in `assets/units`, named `uni<id>_<form>00.png` where the form letter
 * is f (base), c (evolved), s (true) and u (ultra).
 *
 * Not every unit has every form on disk, so a missing sprite falls back to the base form and then
 * to nothing at all rather than showing a broken image.
 */
@Composable
fun UnitSprite(unitId: Int, formIndex: Int, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, unitId, formIndex) {
        value = withContext(Dispatchers.IO) { loadUnitSprite(context, unitId, formIndex) }
    }
    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
    } else {
        Box(modifier = modifier.size(size))
    }
}

private fun loadUnitSprite(context: Context, unitId: Int, formIndex: Int): ImageBitmap? {
    if (unitId < 0) return null
    val suffix = when (formIndex) {
        0 -> "f"
        1 -> "c"
        2 -> "s"
        else -> "u"
    }
    val paths = buildList {
        add(spritePath(unitId, suffix))
        if (suffix != "f") add(spritePath(unitId, "f"))
    }
    for (path in paths) {
        try {
            context.assets.open(path).use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap != null) return bitmap.asImageBitmap()
            }
        } catch (_: IOException) {
            // No artwork for this form; the loop falls back to the base form.
        }
    }
    return null
}

internal fun spritePath(unitId: Int, suffix: String): String =
    "units/uni${unitId.toString().padStart(3, '0')}_${suffix}00.png"
