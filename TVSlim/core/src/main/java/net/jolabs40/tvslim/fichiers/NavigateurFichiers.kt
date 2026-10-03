package net.jolabs40.tvslim.fichiers

import net.jolabs40.tvslim.shell.EnvoyeurFichiers
import net.jolabs40.tvslim.shell.ExecuteurCommande

enum class IssueCreation { CREE, NOM_INVALIDE, EXISTE, ECHEC }

data class CreationDossier(val issue: IssueCreation, val nom: String, val detail: String = "")

/**
 * Parcourt les dossiers du téléviseur et y dépose des fichiers : ce que font `adb shell ls` et `adb push`, sans
 * ordinateur pour le compagnon et sans `adb.exe` pour Windows.
 *
 * Les droits sont ceux du shell d'ADB : le stockage partagé (`/sdcard`, `Android/data` compris), les volumes
 * amovibles et `/data/local/tmp`. Le reste se lit parfois, ne s'écrit jamais — et c'est le téléviseur qui le
 * dit, fichier par fichier, plutôt qu'une liste tenue ici qui finirait par mentir.
 *
 * Rien n'est effacé, et un envoi ne va pas au journal : il ne touche à aucun réglage, et le dossier montre
 * ce qu'il a déposé.
 */
class NavigateurFichiers(
    private val executeur: ExecuteurCommande,
    private val envoyeur: EnvoyeurFichiers,
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

                else -> echecs += EchecDepot(fichier.chemin, reponse.sortie.ifBlank { "Refusé par le téléviseur." })
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
