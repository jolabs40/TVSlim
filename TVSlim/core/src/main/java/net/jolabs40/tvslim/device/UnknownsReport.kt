package net.jolabs40.tvslim.device

import net.jolabs40.tvslim.catalog.PackageEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What is read from the TV when exporting the inventory. Any part may be missing (Android too old, a failed
 * read) without blocking the inventory; its cells then show a dash.
 */
data class UnknownsSurvey(
    val hints: Map<String, PackageHints> = emptyMap(),
    val memory: MemoryBreakdown = MemoryBreakdown(),
    val storage: StorageBreakdown = StorageBreakdown(),
    val firmware: Firmware = Firmware(),
)

/**
 * Markdown inventory of unknown packages, to attach to an issue for extending the catalogue: what ADB reports
 * for each (origin, UID, declarations, memory, storage), then the catalogue entries already on the device.
 * Nothing personal: device, firmware, package names.
 */
object UnknownsReport {

    fun suggestedName(info: DeviceInfo, day: LocalDate = LocalDate.now()): String =
        "TVSlim-inconnus-${info.fileSafeName}-$day.md"

    fun markdown(
        info: DeviceInfo,
        unknowns: List<UnknownPackage>,
        application: String,
        survey: UnknownsSurvey = UnknownsSurvey(),
        /** Each catalogue entry and its state on the device; absent ones are skipped. */
        fromCatalog: Map<PackageEntry, PackageState> = emptyMap(),
        timestamp: Long = System.currentTimeMillis(),
    ): String = buildString {
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
        val byOrigin = unknowns.groupBy { it.origin }
        val hints = unknowns.mapNotNull { survey.hints[it.packageName] }
        val memory = survey.memory.kilobytesPerPackage
        val storage = survey.storage.applications.associate { it.packageName to it.totalBytes }
        val present = fromCatalog.filterValues { it != PackageState.ABSENT }.toList().sortedBy { it.first.packageName }

        appendLine("# Paquets inconnus du catalogue TV Slim")
        appendLine()
        appendLine("- Appareil : ${info.displayName.ifBlank { "inconnu" }}")
        appendLine("- Fabricant déclaré : ${info.brand.ifBlank { "—" }} ; marque : ${info.retailBrand.ifBlank { "—" }}")
        appendLine("- Android : ${info.androidVersion.ifBlank { "—" }} (${info.build.ifBlank { "—" }})")
        if (survey.firmware.populated) appendLine("- Firmware : ${firmware(survey.firmware)}")
        appendLine("- Relevé le : $date, avec $application")
        appendLine(
            "- Paquets inconnus : ${unknowns.size} — constructeur ${byOrigin[PackageOrigin.MAKER].orEmpty().size}, " +
                "Android ${byOrigin[PackageOrigin.ANDROID].orEmpty().size}, " +
                "autres ${byOrigin[PackageOrigin.OTHER].orEmpty().size}",
        )
        if (hints.isNotEmpty()) {
            appendLine(
                "- Avec les droits du système : ${hints.count { it.hasSystemPrivileges }} ; " +
                    "avec une déclaration sensible : ${hints.count { it.declarations.isNotEmpty() }}",
            )
        }
        if (present.isNotEmpty()) {
            appendLine(
                "- Déjà au catalogue : ${present.size} présents — actifs ${present.count { it.second == PackageState.ACTIVE }}, " +
                    "désactivés ${present.count { it.second == PackageState.DISABLED }}",
            )
        }
        appendLine("- Lu sur l'appareil : ${reads(survey)}")
        appendLine()
        appendLine(LEGEND)

        PackageOrigin.ORDER.forEach { origin ->
            val packages = byOrigin[origin].orEmpty()
            if (packages.isEmpty()) return@forEach
            appendLine()
            appendLine("## ${title(origin)} (${packages.size})")
            packages.groupBy { it.family }.forEach { (family, members) ->
                appendLine()
                appendLine("### $family (${members.size})")
                appendLine()
                appendLine("| Paquet | État | Emplacement | Droits | Déclare | Icône | RAM | Stockage |")
                appendLine("|---|---|---|---|---|---|---|---|")
                members.forEach { unknown ->
                    val hint = survey.hints[unknown.packageName]
                    val cells = listOf(
                        "`${unknown.packageName}`",
                        state(unknown.state),
                        hint?.let(::location) ?: "—",
                        hint?.let(::privileges) ?: "—",
                        hint?.let(::declarations) ?: "—",
                        hint?.let { if (it.icon) "oui" else "non" } ?: "—",
                        memory[unknown.packageName]?.let { size(it * KB) } ?: "—",
                        storage[unknown.packageName]?.let(::size) ?: "—",
                    )
                    appendLine(cells.joinToString(" | ", prefix = "| ", postfix = " |"))
                }
            }
        }

        if (present.isNotEmpty()) {
            appendLine()
            appendLine("## Déjà au catalogue (${present.size})")
            appendLine()
            appendLine(CATALOG_INTRO)
            appendLine()
            appendLine("| Paquet | Marque au catalogue | État |")
            appendLine("|---|---|---|")
            present.forEach { (entry, state) ->
                appendLine("| `${entry.packageName}` | ${entry.brand.ifBlank { "—" }} | ${state(state)} |")
            }
        }
    }

