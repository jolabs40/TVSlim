package net.jolabs40.tvslim.applications

import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.moteur.MotifMoteur
import net.jolabs40.tvslim.moteur.NatureNom
import net.jolabs40.tvslim.moteur.ResultatAction
import net.jolabs40.tvslim.moteur.motifSiMuet
import net.jolabs40.tvslim.shell.EnvoyeurFichiers
import net.jolabs40.tvslim.shell.ExecuteurCommande
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** An app on the device: either in the launcher menu, or installed by the user. */
class ApplicationAppareil(
    val paquet: String,
    val versionCode: Long,
    /** Shipped with the device (updates included); false when installed by the user. */
    val systeme: Boolean,
    val active: Boolean,
    /** Launch activity (`package/.Activity`), or `null` when the app has no launcher icon. */
    val lancement: String?,
    /** Display name; the package name until it has been read. */
    val nom: String,
    /** PNG icon; `null` until it has been read. */
    val icone: ByteArray?,
) {
    val lue: Boolean get() = icone != null

    fun avec(nom: String, icone: ByteArray?): ApplicationAppareil =
        ApplicationAppareil(paquet, versionCode, systeme, active, lancement, nom, icone)
}

/** Name and icon of an app, as returned by the helper. */
class DetailsApplication(val nom: String, val icone: ByteArray)

/** Caches names and icons; an app is read again only when its version code changes. */
interface CacheApplications {
    fun lire(paquet: String, versionCode: Long): DetailsApplication?

    fun ecrire(paquet: String, versionCode: Long, details: DetailsApplication)
}

/**
 * On-disk cache, one `.png` and one `.nom` file per package and version, fronted by an in-memory cache so the disk
 * is read once per session. Safe to delete: everything is read again.
 */
class CacheApplicationsFichiers(private val dossier: File) : CacheApplications {

    private val memoire = ConcurrentHashMap<String, DetailsApplication>()

    override fun lire(paquet: String, versionCode: Long): DetailsApplication? {
        val cle = cle(paquet, versionCode)
        memoire[cle]?.let { return it }
        val icone = File(dossier, "$cle.png")
        val nom = File(dossier, "$cle.nom")
        if (!icone.isFile || !nom.isFile) return null
        return runCatching { DetailsApplication(nom.readText(Charsets.UTF_8), icone.readBytes()) }
            .getOrNull()
            ?.also { memoire[cle] = it }
    }

    override fun ecrire(paquet: String, versionCode: Long, details: DetailsApplication) {
        val cle = cle(paquet, versionCode)
        memoire[cle] = details
        runCatching {
            dossier.mkdirs()
            File(dossier, "$cle.png").writeBytes(details.icone)
            File(dossier, "$cle.nom").writeText(details.nom, Charsets.UTF_8)
        }
    }

    private fun cle(paquet: String, versionCode: Long) = "$paquet@$versionCode"
}

/** Why the app list could not be read; each app localizes the message. */
enum class CauseLecture {
    /** The helper is missing from the app's resources (incomplete build). */
    AIDE_ABSENTE,

    /** The device rejected the helper, or the connection dropped while pushing it. */
    ENVOI,

    /** Unexpected helper output: `app_process` refused, or a version mismatch. */
    AIDE_REFUSEE,

    CONNEXION,
}

sealed interface ResultatLecture {
    data class Lues(val applications: List<ApplicationAppareil>) : ResultatLecture

    data class Echec(val cause: CauseLecture, val detail: String) : ResultatLecture
}

/**
 * Lists the device's apps with their name and icon, which no ADB command provides.
 *
 * A small helper (`aide/`, a few KB, bundled in the resources) is pushed to `/data/local/tmp`, run with
 * `app_process` as the shell user (the way scrcpy runs its server), then deleted. Nothing is installed.
 *
 * Two passes: the list (1.6 s on the TCL), then names and icons in batches, only for what [cache] lacks. A phone
 * with 200 apps takes about 30 s the first time.
 */
