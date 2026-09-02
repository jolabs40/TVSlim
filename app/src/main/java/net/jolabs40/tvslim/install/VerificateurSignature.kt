package net.jolabs40.tvslim.install

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Verdict d'un contrôle de signature. */
sealed interface Verdict {
    data object Conforme : Verdict
    data class Refuse(val motif: String) : Verdict
}

/**
 * Contrôle qu'un APK — ou un paquet déjà installé — porte bien la signature attendue.
 *
 * Shizuku n'est signé qu'en **schéma v2** : `GET_SIGNATURES`, qui ne lit que la vieille
 * signature JAR (v1), ne verrait rien. Il faut `GET_SIGNING_CERTIFICATES`, apparu en API 28 —
 * d'où [verificationPossible], qui conditionne l'installation intégrée. En deçà, l'application
 * s'en tient aux QR codes : mieux vaut pas d'installation qu'une installation non vérifiée.
 */
@Singleton
class VerificateurSignature @Inject constructor(
    @ApplicationContext private val contexte: Context,
) {

    val verificationPossible: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** Contrôle un fichier APK avant son installation. */
    fun verifierApk(chemin: String, paquetAttendu: String, empreinteAttendue: String): Verdict {
        if (!verificationPossible) {
            return Verdict.Refuse("Android ${Build.VERSION.RELEASE} ne permet pas de lire la signature d'un APK.")
        }
        val info = runCatching {
            contexte.packageManager.getPackageArchiveInfo(
                chemin,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        }.getOrNull() ?: return Verdict.Refuse("Fichier illisible ou APK invalide.")

        if (info.packageName != paquetAttendu) {
            return Verdict.Refuse("Paquet inattendu : ${info.packageName}")
        }
        val signataires = info.signingInfo?.apkContentsSigners
        return verdict(signataires, empreinteAttendue)
    }

    /** Contrôle un paquet déjà installé — utile si Shizuku a été posé par un autre chemin. */
    fun verifierPaquetInstalle(paquet: String, empreinteAttendue: String): Verdict {
        if (!verificationPossible) return Verdict.Refuse("Contrôle impossible sur cette version d'Android.")
        val info = runCatching {
            contexte.packageManager.getPackageInfo(paquet, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return Verdict.Refuse("Paquet absent.")
        return verdict(info.signingInfo?.apkContentsSigners, empreinteAttendue)
    }

    private fun verdict(signataires: Array<Signature>?, empreinteAttendue: String): Verdict = when {
        signataires.isNullOrEmpty() -> Verdict.Refuse("APK sans signature exploitable.")

        // Plusieurs signataires : on refuse plutôt que de chercher le bon dans le lot.
        signataires.size != 1 -> Verdict.Refuse("${signataires.size} signataires au lieu d'un.")

        else -> {
            val empreinte = empreinte(signataires.first())
            if (empreinte.equals(empreinteAttendue, ignoreCase = true)) {
                Verdict.Conforme
            } else {
                Verdict.Refuse("Signature inattendue : ${empreinte.take(16)}…")
            }
        }
    }

    /** Condensat SHA-256 du certificat X.509 encodé en DER — la valeur qu'affiche `apksigner`. */
    private fun empreinte(signature: Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { octet -> "%02x".format(octet) }
}
