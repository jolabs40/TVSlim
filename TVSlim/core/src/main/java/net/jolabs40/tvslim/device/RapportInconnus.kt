package net.jolabs40.tvslim.device

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Ce qu'on relit du téléviseur au moment d'exporter l'inventaire. Chaque partie peut manquer — un Android
 * trop ancien, une lecture qui échoue — sans empêcher l'inventaire : ses cases disent alors « — ».
 */
data class ReleveInconnus(
    val indices: Map<String, IndicesPaquet> = emptyMap(),
    val memoire: RepartitionMemoire = RepartitionMemoire(),
    val stockage: RepartitionStockage = RepartitionStockage(),
)

/**
 * L'inventaire des paquets inconnus, en Markdown : de quoi compléter le catalogue, joint à un message ou
 * collé dans un ticket. Chaque paquet y porte ce qu'ADB en dit — d'où il vient, sous quelle identité il
 * tourne, ce qu'il déclare au système, ce qu'il occupe —, de quoi juger s'il est prudent d'y toucher avant
 * de le décrire. Rien de personnel n'y figure : l'appareil, son système, des noms de paquets.
 */
object RapportInconnus {

    fun nomPropose(infos: InfosAppareil, jour: LocalDate = LocalDate.now()): String =
        "TVSlim-inconnus-${infos.nomPourFichier}-$jour.md"

    fun markdown(
        infos: InfosAppareil,
        inconnus: List<PaquetInconnu>,
        application: String,
        releve: ReleveInconnus = ReleveInconnus(),
        horodatage: Long = System.currentTimeMillis(),
    ): String = buildString {
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .format(Instant.ofEpochMilli(horodatage).atZone(ZoneId.systemDefault()))
        val parOrigine = inconnus.groupBy { it.origine }
        val indices = inconnus.mapNotNull { releve.indices[it.paquet] }
        val memoire = releve.memoire.kilooctetsParPaquet
        val stockage = releve.stockage.applications.associate { it.paquet to it.totalOctets }

        appendLine("# Paquets inconnus du catalogue TV Slim")
        appendLine()
        appendLine("- Appareil : ${infos.nomAffiche.ifBlank { "inconnu" }}")
        appendLine("- Fabricant déclaré : ${infos.marque.ifBlank { "—" }} ; marque : ${infos.marqueCommerciale.ifBlank { "—" }}")
        appendLine("- Android : ${infos.versionAndroid.ifBlank { "—" }} (${infos.build.ifBlank { "—" }})")
        appendLine("- Relevé le : $date, avec $application")
        appendLine(
            "- Paquets inconnus : ${inconnus.size} — constructeur ${parOrigine[OriginePaquet.CONSTRUCTEUR].orEmpty().size}, " +
                "Android ${parOrigine[OriginePaquet.ANDROID].orEmpty().size}, " +
                "autres ${parOrigine[OriginePaquet.AUTRE].orEmpty().size}",
        )
        if (indices.isNotEmpty()) {
            appendLine(
                "- Avec les droits du système : ${indices.count { it.droitsSysteme }} ; " +
                    "avec une déclaration sensible : ${indices.count { it.declarations.isNotEmpty() }}",
            )
        }
        appendLine("- Lu sur l'appareil : ${lectures(releve)}")
        appendLine()
        appendLine(LEGENDE)

        OriginePaquet.ORDRE.forEach { origine ->
            val paquets = parOrigine[origine].orEmpty()
            if (paquets.isEmpty()) return@forEach
            appendLine()
            appendLine("## ${titre(origine)} (${paquets.size})")
            paquets.groupBy { it.famille }.forEach { (famille, membres) ->
                appendLine()
                appendLine("### $famille (${membres.size})")
                appendLine()
                appendLine("| Paquet | État | Emplacement | Droits | Déclare | Icône | RAM | Stockage |")
                appendLine("|---|---|---|---|---|---|---|---|")
                membres.forEach { inconnu ->
                    val indice = releve.indices[inconnu.paquet]
                    val cases = listOf(
                        "`${inconnu.paquet}`",
                        if (inconnu.etat == EtatPaquet.DESACTIVE) "désactivé" else "actif",
                        indice?.let(::emplacement) ?: "—",
                        indice?.let(::droits) ?: "—",
                        indice?.let(::declarations) ?: "—",
                        indice?.let { if (it.icone) "oui" else "non" } ?: "—",
                        memoire[inconnu.paquet]?.let { taille(it * KO) } ?: "—",
                        stockage[inconnu.paquet]?.let(::taille) ?: "—",
                    )
                    appendLine(cases.joinToString(" | ", prefix = "| ", postfix = " |"))
                }
            }
        }
    }

