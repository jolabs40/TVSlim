package net.jolabs40.tvslim.remote.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.PhoneCapture

@Composable
fun CaptureButton(inProgress: Boolean, onCapture: () -> Unit) {
    if (inProgress) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    } else {
        IconButton(onClick = onCapture) {
            Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.screen_capture))
        }
    }
}

@Composable
fun CapturePreviewDialog(capture: PhoneCapture, onShare: () -> Unit, onClose: () -> Unit) {
    val image = remember(capture) { BitmapFactory.decodeByteArray(capture.png, 0, capture.png.size)?.asImageBitmap() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.capture_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                image?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                Text(stringResource(R.string.capture_saved, capture.location), style = MaterialTheme.typography.bodySmall)
                Text(
                    text = stringResource(R.string.capture_drm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onShare) { Text(stringResource(R.string.capture_share)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.capture_close)) } },
    )
}
