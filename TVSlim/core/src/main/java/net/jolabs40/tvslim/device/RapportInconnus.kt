package net.jolabs40.tvslim.device

import net.jolabs40.tvslim.catalog.EntreePaquet
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
    val firmware: Firmware = Firmware(),
)

/**
 * L'inventaire des paquets inconnus, en Markdown : de quoi compléter le catalogue, joint à un message ou
 * à une issue. Chaque paquet y porte ce qu'ADB en dit — d'où il vient, sous quelle identité il tourne, ce
 * qu'il déclare au système, ce qu'il occupe —, de quoi juger s'il est prudent d'y toucher avant de le
 * décrire. Suivent les entrées du catalogue que l'appareil porte déjà : ce qui, décrit ailleurs, vaut aussi
 * chez ce constructeur. Rien de personnel n'y figure : l'appareil, son firmware, des noms de paquets.
 */
object RapportInconnus {

    fun nomPropose(infos: InfosAppareil, jour: LocalDate = LocalDate.now()): String =
        "TVSlim-inconnus-${infos.nomPourFichier}-$jour.md"

    fun markdown(
        infos: InfosAppareil,
        inconnus: List<PaquetInconnu>,
        application: String,
        releve: ReleveInconnus = ReleveInconnus(),
        /** Chaque entrée du catalogue et son état sur l'appareil ; les absentes sont écartées. */
        duCatalogue: Map<EntreePaquet, EtatPaquet> = emptyMap(),
        horodatage: Long = System.currentTimeMillis(),
    ): String = buildString {
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .format(Instant.ofEpochMilli(horodatage).atZone(ZoneId.systemDefault()))
        val parOrigine = inconnus.groupBy { it.origine }
        val indices = inconnus.mapNotNull { releve.indices[it.paquet] }
        val memoire = releve.memoire.kilooctetsParPaquet
        val stockage = releve.stockage.applications.associate { it.paquet to it.totalOctets }
        val presents = duCatalogue.filterValues { it != EtatPaquet.ABSENT }.toList().sortedBy { it.first.paquet }

        appendLine("# Paquets inconnus du catalogue TV Slim")
        appendLine()
        appendLine("- Appareil : ${infos.nomAffiche.ifBlank { "inconnu" }}")
        appendLine("- Fabricant déclaré : ${infos.marque.ifBlank { "—" }} ; marque : ${infos.marqueCommerciale.ifBlank { "—" }}")
        appendLine("- Android : ${infos.versionAndroid.ifBlank { "—" }} (${infos.build.ifBlank { "—" }})")
        if (releve.firmware.renseigne) appendLine("- Firmware : ${firmware(releve.firmware)}")
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
        if (presents.isNotEmpty()) {
            appendLine(
                "- Déjà au catalogue : ${presents.size} présents — actifs ${presents.count { it.second == EtatPaquet.ACTIF }}, " +
                    "désactivés ${presents.count { it.second == EtatPaquet.DESACTIVE }}",
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
                        etat(inconnu.etat),
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

        if (presents.isNotEmpty()) {
            appendLine()
            appendLine("## Déjà au catalogue (${presents.size})")
            appendLine()
            appendLine(INTRO_CATALOGUE)
            appendLine()
            appendLine("| Paquet | Marque au catalogue | État |")
            appendLine("|---|---|---|")
            presents.forEach { (entree, etat) ->
                appendLine("| `${entree.paquet}` | ${entree.marque.ifBlank { "—" }} | ${etat(etat)} |")
            }
        }
    }

    /** Ce qui a pu être lu, et ce qui ne l'a pas été : une case « — » ne dit pas la même chose dans les deux cas. */
    private fun lectures(releve: ReleveInconnus): String {
        val parties = listOf(
            "indices ADB" to releve.indices.isNotEmpty(),
            "mémoire vive" to releve.memoire.renseignee,
            "stockage" to releve.stockage.applications.isNotEmpty(),
            "firmware" to releve.firmware.renseigne,
        )
        val lues = parties.filter { it.second }.joinToString(", ") { it.first }
        val manquees = parties.filterNot { it.second }.joinToString(", ") { it.first }
        return lues.ifEmpty { "la seule liste des paquets" } + if (manquees.isEmpty()) "" else " ; illisible : $manquees"
    }

    /** « produit `G08_4K_GB`, langue d'usine en-GB, empreinte `TCL/…` » : seulement ce qui a pu être lu. */
    private fun firmware(firmware: Firmware): String = listOfNotNull(
        firmware.produit.takeIf { it.isNotEmpty() }?.let { "produit `$it`" },
        firmware.langueUsine.takeIf { it.isNotEmpty() }?.let { "langue d'usine $it" },
        firmware.empreinte.takeIf { it.isNotEmpty() }?.let { "empreinte `$it`" },
    ).joinToString(", ")

    private fun etat(etat: EtatPaquet): String = if (etat == EtatPaquet.DESACTIVE) "désactivé" else "actif"

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

    private const val INTRO_CATALOGUE =
        "Les entrées du catalogue que porte cet appareil, avec la marque que le catalogue leur donne : ce qui, " +
            "décrit sur un autre appareil, se retrouve ici. Un paquet désactivé l'a été sur cet appareil — par " +
            "la personne, ou dès l'usine."
}