    /** What could be read and what could not: a dash in a cell means something different in each case. */
    private fun reads(survey: UnknownsSurvey): String {
        val parts = listOf(
            "indices ADB" to survey.hints.isNotEmpty(),
            "mémoire vive" to survey.memory.populated,
            "stockage" to survey.storage.applications.isNotEmpty(),
            "firmware" to survey.firmware.populated,
        )
        val fetched = parts.filter { it.second }.joinToString(", ") { it.first }
        val missed = parts.filterNot { it.second }.joinToString(", ") { it.first }
        return fetched.ifEmpty { "la seule liste des paquets" } + if (missed.isEmpty()) "" else " ; illisible : $missed"
    }

    /** Product, factory language and fingerprint, only those that could be read. */
    private fun firmware(firmware: Firmware): String = listOfNotNull(
        firmware.product.takeIf { it.isNotEmpty() }?.let { "produit `$it`" },
        firmware.factoryLanguage.takeIf { it.isNotEmpty() }?.let { "langue d'usine $it" },
        firmware.fingerprint.takeIf { it.isNotEmpty() }?.let { "empreinte `$it`" },
    ).joinToString(", ")

    private fun state(state: PackageState): String = if (state == PackageState.DISABLED) "désactivé" else "actif"

    private fun location(hint: PackageHints): String =
        hint.location.ifBlank { "—" } + if (hint.updated) ", mise à jour" else ""

    private fun privileges(hint: PackageHints): String = when {
        hint.uid == null -> "—"
        hint.hasSystemPrivileges -> "système"
        hint.reservedUid -> "plateforme (${hint.uid})"
        else -> "appli"
    }

    private fun declarations(hint: PackageHints): String =
        hint.declarations.sorted().joinToString(", ") { label(it) }.ifEmpty { "—" }

    private fun label(declaration: SensitiveDeclaration): String = when (declaration) {
        SensitiveDeclaration.TV_INPUT -> "entrée TV"
        SensitiveDeclaration.ACCESSIBILITY -> "accessibilité"
        SensitiveDeclaration.KEYBOARD -> "clavier"
        SensitiveDeclaration.BOOT -> "démarrage"
    }

    private fun size(bytes: Long): String = when {
        bytes <= 0 -> "0 Mo"
        bytes < MB -> "< 1 Mo"
        else -> "${bytes / MB} Mo"
    }

    private fun title(origin: PackageOrigin): String = when (origin) {
        PackageOrigin.MAKER -> "Constructeur"
        PackageOrigin.ANDROID -> "Android"
        PackageOrigin.OTHER -> "Autres"
    }

    private const val KB = 1024L
    private const val MB = 1024L * 1024

    private val LEGEND = """
        |## Comment lire
        |
        |- **Emplacement** : d'où vient l'application. Un `priv-app` lui vaut des permissions privilégiées ; « mise à jour » signale une version plus récente installée par-dessus celle d'usine.
        |- **Droits** : `système` quand elle tourne sous l'identité du système (UID 1000) — prudence, ce qu'elle fait, le système le fait ; `plateforme` pour un autre identifiant réservé (téléphonie, Bluetooth, NFC…) ; `appli` sinon.
        |- **Déclare** : ce qui rend sa désactivation risquée — `entrée TV` (tuner, HDMI, chaînes), `accessibilité`, `clavier`, `démarrage` (se lance avec l'appareil).
        |- **Icône** : l'application a une entrée dans le menu des applications.
        |- **RAM** : mémoire réellement occupée (PSS) par les processus à son nom au moment du relevé ; `—` quand aucun ne tourne.
        |- **Stockage** : application, données et cache sur le stockage interne, selon la dernière estimation d'Android.
    """.trimMargin()

    private const val CATALOG_INTRO =
        "Les entrées du catalogue que porte cet appareil, avec la marque que le catalogue leur donne : ce qui, " +
            "décrit sur un autre appareil, se retrouve ici. Un paquet désactivé l'a été sur cet appareil — par " +
            "la personne, ou dès l'usine."
}
