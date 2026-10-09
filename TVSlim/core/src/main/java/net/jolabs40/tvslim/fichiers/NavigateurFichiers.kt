package net.jolabs40.tvslim.fichiers

import net.jolabs40.tvslim.shell.EnvoyeurFichiers
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.RecepteurFichiers
import net.jolabs40.tvslim.shell.ResultatShell

enum class IssueCreation { CREE, NOM_INVALIDE, EXISTE, ECHEC }

data class CreationDossier(val issue: IssueCreation, val nom: String, val detail: String = "")

/**
 * Browses TV folders, uploads files, copies them to the PC and deletes them, like `adb shell ls`, `adb push`,
 * `adb pull` and `adb shell rm`, without a PC for the companion or `adb.exe` for Windows.
 *
 * Runs with the ADB shell's permissions: shared storage (`/sdcard`, `Android/data` included), removable volumes
 * and `/data/local/tmp`. Elsewhere the TV refuses file by file; no list is kept here, it would go stale. The one
 * exception: a whole storage root is never deleted (`InventaireDossier`).
 *
 * Nothing here is journaled (no setting changes, and deletes cannot be undone). The companion passes no
 * [recepteur]: it copies nothing to the phone.
 */
class NavigateurFichiers(
    private val executeur: ExecuteurCommande,
    private val envoyeur: EnvoyeurFichiers,
    private val recepteur: RecepteurFichiers? = null,
) {

    /** A read, safe to replay after a disconnect, so it goes through the regular command path. */
    suspend fun lister(chemin: String): LectureDossier {
        val normal = CheminDistant.normaliser(chemin)
        return LecteurDossier.lire(normal, executeur.executer(LecteurDossier.commande(normal)))
    }

    suspend fun raccourcis(): List<Raccourci> {
        val lus = executeur.executer(LecteurDossier.COMMANDE_VOLUMES)
        return Raccourci.avecVolumes(if (lus.reussi) LecteurDossier.volumes(lus.sortie) else emptyList())
    }

    /**
     * Checks what uploading [lot] into [destination] would do, without sending anything: names first, then
     * the folder, re-read now since it may have changed since it was displayed.
     */
    suspend fun examiner(lot: LotLocal, destination: String): ExamenDepot {
        if (lot.vide) return ExamenDepot.Refuse(RefusDepot.VIDE)
        val invalides = (lot.fichiers.map { it.chemin } + lot.dossiers)
            .filter { chemin -> chemin.split('/').any { !CheminDistant.nomValide(it) } }
        if (invalides.isNotEmpty()) return ExamenDepot.Refuse(RefusDepot.NOM_INVALIDE, invalides.take(NOMS_MAX))

        val lecture = lister(destination) as? LectureDossier.Lue
            ?: return ExamenDepot.Refuse(RefusDepot.DESTINATION_ILLISIBLE)
        val presents = lecture.entrees.associateBy { it.nom }
        val racines = lot.racines
        val contraires = racines.filter { (nom, dossier) -> presents[nom]?.let { it.dossier != dossier } == true }
        if (contraires.isNotEmpty()) {
            return ExamenDepot.Refuse(RefusDepot.NATURE_DIFFERENTE, contraires.keys.sorted().take(NOMS_MAX))
        }
        return ExamenDepot.Pret(PlanDepot(lecture.chemin, lot, racines.keys.filter { it in presents }.sorted()))
    }

    /**
     * Uploads a confirmed plan: folders first (empty ones included), then files one by one.
     *
     * A rejected file does not stop the others: Android's refusal is recorded and the upload goes on. A lost
     * connection does stop it. [annule] is checked between files and during each one.
     */
    suspend fun deposer(
        plan: PlanDepot,
        annule: () -> Boolean = { false },
        surAvancee: (AvanceeDepot) -> Unit = {},
    ): ResultatDepot {
        val fichiers = plan.lot.fichiers
        val total = plan.lot.taille
        val bilan = ResultatDepot(plan.destination, envoyes = 0, nombre = fichiers.size)

        // `mkdir -p` ignores existing folders, so it is safe to replay after a disconnect.
        val dossiers = plan.lot.dossiersACreer.map { CheminDistant.joindre(plan.destination, it) }
        for (lot in enLots(dossiers)) {
            val creation = executeur.executer("mkdir -p $lot")
            if (!creation.reussi) {
                val motif = creation.sortie.ifBlank { "Code de retour ${creation.code}." }
                return bilan.copy(echecs = listOf(EchecDepot(plan.destination, motif)), interrompu = creation.code < 0)
            }
        }

        var envoyes = 0
        var partis = 0L
        val echecs = mutableListOf<EchecDepot>()
        for ((index, fichier) in fichiers.withIndex()) {
            if (annule()) return bilan.copy(envoyes = envoyes, echecs = echecs, annule = true)
            val avancee = AvanceeDepot(fichier.chemin, index + 1, fichiers.size, partis, total)
            surAvancee(avancee)

            val source = try {
                fichier.ouvrir()
            } catch (erreur: Exception) {
                // A local file that cannot be read (locked, deleted since it was picked) does not stop the others.
                echecs += EchecDepot(fichier.chemin, erreur.message ?: erreur.javaClass.simpleName)
                partis += fichier.taille
                continue
            }
            val reponse = envoyeur.envoyer(
                source = source,
                taille = fichier.taille,
                chemin = CheminDistant.joindre(plan.destination, fichier.chemin),
                date = fichier.date,
                annule = annule,
            ) { envoye -> surAvancee(avancee.copy(envoye = partis + envoye)) }
            partis += fichier.taille

            when {
                reponse.reussi -> envoyes++
                annule() -> return bilan.copy(envoyes = envoyes, echecs = echecs, annule = true)
                reponse.code < 0 -> return bilan.copy(
                    envoyes = envoyes,
                    echecs = echecs + EchecDepot(fichier.chemin, reponse.sortie),
                    interrompu = true,
                )

                // Empty when the TV refuses without a message; each app then shows its own localized text.
                else -> echecs += EchecDepot(fichier.chemin, reponse.sortie)
            }
        }
        return bilan.copy(envoyes = envoyes, echecs = echecs)
    }

    /** Creates an empty folder in [parent]. An existing name is reported rather than treated as success. */
    suspend fun creerDossier(parent: String, nom: String): CreationDossier {
        val propre = nom.trim()
        if (!CheminDistant.nomValide(propre)) return CreationDossier(IssueCreation.NOM_INVALIDE, propre)
        val chemin = citer(CheminDistant.joindre(CheminDistant.normaliser(parent), propre))
        val reponse = executeur.executer("[ -e $chemin ] && exit $CODE_EXISTE; mkdir $chemin")
        return when {
            reponse.reussi -> CreationDossier(IssueCreation.CREE, propre)
            reponse.code == CODE_EXISTE -> CreationDossier(IssueCreation.EXISTE, propre)
            else -> CreationDossier(IssueCreation.ECHEC, propre, reponse.sortie.ifBlank { "Code de retour ${reponse.code}." })
        }
    }

    /**
     * Prepares copying [entree], seen in [dossier], to [cible] as [nom]. A file needs nothing more; a folder
     * is listed recursively to know what will arrive and how big it is.
     */
    suspend fun preparerRapatriement(
        dossier: String,
        entree: EntreeDistante,
        cible: CibleLocale,
        nom: String,
    ): ExamenRapatriement {
        val source = CheminDistant.joindre(CheminDistant.normaliser(dossier), entree.nom)
        if (!entree.dossier) {
            val fichier = FichierDistant(source, nom, entree.taille, entree.date)
            return ExamenRapatriement.Pret(PlanRapatriement(source, cible, nom, dossier = false, fichiers = listOf(fichier)))
        }
        val lue = InventaireDossier.lire(executeur.executer(InventaireDossier.commande(source, garde = false)))
        return when (lue) {
            is LectureInventaire.Lu -> ExamenRapatriement.Pret(
                PlanRapatriement(
                    source = source,
                    cible = cible,
                    nom = nom,
                    dossier = true,
                    fichiers = lue.inventaire.fichiers.map {
                        FichierDistant(CheminDistant.joindre(source, it.chemin), "$nom/${it.chemin}", it.taille, it.date)
                    },
                    dossiers = listOf(nom) + lue.inventaire.dossiers.map { "$nom/$it" },
                    existant = cible.existe(nom),
                ),
            )

            is LectureInventaire.Illisible -> ExamenRapatriement.Illisible(lue.refus, lue.motif)
            // Only the listing before a delete runs the guard, so this cannot happen here.
            LectureInventaire.Protege -> ExamenRapatriement.Illisible(RefusLecture.ECHEC)
        }
    }

    /**
     * Copies a plan to the PC: folders first (empty ones included), then files one by one.
     *
     * As with uploads, a file rejected by the TV or by the disk does not stop the others; a lost connection
     * does. A file only reaches the target complete: if stopped or cut off, it leaves nothing behind.
     */
    suspend fun rapatrier(
        plan: PlanRapatriement,
        annule: () -> Boolean = { false },
        surAvancee: (AvanceeDepot) -> Unit = {},
    ): ResultatDepot {
        val recepteur = checkNotNull(recepteur) { "Cette application ne copie rien vers l'appareil." }
        val fichiers = plan.fichiers
        val bilan = ResultatDepot(plan.destination, envoyes = 0, nombre = fichiers.size, sens = SensTransfert.RECEPTION)

        for (dossier in plan.dossiers) {
            try {
                plan.cible.creerDossier(dossier)
            } catch (erreur: Exception) {
                // A folder the disk refuses would receive none of its contents.
                return bilan.copy(echecs = listOf(EchecDepot(dossier, motif(erreur))))
            }
        }

        var copies = 0
        var recus = 0L
        val echecs = mutableListOf<EchecDepot>()
        for ((index, fichier) in fichiers.withIndex()) {
            if (annule()) return bilan.copy(envoyes = copies, echecs = echecs, annule = true)
            val avancee = AvanceeDepot(fichier.local, index + 1, fichiers.size, recus, plan.taille, SensTransfert.RECEPTION)
            surAvancee(avancee)

            val ecriture = try {
                plan.cible.ecrire(fichier.local)
            } catch (erreur: Exception) {
                // A file the disk refuses (protected folder, impossible name) does not stop the others.
                echecs += EchecDepot(fichier.local, motif(erreur))
                recus += fichier.taille
                continue
            }
            val reponse = ecriture.use { ouverte ->
                val recue = recepteur.recevoir(fichier.distant, ouverte.flux, fichier.taille, annule) { recu ->
                    surAvancee(avancee.copy(envoye = recus + recu))
                }
                if (recue.reussi) valider(ouverte, fichier.date) else recue
            }
            recus += fichier.taille

            when {
                reponse.reussi -> copies++
                annule() -> return bilan.copy(envoyes = copies, echecs = echecs, annule = true)
                reponse.code < 0 -> return bilan.copy(
                    envoyes = copies,
                    echecs = echecs + EchecDepot(fichier.local, reponse.sortie),
                    interrompu = true,
                )

                else -> echecs += EchecDepot(fichier.local, reponse.sortie)
            }
        }
        return bilan.copy(envoyes = copies, echecs = echecs)
    }

    /** Moves the received file into place; a disk refusal (file open elsewhere) counts as a failure. */
    private fun valider(ecriture: EcritureLocale, date: Long): ResultatShell = try {
        ecriture.valider(date)
        ResultatShell(0, "")
    } catch (erreur: Exception) {
        ResultatShell(1, motif(erreur))
    }

    /**
     * Prepares deleting [entree], seen in [dossier]. A folder is listed recursively so the confirmation can say
     * what it holds, and a whole storage root is rejected right here.
     */
    suspend fun preparerSuppression(dossier: String, entree: EntreeDistante): ExamenSuppression {
        val chemin = CheminDistant.joindre(CheminDistant.normaliser(dossier), entree.nom)
        if (entree.lien) return ExamenSuppression.Pret(PlanSuppression(chemin, NatureSuppression.LIEN))
        if (!entree.dossier) {
            return ExamenSuppression.Pret(PlanSuppression(chemin, NatureSuppression.FICHIER, fichiers = 1, taille = entree.taille))
        }
        return when (val lue = InventaireDossier.lire(executeur.executer(InventaireDossier.commande(chemin, garde = true)))) {
            is LectureInventaire.Lu -> ExamenSuppression.Pret(
                PlanSuppression(
                    chemin = chemin,
                    nature = NatureSuppression.DOSSIER,
                    fichiers = lue.inventaire.fichiers.size,
                    dossiers = lue.inventaire.dossiers.size,
                    taille = lue.inventaire.taille,
                ),
            )

            LectureInventaire.Protege -> ExamenSuppression.Protege
            is LectureInventaire.Illisible -> ExamenSuppression.Illisible(lue.refus, lue.motif)
        }
    }

    /** Deletes a confirmed plan. Whatever the TV refuses stays in place, and its answer says what. */
    suspend fun supprimer(plan: PlanSuppression): SuppressionEntree {
        val reponse = executeur.executer(InventaireDossier.commandeSuppression(plan.chemin, plan.nature))
        return InventaireDossier.suppression(plan.nom, reponse)
    }

    private fun motif(erreur: Exception): String = erreur.message ?: erreur.javaClass.simpleName

    /**
     * Groups quoted paths into commands of reasonable length: the command travels in the name of the ADB
     * service being opened, which old `adbd` versions limit to 4 KB.
     */
    private fun enLots(chemins: List<String>): List<String> {
        val lots = mutableListOf<String>()
        val courant = StringBuilder()
        chemins.map(::citer).forEach { cite ->
            if (courant.isNotEmpty() && courant.length + 1 + cite.length > LONGUEUR_MAX) {
                lots += courant.toString()
                courant.clear()
            }
            if (courant.isNotEmpty()) courant.append(' ')
            courant.append(cite)
        }
        if (courant.isNotEmpty()) lots += courant.toString()
        return lots
    }

    private companion object {
        /** Beyond this, listing the offending names in a message stops being useful. */
        const val NOMS_MAX = 5

        const val LONGUEUR_MAX = 3_500

        const val CODE_EXISTE = 4
    }
}
