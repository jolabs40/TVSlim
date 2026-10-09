package net.jolabs40.tvslim.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR code encoded on the device, with no network call or third-party service.
 *
 * The practical way to hand over an address from a TV: most Android TVs have no browser, and typing a URL
 * with a remote is painful. Drawn on white because scanners read inverted QR codes poorly.
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    val image = remember(content) { encoder(content) } ?: return

    Image(
        bitmap = image.asImageBitmap(),
        contentDescription = content,
        modifier = modifier
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .size(size),
        contentScale = ContentScale.Fit,
        // Otherwise interpolation blurs the modules and breaks scanning.
        filterQuality = FilterQuality.None,
    )
}

private fun encoder(content: String): Bitmap? = runCatching {
    val side = SIDE_PIXELS
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        side,
        side,
        mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
    val pixels = IntArray(side * side) { pixelIndex ->
        if (matrix.get(pixelIndex % side, pixelIndex / side)) BLACK else WHITE
    }
    Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, side, 0, 0, side, side)
    }
}.getOrNull()

private const val SIDE_PIXELS = 360
private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