class LecteurApplications(
    private val executeur: ExecuteurCommande,
    private val envoyeur: EnvoyeurFichiers,
    private val aide: () -> InputStream?,
    private val cache: CacheApplications,
) {

    /**
     * Reads the list, then fills in names and icons. [surAvancee] receives the list after each step, with the number
     * of details read so far and the total to read.
     */
    suspend fun lire(surAvancee: (List<ApplicationAppareil>, fait: Int, total: Int) -> Unit = { _, _, _ -> }): ResultatLecture {
        val octets = runCatching { aide()?.use { it.readBytes() } }.getOrNull()
            ?: return ResultatLecture.Echec(CauseLecture.AIDE_ABSENTE, CHEMIN_RESSOURCE)

        val envoi = envoyeur.envoyer(ByteArrayInputStream(octets), octets.size.toLong(), CHEMIN_AIDE, System.currentTimeMillis(), { false }, {})
        if (envoi.code != 0) return ResultatLecture.Echec(if (envoi.code < 0) CauseLecture.CONNEXION else CauseLecture.ENVOI, envoi.sortie)

        try {
            val reponse = executeur.executer(commande("liste"))
            if (reponse.code < 0) return ResultatLecture.Echec(CauseLecture.CONNEXION, reponse.sortie)
            if (!versionReconnue(reponse.sortie)) return ResultatLecture.Echec(CauseLecture.AIDE_REFUSEE, debut(reponse.sortie))

            var applications = trier(lireListe(reponse.sortie).map { app ->
                cache.lire(app.paquet, app.versionCode)?.let { app.avec(it.nom, it.icone) } ?: app
            })
            val aLire = applications.filterNot { it.lue }
            surAvancee(applications, 0, aLire.size)

            var fait = 0
            for (lot in aLire.chunked(LOT)) {
                val details = executeur.executer(commande("details $TAILLE_ICONE " + lot.joinToString(" ") { it.paquet }))
                if (details.code < 0) return ResultatLecture.Echec(CauseLecture.CONNEXION, details.sortie)
                val lus = lireDetails(details.sortie)
                applications = trier(applications.map { app ->
                    val lu = lus[app.paquet] ?: return@map app
                    cache.ecrire(app.paquet, app.versionCode, lu)
                    app.avec(lu.nom, lu.icone)
                })
                fait += lot.size
                surAvancee(applications, fait, aLire.size)
            }
            return ResultatLecture.Lues(applications)
        } finally {
            executeur.executer("rm -f $CHEMIN_AIDE")
        }
    }

    companion object {
        /** Helper location in the resources of both apps. */
        const val CHEMIN_RESSOURCE = "aide/tvslim-aide.apk"
        const val CHEMIN_AIDE = "/data/local/tmp/tvslim-aide.apk"
        const val CLASSE_AIDE = "net.jolabs40.tvslim.aide.Aide"
        const val VERSION_AIDE = 1

        /** 96 px: sharp at 48 dp on a 2x screen, about 4 KB per icon. */
        const val TAILLE_ICONE = 96

        /** Small enough to fit in the command timeout, even on a phone slow to load app resources. */
        const val LOT = 12

        private val IDENTIFIANT = Regex("""[A-Za-z0-9_.]+""")

        fun commande(arguments: String) = "CLASSPATH=$CHEMIN_AIDE app_process / $CLASSE_AIDE $arguments"

        internal fun versionReconnue(sortie: String): Boolean =
            sortie.lineSequence().any { it.trim() == "TVSLIM_AIDE $VERSION_AIDE" }

        /** Parses tab-separated `A package versionCode system enabled launch` lines; other lines are ignored. */
        internal fun lireListe(sortie: String): List<ApplicationAppareil> = sortie.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.startsWith("A\t") }
            .mapNotNull { ligne ->
                val champs = ligne.split('\t')
                if (champs.size < 6 || !IDENTIFIANT.matches(champs[1])) return@mapNotNull null
                ApplicationAppareil(
                    paquet = champs[1],
                    versionCode = champs[2].toLongOrNull() ?: 0L,
                    systeme = champs[3] == "1",
                    active = champs[4] == "1",
                    lancement = champs[5].takeIf { it != "-" && it.contains('/') },
                    nom = champs[1],
                    icone = null,
                )
            }
            .toList()

        /** Parses `D package name icon` lines; an unreadable line is skipped and its app keeps the package as name. */
        internal fun lireDetails(sortie: String): Map<String, DetailsApplication> = sortie.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.startsWith("D\t") }
            .mapNotNull { ligne ->
                val champs = ligne.split('\t')
                if (champs.size < 4) return@mapNotNull null
                val icone = runCatching { Base64.getDecoder().decode(champs[3]) }.getOrNull() ?: return@mapNotNull null
                champs[1] to DetailsApplication(champs[2].ifBlank { champs[1] }, icone)
            }
            .toMap()

        /** Sorts by name, case-insensitively. */
        fun trier(applications: List<ApplicationAppareil>): List<ApplicationAppareil> =
            applications.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.nom })

        private fun debut(sortie: String) = sortie.lines().filter { it.isNotBlank() }.take(3).joinToString(" ")
    }
}

