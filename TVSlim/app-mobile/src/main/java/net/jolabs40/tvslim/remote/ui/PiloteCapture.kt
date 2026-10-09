package net.jolabs40.tvslim.remote.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.ecran.CaptureEcran
import net.jolabs40.tvslim.ecran.CauseCapture
import net.jolabs40.tvslim.ecran.ResultatCapture
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.shell.LecteurBinaire
import java.io.File
import java.io.IOException
import java.time.LocalDateTime

/** A screenshot saved on the phone. */
class CaptureTelephone(
    val uri: Uri,
    val png: ByteArray,
    val largeur: Int,
    val hauteur: Int,
    /** Location shown to the user: `Pictures/TV Slim`, or the app's own folder. */
    val emplacement: String,
)

data class EtatCapture(val enCours: Boolean = false, val derniere: CaptureTelephone? = null)

/**
 * Takes a TV screenshot over the app's ADB session.
 *
 * Android 10+: saved to the gallery under `Pictures/TV Slim`, no permission needed. Older versions: saved in the
 * app's folder and shared through a `FileProvider`, since the gallery would need the storage permission.
 */
class PiloteCapture(
    private val contexte: Context,
    private val lecteur: LecteurBinaire,
    private val portee: CoroutineScope,
    private val infos: () -> InfosAppareil,
    private val connecte: () -> Boolean,
    private val afficher: (String) -> Unit,
) {

    private val _etat = MutableStateFlow(EtatCapture())
    val etat: StateFlow<EtatCapture> = _etat.asStateFlow()

    fun capturer() {
        if (!connecte()) return afficher(contexte.getString(R.string.msg_connect_first))
        if (_etat.value.enCours) return
        _etat.update { it.copy(enCours = true) }
        portee.launch {
            when (val resultat = CaptureEcran(lecteur).capturer()) {
                is ResultatCapture.Reussie -> enregistrer(resultat)
                is ResultatCapture.Echouee -> afficher(messageEchec(resultat))
            }
            _etat.update { it.copy(enCours = false) }
        }
    }

    private suspend fun enregistrer(resultat: ResultatCapture.Reussie) {
        val nom = CaptureEcran.nomFichier(infos(), LocalDateTime.now(), "png")
        runCatching {
            withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) dansLaGalerie(nom, resultat.png) else dansLApplication(nom, resultat.png)
            }
        }.onSuccess { (uri, emplacement) ->
            _etat.update { it.copy(derniere = CaptureTelephone(uri, resultat.png, resultat.largeur, resultat.hauteur, emplacement)) }
        }.onFailure {
            afficher(contexte.getString(R.string.capture_write_failed, it.message.orEmpty()))
        }
    }

    /** Android 10+: MediaStore without permission, marked pending until the write completes. */
    private fun dansLaGalerie(nom: String, png: ByteArray): Pair<Uri, String> {
        val resolveur = contexte.contentResolver
        val valeurs = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nom)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SOUS_DOSSIER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolveur.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valeurs)
            ?: throw IOException("MediaStore a refusé l'image")
        try {
            resolveur.openOutputStream(uri)?.use { it.write(png) } ?: throw IOException("image non ouverte en écriture")
            resolveur.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (erreur: Exception) {
            resolveur.delete(uri, null, null)
            throw erreur
        }
        return uri to "${Environment.DIRECTORY_PICTURES}/$SOUS_DOSSIER"
    }

    /** Before Android 10: the app-specific pictures folder, accessible without permission. */
    private fun dansLApplication(nom: String, png: ByteArray): Pair<Uri, String> {
        val dossier = File(contexte.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: contexte.filesDir, SOUS_DOSSIER)
        dossier.mkdirs()
        val fichier = File(dossier, nom).apply { writeBytes(png) }
        val uri = FileProvider.getUriForFile(contexte, "${contexte.packageName}.captures", fichier)
        return uri to dossier.path
    }

    /** Android share sheet; read permission is granted for this image only. */
    fun intentionPartage(capture: CaptureTelephone): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, capture.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            contexte.getString(R.string.capture_share_title),
        )

    fun fermer() = _etat.update { it.copy(derniere = null) }

    private fun messageEchec(echec: ResultatCapture.Echouee): String = contexte.getString(
        when (echec.cause) {
            CauseCapture.CONNEXION -> R.string.capture_failed_connection
            CauseCapture.REFUSEE -> R.string.capture_failed_refused
            CauseCapture.ILLISIBLE -> R.string.capture_failed_unreadable
        },
        echec.detail,
    )

    private companion object {
        const val SOUS_DOSSIER = "TV Slim"
    }
}
