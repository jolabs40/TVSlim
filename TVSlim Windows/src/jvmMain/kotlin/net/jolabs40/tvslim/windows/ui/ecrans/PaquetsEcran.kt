package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.catalog.Profil
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.PropositionCatalogue
import net.jolabs40.tvslim.device.origine
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.baseline_arrow_drop_down_24
import net.jolabs40.tvslim.windows.ressources.baseline_search_24
import net.jolabs40.tvslim.windows.ressources.baseline_warning_24
import net.jolabs40.tvslim.windows.ressources.config_reinject
import net.jolabs40.tvslim.windows.ressources.config_save
import net.jolabs40.tvslim.windows.ressources.filter_all
import net.jolabs40.tvslim.windows.ressources.filter_disabled
import net.jolabs40.tvslim.windows.ressources.filter_enabled
import net.jolabs40.tvslim.windows.ressources.packages_apply
import net.jolabs40.tvslim.windows.ressources.packages_clear
import net.jolabs40.tvslim.windows.ressources.packages_none_matching
import net.jolabs40.tvslim.windows.ressources.packages_not_connected
import net.jolabs40.tvslim.windows.ressources.packages_not_tv
import net.jolabs40.tvslim.windows.ressources.packages_profiles
import net.jolabs40.tvslim.windows.ressources.packages_progress
import net.jolabs40.tvslim.windows.ressources.packages_reactivate
import net.jolabs40.tvslim.windows.ressources.packages_search
import net.jolabs40.tvslim.windows.ressources.packages_untested
import net.jolabs40.tvslim.windows.ressources.side_effect_prefix
import net.jolabs40.tvslim.windows.ressources.size_mb
import net.jolabs40.tvslim.windows.ui.EtatApp
import net.jolabs40.tvslim.windows.ui.Filtre
import net.jolabs40.tvslim.windows.ui.LignePaquet
import net.jolabs40.tvslim.windows.ui.composants.EcranVide
import net.jolabs40.tvslim.windows.ui.composants.IconeOrigine
import net.jolabs40.tvslim.windows.ui.composants.PastilleRisque
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Packages tab: the catalogue filtered to what the TV actually has, profiles, batch apply. Same information
 * as on the phone, plus a detail pane where a package can be read in full without ticking it by mistake.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaquetsEcran(
    etat: EtatApp,
    onBasculer: (String) -> Unit,
    onDetailler: (String) -> Unit,
    onProfil: (Profil) -> Unit,
    onToutDecocher: () -> Unit,
    onAppliquer: () -> Unit,
    onReactiver: (String) -> Unit,
    onRecherche: (String) -> Unit,
    onFiltre: (Filtre) -> Unit,
    onSauvegarder: () -> Unit,
    onReinjecter: () -> Unit,
    onExporterInconnus: () -> Unit,
    onProposerInconnus: () -> Unit,
) {
    if (!etat.connecte) {
        EcranVide(stringResource(Res.string.packages_not_connected))
        return
    }

    val affichees = etat.affichees

    Column(modifier = Modifier.fillMaxSize()) {
        etat.progression?.let { progression ->
            LinearProgressIndicator(
                progress = { if (progression.total == 0) 0f else progression.fait.toFloat() / progression.total },
                modifier = Modifier.fillMaxWidth(),
            )
            TexteSecondaire(
                stringResource(Res.string.packages_progress, progression.fait, progression.total),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                petit = true,
            )
        }

        // Phone or tablet: the catalogue is not written for them and no profile applies.
        val pourLeCatalogue = etat.infos.typeAppareil.pourLeCatalogue
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!pourLeCatalogue) BandeauHorsTeleviseur()
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = etat.recherche,
                    onValueChange = onRecherche,
                    placeholder = { Text(stringResource(Res.string.packages_search)) },
                    leadingIcon = { Icon(painterResource(Res.drawable.baseline_search_24), contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).widthIn(max = 480.dp),
                )
                // Save and reapply the TV configuration (launcher and packages): a save is a self-made
                // profile. The search field shrinks to make room here; the filter row has no room left.
                OutlinedButton(onClick = onSauvegarder, enabled = !etat.travailEnCours) {
                    Text(stringResource(Res.string.config_save))
                }
                OutlinedButton(onClick = onReinjecter, enabled = !etat.travailEnCours) {
                    Text(stringResource(Res.string.config_reinject))
                }
                TextButton(onClick = onToutDecocher) { Text(stringResource(Res.string.packages_clear)) }
                Button(onClick = onAppliquer, enabled = !etat.travailEnCours) {
                    Text(stringResource(Res.string.packages_apply, etat.selection.size))
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FiltreEtat(
                    filtre = etat.filtre,
                    actifs = etat.nombreActifs,
                    desactives = etat.nombreDesactives,
                    onFiltre = onFiltre,
                )
                Spacer(Modifier.weight(1f))
                // A dropdown rather than a row of buttons: five long profile names wrapped onto two
                // lines, and the dropdown has room to describe what each one selects.
                ListeProfils(
                    profils = etat.catalogue.profils,
                    selectionVide = etat.selection.isEmpty(),
                    actif = pourLeCatalogue,
                    onProfil = onProfil,
                )
            }
        }

        HorizontalDivider()

        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1.35f).fillMaxHeight()) {
                val inconnus = etat.inconnusAffiches
                if (affichees.isEmpty() && inconnus.isEmpty()) {
                    TexteSecondaire(
                        stringResource(Res.string.packages_none_matching),
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    val liste = rememberLazyListState()
                    LazyColumn(state = liste, modifier = Modifier.fillMaxSize()) {
                        items(affichees, key = { it.entree.paquet }) { ligne ->
                            VuePaquet(
                                ligne = ligne,
                                choisie = ligne.entree.paquet == etat.paquetDetaille,
                                onDetailler = { onDetailler(ligne.entree.paquet) },
                                onBasculer = { onBasculer(ligne.entree.paquet) },
                                onReactiver = { onReactiver(ligne.entree.paquet) },
                            )
                        }
                        // Then the packages the catalogue does not know: shown, never offered for disabling.
                        if (etat.inconnus.isNotEmpty()) {
                            sectionInconnus(
                                affiches = inconnus,
                                total = etat.inconnus.size,
                                onExporter = onExporterInconnus,
                                onProposer = onProposerInconnus.takeIf { PropositionCatalogue.aProposer(etat.inconnus) },
                            )
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(liste),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
            VerticalDivider()
            DetailPaquet(
                ligne = etat.ligneDetaillee,
                catalogue = etat.catalogue,
                onBasculer = onBasculer,
                onReactiver = onReactiver,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/** All, enabled or disabled: one three-way toggle rather than three buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiltreEtat(filtre: Filtre, actifs: Int, desactives: Int, onFiltre: (Filtre) -> Unit) {
    // Fixed width and no check mark: otherwise the row shrinks to its labels' minimum width and a label
    // like "Disabled (55)" loses its count.
    SingleChoiceSegmentedButtonRow(modifier = Modifier.width(456.dp)) {
        Filtre.entries.forEachIndexed { rang, choix ->
            SegmentedButton(
                selected = filtre == choix,
                onClick = { onFiltre(choix) },
                shape = SegmentedButtonDefaults.itemShape(index = rang, count = Filtre.entries.size),
                icon = {},
            ) {
                Text(
                    text = when (choix) {
                        Filtre.TOUS -> stringResource(Res.string.filter_all)
                        Filtre.ACTIFS -> stringResource(Res.string.filter_enabled, actifs)
                        Filtre.DESACTIVES -> stringResource(Res.string.filter_disabled, desactives)
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** Shown for a phone or tablet: what the catalogue will not do for it, and what remains possible. */
@Composable
private fun BandeauHorsTeleviseur() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(Res.drawable.baseline_warning_24), contentDescription = null)
            Text(stringResource(Res.string.packages_not_tv), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Profile dropdown. Picking one selects everything it covers without unselecting anything; the description
 * under each name says what applying it costs. The field shows the last applied profile until the selection
 * is cleared.
 */
@Composable
private fun ListeProfils(profils: List<Profil>, selectionVide: Boolean, actif: Boolean, onProfil: (Profil) -> Unit) {
    var ouverte by remember { mutableStateOf(false) }
    var dernier by remember { mutableStateOf<Profil?>(null) }

    Box(modifier = Modifier.width(300.dp)) {
        OutlinedTextField(
            value = if (selectionVide) "" else dernier?.nom.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(Res.string.packages_profiles)) },
            enabled = actif,
            trailingIcon = {
                Icon(painter = painterResource(Res.drawable.baseline_arrow_drop_down_24), contentDescription = null)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // A read-only text field swallows clicks, so a transparent overlay receives them.
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(top = 8.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable(enabled = actif) { ouverte = true },
        )
        DropdownMenu(
            expanded = ouverte,
            onDismissRequest = { ouverte = false },
            modifier = Modifier.width(460.dp),
        ) {
            profils.forEach { profil ->
                DropdownMenuItem(
                    text = {
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(
                                text = profil.nom,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = profil.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        ouverte = false
                        dernier = profil
                        onProfil(profil)
                    },
                )
            }
        }
    }
}

/**
 * A catalogue row. A click opens it in the detail pane; only the checkbox selects. An already disabled
 * package cannot be selected: it is re-enabled with an explicit button.
 */
@Composable
private fun VuePaquet(
    ligne: LignePaquet,
    choisie: Boolean,
    onDetailler: () -> Unit,
    onBasculer: () -> Unit,
    onReactiver: () -> Unit,
) {
    val fond = if (choisie) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(fond)
            .clickable(onClick = onDetailler)
            .padding(end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(120.dp), contentAlignment = Alignment.Center) {
            if (ligne.etat == EtatPaquet.DESACTIVE) {
                TextButton(onClick = onReactiver) { Text(stringResource(Res.string.packages_reactivate)) }
            } else {
                Checkbox(checked = ligne.selectionne, onCheckedChange = { onBasculer() })
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconeOrigine(ligne.entree.origine, modifier = Modifier.padding(end = 8.dp))
                PastilleRisque(ligne.entree.risque)
                Text(
                    text = ligne.entree.nom,
                    modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Described from a submitted inventory: no profile selects it, and the detail pane says why.
                if (!ligne.entree.eprouve) {
                    Text(
                        text = stringResource(Res.string.packages_untested),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                ligne.entree.tailleMo?.let { taille ->
                    TexteSecondaire(
                        stringResource(Res.string.size_mb, taille),
                        modifier = Modifier.padding(start = 8.dp),
                        petit = true,
                    )
                }
            }
            Text(
                text = ligne.entree.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ligne.entree.effetDeBord?.let { effet ->
                Text(
                    text = stringResource(Res.string.side_effect_prefix, effet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
