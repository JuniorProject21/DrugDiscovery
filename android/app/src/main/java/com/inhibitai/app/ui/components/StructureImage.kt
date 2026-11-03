package com.inhibitai.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inhibitai.app.ui.theme.CardBorder
import com.inhibitai.app.ui.theme.PurpleLight
import com.inhibitai.app.ui.theme.TextMuted
import kotlinx.coroutines.CancellationException

/** Fetches a 2D drawing of a SMILES string (rendered by RDKit on the backend). */
fun interface StructureImageLoader {
    suspend fun load(smiles: String, widthPx: Int, heightPx: Int, dark: Boolean): ImageBitmap
}

val LocalStructureImageLoader = staticCompositionLocalOf<StructureImageLoader> {
    error("No StructureImageLoader provided")
}

private sealed interface ImageState {
    data object Loading : ImageState
    data class Ready(val image: ImageBitmap) : ImageState
    data object Failed : ImageState
}

/**
 * 2D structure for [smiles], drawn at exactly this composable's pixel size.
 * [dark] = light bonds for dark backgrounds; otherwise dark bonds for a white background.
 */
@Composable
fun StructureImage(
    smiles: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
    showStatus: Boolean = true,
) {
    val loader = LocalStructureImageLoader.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Round to 16 px steps so small layout differences reuse the same cached drawing.
        fun px(max: Int) = if (max == Int.MAX_VALUE || max <= 0) 300 else (max / 16 * 16).coerceIn(32, 1200)
        val width = px(constraints.maxWidth)
        val height = px(constraints.maxHeight)
        val state by produceState<ImageState>(ImageState.Loading, smiles, dark, width, height) {
            value = try {
                ImageState.Ready(loader.load(smiles, width, height, dark))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImageState.Failed
            }
        }
        when (val s = state) {
            is ImageState.Ready -> Image(s.image, "Structure of $smiles", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            ImageState.Loading -> if (showStatus) {
                CircularProgressIndicator(color = PurpleLight, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
            ImageState.Failed -> if (showStatus) {
                Text("Preview unavailable", color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Small framed structure used in list rows. */
@Composable
fun MoleculeThumb(smiles: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .clip(shape)
            .background(Color(0xFF0B0A14))
            .border(1.dp, CardBorder, shape)
            .padding(4.dp),
    ) {
        StructureImage(smiles, dark = true, modifier = Modifier.fillMaxSize(), showStatus = false)
    }
}
