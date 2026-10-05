package net.jolabs40.tvslim.fichiers

import net.jolabs40.tvslim.shell.EnvoyeurFichiers
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.RecepteurFichiers
import net.jolabs40.tvslim.shell.ResultatShell

enum class IssueCreation { CREE, NOM_INVALIDE, EXISTE, ECHEC }

data class CreationDossier(val issue: IssueCreation, val nom: String, val detail: String = "")

/**
 * Parcourt les dossiers du téléviseur, y dépose des fichiers, les copie vers l'ordinateur et les efface : ce que
 * font `adb shell ls`, `adb push`, `adb pull` et `adb shell rm`, sans ordinateur pour le compagnon et sans
 * `adb.exe` pour Windows.
 *
 * Les droits sont ceux du shell d'ADB : le stockage partagé (`/sdcard`, `Android/data` compris), les volumes
 * amovibles et `/data/local/tmp`. Le reste se lit parfois, ne s'écrit jamais — et c'est le téléviseur qui le
 * dit, fichier par fichier, plutôt qu'une liste tenue ici qui finirait par mentir. Une seule exception : un
 * stockage entier ne s'efface pas d'un bloc (`InventaireDossier`).
 *
 * Rien de tout cela ne va au journal : aucun réglage n'est touché, et une suppression ne s'annule pas.
 *
 * [recepteur] manque au compagnon, qui ne copie rien vers le téléphone.
 */
class NavigateurFichiers(
    private val executeur: ExecuteurCommande,
    private val envoyeur: EnvoyeurFichiers,
    private val recepteur: RecepteurFichiers? = null,
) {

    /** Une lecture, rejouée sans risque après une rupture : elle passe par le chemin ordinaire. */
    suspend fun lister(chemin: String): LectureDossier {
        val normal = CheminDistant.normaliser(chemin)
        return LecteurDossier.lire(normal, executeur.executer(LecteurDossier.commande(normal)))
    }

    suspend fun raccourcis(): List<Raccourci> {
        val lus = executeur.executer(LecteurDossier.COMMANDE_VOLUMES)
        return Raccourci.avecVolumes(if (lus.reussi) LecteurDossier.volumes(lus.sortie) else emptyList())
    }

    /**
     * Ce que donnerait l'envoi de [lot] dans [destination], sans rien envoyer : les noms d'abord, puis le
     * dossier, relu à l'instant — celui qu'on regarde a pu changer depuis.
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
     * Envoie un plan confirmé. Les dossiers d'abord, vides compris, puis les fichiers un à un.
     *
     * Un fichier refusé n'arrête pas les suivants : le refus d'Android est noté, et l'envoi continue. Une
     * connexion perdue, si : rien ne passerait plus. [annule] est consulté entre deux fichiers, et pendant
     * chacun.
     */
    suspend fun deposer(
        plan: PlanDepot,
        annule: () -> Boolean = { false },
        surAvancee: (AvanceeDepot) -> Unit = {},
    ): ResultatDepot {
        val fichiers = plan.lot.fichiers
        val total = plan.lot.taille
        val bilan = ResultatDepot(plan.destination, envoyes = 0, nombre = fichiers.size)

        // `mkdir -p` ne se plaint pas de ce qui existe déjà, et se rejoue donc sans risque après une rupture.
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
                // Un fichier illisible sur place — verrouillé, effacé depuis le choix — n'arrête pas les autres.
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

                // Vide quand le téléviseur refuse sans un mot : chaque application le dit alors dans sa langue.
                else -> echecs += EchecDepot(fichier.chemin, reponse.sortie)
            }
        }
        return bilan.copy(envoyes = envoyes, echecs = echecs)
    }

    /** Un dossier vide, dans [parent]. Un nom déjà pris est signalé plutôt que confondu avec un succès. */
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
     * Prépare la copie de [entree], vue dans [dossier], vers [cible] sous le nom [nom]. Un fichier n'a rien à
     * lire de plus ; un dossier se lit à toute profondeur, pour savoir ce qui arrivera et combien cela pèse.
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
            // Seule la lecture qui précède une suppression monte la garde : ce cas ne se présente pas ici.
            LectureInventaire.Protege -> ExamenRapatriement.Illisible(RefusLecture.ECHEC)
        }
    }

    /**
     * Copie un plan vers l'ordinateur : les dossiers d'abord, vides compris, puis les fichiers un à un.
     *
     * Comme pour un dépôt, un fichier refusé — par le téléviseur ou par le disque — n'arrête pas les suivants, une
     * connexion perdue si. Un fichier n'arrive dans la cible qu'entier : arrêté ou coupé, il n'y laisse rien.
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
                // Un dossier que le disque refuse ne recevrait rien de ce qu'il devait contenir.
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
                // Un fichier que le disque refuse — dossier protégé, nom impossible — n'arrête pas les autres.
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

    /** Le fichier arrivé prend sa place ; un disque qui s'y refuse — fichier ouvert ailleurs — vaut un refus. */
    private fun valider(ecriture: EcritureLocale, date: Long): ResultatShell = try {
        ecriture.valider(date)
        ResultatShell(0, "")
    } catch (erreur: Exception) {
        ResultatShell(1, motif(erreur))
    }

    /**
     * Ce qu'effacerait la suppression de [entree], vue dans [dossier]. Un dossier se lit d'abord, à toute
     * profondeur, pour que la confirmation dise ce qu'il contient — et le refus tombe dès ici s'il s'agit d'un
     * stockage entier.
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

    /** Efface un plan confirmé. Ce que le téléviseur refuse reste en place, et sa réponse dit quoi. */
    suspend fun supprimer(plan: PlanSuppression): SuppressionEntree {
        val reponse = executeur.executer(InventaireDossier.commandeSuppression(plan.chemin, plan.nature))
        return InventaireDossier.suppression(plan.nom, reponse)
    }

    private fun motif(erreur: Exception): String = erreur.message ?: erreur.javaClass.simpleName

    /**
     * Des chemins cités, regroupés en commandes de longueur raisonnable : la commande voyage dans le nom du
     * service ADB qu'on ouvre, et un vieil `adbd` le limite à quatre kilo-octets.
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
        /** Au-delà, la liste des noms en cause ne servirait plus à rien dans un message. */
        const val NOMS_MAX = 5

        const val LONGUEUR_MAX = 3_500

        const val CODE_EXISTE = 4
    }
}
