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

/** Une application de l'appareil : du menu, ou installée par la personne. */
class ApplicationAppareil(
    val paquet: String,
    val versionCode: Long,
    /** Livrée avec l'appareil — mise à jour comprise. Faux : installée par la personne. */
    val systeme: Boolean,
    val active: Boolean,
    /** L'activité qui l'ouvre (« paquet/.Activité ») ; `null` pour une application sans icône dans le menu. */
    val lancement: String?,
    /** Son nom affiché ; le paquet tant qu'il n'a pas été lu. */
    val nom: String,
    /** Son icône en PNG ; `null` tant qu'elle n'a pas été lue. */
    val icone: ByteArray?,
) {
    val lue: Boolean get() = icone != null

    fun avec(nom: String, icone: ByteArray?): ApplicationAppareil =
        ApplicationAppareil(paquet, versionCode, systeme, active, lancement, nom, icone)
}

/** Ce que l'aide a rendu de lisible : le nom et l'icône d'une application. */
class DetailsApplication(val nom: String, val icone: ByteArray)

/** Garde noms et icônes d'une lecture à l'autre : une application ne se relit que si sa version a changé. */
interface CacheApplications {
    fun lire(paquet: String, versionCode: Long): DetailsApplication?

    fun ecrire(paquet: String, versionCode: Long, details: DetailsApplication)
}

/**
 * Le cache sur le disque, un fichier pour l'icône et un pour le nom, par paquet et par version — devant un cache en
 * mémoire, pour ne relire le disque qu'une fois par session. Rien de grave s'il est effacé : tout se relit.
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

/** Pourquoi la liste n'a pas pu être lue — chaque application le dit dans sa langue. */
enum class CauseLecture {
    /** L'aide manque aux ressources de l'application : une build incomplète. */
    AIDE_ABSENTE,

    /** L'appareil a refusé l'aide, ou la connexion a lâché pendant son envoi. */
    ENVOI,

    /** L'aide n'a pas répondu comme attendu : `app_process` refusé, autre version. */
    AIDE_REFUSEE,

    CONNEXION,
}

sealed interface ResultatLecture {
    data class Lues(val applications: List<ApplicationAppareil>) : ResultatLecture

    data class Echec(val cause: CauseLecture, val detail: String) : ResultatLecture
}

/**
 * Les applications de l'appareil, avec leur nom et leur icône — ce qu'aucune commande d'ADB ne donne.
 *
 * Il faut donc le demander à Android lui-même : la petite **aide** (`aide/`, quelques Ko, embarquée dans les
 * ressources) est copiée dans `/data/local/tmp`, lancée par `app_process` avec les droits du shell, comme scrcpy son
 * serveur, puis effacée. Rien n'est installé.
 *
 * Deux temps : la liste, rapide (1,6 s sur la TCL) ; puis noms et icônes, par lots, seulement pour ce que le
 * [cache] ne connaît pas — un téléphone de 200 applications demande une demi-minute la première fois.
 */
class LecteurApplications(
    private val executeur: ExecuteurCommande,
    private val envoyeur: EnvoyeurFichiers,
    private val aide: () -> InputStream?,
    private val cache: CacheApplications,
) {

    /**
     * Lit la liste, puis complète noms et icônes. [surAvancee] reçoit la liste à chaque étape — d'abord les paquets
     * seuls, puis de plus en plus de noms et d'icônes —, avec le nombre de détails lus sur le nombre à lire.
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
        /** Où l'aide est rangée dans les ressources des deux applications. */
        const val CHEMIN_RESSOURCE = "aide/tvslim-aide.apk"
        const val CHEMIN_AIDE = "/data/local/tmp/tvslim-aide.apk"
        const val CLASSE_AIDE = "net.jolabs40.tvslim.aide.Aide"
        const val VERSION_AIDE = 1

        /** 96 px : net à 48 dp sur un écran à deux pixels par point, 4 Ko par icône. */
        const val TAILLE_ICONE = 96

        /** Assez court pour tenir dans le délai d'une commande, même sur un téléphone lent à charger ses ressources. */
        const val LOT = 12

        private val IDENTIFIANT = Regex("""[A-Za-z0-9_.]+""")

        fun commande(arguments: String) = "CLASSPATH=$CHEMIN_AIDE app_process / $CLASSE_AIDE $arguments"

        internal fun versionReconnue(sortie: String): Boolean =
            sortie.lineSequence().any { it.trim() == "TVSLIM_AIDE $VERSION_AIDE" }

        /** « A paquet versionCode systeme active lancement », une ligne par application ; le reste est ignoré. */
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

        /** « D paquet nom icône » ; une ligne illisible est sautée, son application garde son paquet pour nom. */
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

        /** Par nom, sans tenir compte de la casse : « YouTube » entre « Wi-Fi » et « Zoom ». */
        fun trier(applications: List<ApplicationAppareil>): List<ApplicationAppareil> =
            applications.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.nom })

        private fun debut(sortie: String) = sortie.lines().filter { it.isNotBlank() }.take(3).joinToString(" ")
    }
}

/** Ce qu'on fait d'une application depuis l'onglet Applications, hors désactivation — elle passe par le moteur. */
class ActionsApplications(
    private val executeur: ExecuteurCommande,
    private val journal: () -> JournalRepository?,
) {

    /** L'ouvre sur l'appareil, comme depuis son menu. */
    suspend fun ouvrir(application: ApplicationAppareil): ResultatAction {
        val lancement = application.lancement
        if (lancement == null || !COMPOSANT.matches(lancement)) {
            return ResultatAction(application.paquet, application.nom, false, motif = MotifMoteur.AucuneActivite)
        }
        // Entre apostrophes : un nom d'activité interne porte un « $ », que le shell prendrait pour une variable.
        val sortie = executeur.executer("am start -n '$lancement'")
        val reussi = sortie.reussi && !sortie.sortie.contains("Error")
        val message = if (reussi) "" else sortie.sortie.trim()
        return ResultatAction(application.paquet, application.nom, reussi, message, motifSiMuet(reussi, message))
    }

    /**
     * Désinstalle une application **que la personne a installée**, relue comme telle à l'instant (`pm list
     * packages -3`) : un paquet du système ne se désinstalle jamais, ni ici ni ailleurs — le moteur de débloat ne
     * connaît que `disable-user`. Ne s'annule pas : il faudrait la réinstaller. Consignée au journal.
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