    /** Ce qui a pu être lu, et ce qui ne l'a pas été : une case « — » ne dit pas la même chose dans les deux cas. */
    private fun lectures(releve: ReleveInconnus): String {
        val parties = listOf(
            "indices ADB" to releve.indices.isNotEmpty(),
            "mémoire vive" to releve.memoire.renseignee,
            "stockage" to releve.stockage.applications.isNotEmpty(),
        )
        val lues = parties.filter { it.second }.joinToString(", ") { it.first }
        val manquees = parties.filterNot { it.second }.joinToString(", ") { it.first }
        return lues.ifEmpty { "la seule liste des paquets" } + if (manquees.isEmpty()) "" else " ; illisible : $manquees"
    }

    private fun emplacement(indice: IndicesPaquet): String =
        indice.emplacement.ifBlank { "—" } + if (indice.misAJour) ", mise à jour" else ""

    private fun droits(indice: IndicesPaquet): String = when {
        indice.uid == null -> "—"
        indice.droitsSysteme -> "système"
        indice.uidReserve -> "plateforme (${indice.uid})"
        else -> "appli"
    }

    private fun declarations(indice: IndicesPaquet): String =
        indice.declarations.sorted().joinToString(", ") { libelle(it) }.ifEmpty { "—" }

    private fun libelle(declaration: DeclarationSensible): String = when (declaration) {
        DeclarationSensible.ENTREE_TV -> "entrée TV"
        DeclarationSensible.ACCESSIBILITE -> "accessibilité"
        DeclarationSensible.CLAVIER -> "clavier"
        DeclarationSensible.DEMARRAGE -> "démarrage"
    }

    private fun taille(octets: Long): String = when {
        octets <= 0 -> "0 Mo"
        octets < MO -> "< 1 Mo"
        else -> "${octets / MO} Mo"
    }

    private fun titre(origine: OriginePaquet): String = when (origine) {
        OriginePaquet.CONSTRUCTEUR -> "Constructeur"
        OriginePaquet.ANDROID -> "Android"
        OriginePaquet.AUTRE -> "Autres"
    }

    private const val KO = 1024L
    private const val MO = 1024L * 1024

    private val LEGENDE = """
        |## Comment lire
        |
        |- **Emplacement** : d'où vient l'application. Un `priv-app` lui vaut des permissions privilégiées ; « mise à jour » signale une version plus récente installée par-dessus celle d'usine.
        |- **Droits** : `système` quand elle tourne sous l'identité du système (UID 1000) — prudence, ce qu'elle fait, le système le fait ; `plateforme` pour un autre identifiant réservé (téléphonie, Bluetooth, NFC…) ; `appli` sinon.
        |- **Déclare** : ce qui rend sa désactivation risquée — `entrée TV` (tuner, HDMI, chaînes), `accessibilité`, `clavier`, `démarrage` (se lance avec l'appareil).
        |- **Icône** : l'application a une entrée dans le menu des applications.
        |- **RAM** : mémoire réellement occupée (PSS) par les processus à son nom au moment du relevé ; `—` quand aucun ne tourne.
        |- **Stockage** : application, données et cache sur le stockage interne, selon la dernière estimation d'Android.
    """.trimMargin()
}
