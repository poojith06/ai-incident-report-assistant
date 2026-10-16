package com.example.test_app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.test_app.domain.model.MediaItem
import com.example.test_app.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/** Square photo thumbnail decoded off the main thread (down-sampled). Used by: HomeScreen (attached gallery photos), ReviewScreen. */
@Composable
fun Thumbnail(item: MediaItem, index: Int, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, item.localUri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val path = File(URI(item.localUri)).path
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val opts = BitmapFactory.Options().apply { inSampleSize = (bounds.outWidth / 256).coerceAtLeast(1) }
                BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(AppTheme.radii.sm)
    Box(
        modifier
            .size(AppTheme.sizes.thumbnail)
            .clip(shape)
            .background(AppTheme.colors.surfaceElevated)
            .semantics { contentDescription = "Photo ${index + 1}" },
    ) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(AppTheme.sizes.thumbnail)) }
    }
}