/** Actions from the Applications tab. Disabling is not here: it goes through the debloat engine. */
class ActionsApplications(
    private val executeur: ExecuteurCommande,
    private val journal: () -> JournalRepository?,
) {

    /** Launches the app on the device, as from the launcher. */
    suspend fun ouvrir(application: ApplicationAppareil): ResultatAction {
        val lancement = application.lancement
        if (lancement == null || !COMPOSANT.matches(lancement)) {
            return ResultatAction(application.paquet, application.nom, false, motif = MotifMoteur.AucuneActivite)
        }
        // Single-quoted: nested activity class names contain `$`, which the shell would expand.
        val sortie = executeur.executer("am start -n '$lancement'")
        val reussi = sortie.reussi && !sortie.sortie.contains("Error")
        val message = if (reussi) "" else sortie.sortie.trim()
        return ResultatAction(application.paquet, application.nom, reussi, message, motifSiMuet(reussi, message))
    }

    /**
     * Uninstalls an app the user installed, re-checked right before with `pm list packages -3`. System packages are
     * never uninstalled; the debloat engine only uses `disable-user`. Cannot be undone, and is logged to the journal.
     */
    suspend fun desinstaller(application: ApplicationAppareil): ResultatAction {
        val paquet = application.paquet
        if (!IDENTIFIANT.matches(paquet)) {
            return ResultatAction(paquet, application.nom, false, motif = MotifMoteur.NomInvalide(NatureNom.PAQUET, paquet))
        }
        val tiers = executeur.executer("pm list packages -3 --user 0 $paquet")
        if (tiers.code < 0) return ResultatAction(paquet, application.nom, false, tiers.sortie)
        if (tiers.sortie.lines().none { it.trim() == "package:$paquet" }) {
            return ResultatAction(paquet, application.nom, false, motif = MotifMoteur.PasInstalleeParLaPersonne)
        }
        val sortie = executeur.executer("pm uninstall $paquet")
        val reussi = sortie.reussi && sortie.sortie.contains("Success")
        val message = if (reussi) "" else sortie.sortie.trim()
        journal()?.ajouter(
            listOf(
                ActionJournal(
                    horodatage = System.currentTimeMillis(),
                    type = TypeAction.DESINSTALLATION,
                    cible = paquet,
                    libelle = application.nom,
                    commandeAnnulation = "",
                    reussi = reussi,
                    message = message,
                ),
            ),
        )
        return ResultatAction(paquet, application.nom, reussi, message, motifSiMuet(reussi, message))
    }

    private companion object {
        val IDENTIFIANT = Regex("""[A-Za-z0-9_.]+""")
        val COMPOSANT = Regex("""[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+""")
    }
}
