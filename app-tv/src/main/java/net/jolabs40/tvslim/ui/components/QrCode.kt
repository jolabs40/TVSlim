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
 * QR code encodé sur l'appareil — aucun appel réseau, aucun service tiers.
 *
 * Sur un téléviseur, c'est la seule façon commode de faire passer une adresse : la plupart des
 * Android TV n'ont pas de navigateur, et saisir une URL à la télécommande est pénible.
 * Le code est rendu sur fond blanc : les scanners lisent mal un QR inversé.
 */
@Composable
fun QrCode(
    contenu: String,
    modifier: Modifier = Modifier,
    taille: Dp = 200.dp,
) {
    val image = remember(contenu) { encoder(contenu) } ?: return

    Image(
        bitmap = image.asImageBitmap(),
        contentDescription = contenu,
        modifier = modifier
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .size(taille),
        contentScale = ContentScale.Fit,
        // Sans quoi l'interpolation floute les modules et casse la lecture.
        filterQuality = FilterQuality.None,
    )
}

private fun encoder(contenu: String): Bitmap? = runCatching {
    val cote = COTE_PIXELS
    val matrice = QRCodeWriter().encode(
        contenu,
        BarcodeFormat.QR_CODE,
        cote,
        cote,
        mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
    val pixels = IntArray(cote * cote) { indice ->
        if (matrice.get(indice % cote, indice / cote)) NOIR else BLANC
    }
    Bitmap.createBitmap(cote, cote, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, cote, 0, 0, cote, cote)
    }
}.getOrNull()

private const val COTE_PIXELS = 360
private const val NOIR = 0xFF000000.toInt()
private const val BLANC = 0xFFFFFFFF.toInt()
