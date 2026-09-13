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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.baseline_arrow_drop_down_24
import net.jolabs40.tvslim.windows.ressources.baseline_search_24
import net.jolabs40.tvslim.windows.ressources.filter_all
import net.jolabs40.tvslim.windows.ressources.filter_disabled
import net.jolabs40.tvslim.windows.ressources.filter_enabled
import net.jolabs40.tvslim.windows.ressources.packages_apply
import net.jolabs40.tvslim.windows.ressources.packages_clear
import net.jolabs40.tvslim.windows.ressources.packages_none_matching
import net.jolabs40.tvslim.windows.ressources.packages_not_connected
import net.jolabs40.tvslim.windows.ressources.packages_profiles
import net.jolabs40.tvslim.windows.ressources.packages_progress
import net.jolabs40.tvslim.windows.ressources.packages_reactivate
import net.jolabs40.tvslim.windows.ressources.packages_search
import net.jolabs40.tvslim.windows.ressources.side_effect_prefix
import net.jolabs40.tvslim.windows.ressources.size_mb
import net.jolabs40.tvslim.windows.ui.EtatApp
import net.jolabs40.tvslim.windows.ui.Filtre
import net.jolabs40.tvslim.windows.ui.LignePaquet
import net.jolabs40.tvslim.windows.ui.composants.EcranVide
import net.jolabs40.tvslim.windows.ui.composants.PastilleRisque
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * L'onglet Paquets : le catalogue filtré sur ce que le téléviseur porte réellement, les profils,
 * l'application en lot. Mêmes informations que sur le téléphone ; le bureau y ajoute un volet de
 * détail, où chaque paquet se lit en entier sans rien cocher par mégarde.
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

        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
                TextButton(onClick = onToutDecocher) { Text(stringResource(Res.string.packages_clear)) }
                Button(onClick = onAppliquer, enabled = !etat.travailEnCours) {
                    Text(stringResource(Res.string.packages_apply, etat.selection.size))
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = etat.filtre == Filtre.TOUS,
                    onClick = { onFiltre(Filtre.TOUS) },
                    label = { Text(stringResource(Res.string.filter_all)) },
                )
                FilterChip(
                    selected = etat.filtre == Filtre.ACTIFS,
                    onClick = { onFiltre(Filtre.ACTIFS) },
                    label = { Text(stringResource(Res.string.filter_enabled, etat.nombreActifs)) },
                )
                FilterChip(
                    selected = etat.filtre == Filtre.DESACTIVES,
                    onClick = { onFiltre(Filtre.DESACTIVES) },
                    label = { Text(stringResource(Res.string.filter_disabled, etat.nombreDesactives)) },
                )
                Spacer(Modifier.weight(1f))
                // Une liste déroulante plutôt qu'une rangée de boutons : cinq profils aux noms longs
                // passaient sur deux lignes, et chacun peut désormais dire ce qu'il coche.
                ListeProfils(
                    profils = etat.catalogue.profils,
                    selectionVide = etat.selection.isEmpty(),
                    onProfil = onProfil,
                )
            }
        }

        HorizontalDivider()

        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1.35f).fillMaxHeight()) {
                if (affichees.isEmpty()) {
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

/**
 * Les profils, en liste déroulante. En choisir un coche tout ce qu'il couvre, sans rien décocher ;
 * sa description, sous son nom, dit ce qu'on perd en l'appliquant. Le champ rappelle le dernier
 * profil appliqué, tant que la sélection n'a pas été vidée.
 */
@Composable
private fun ListeProfils(profils: List<Profil>, selectionVide: Boolean, onProfil: (Profil) -> Unit) {
    var ouverte by remember { mutableStateOf(false) }
    var dernier by remember { mutableStateOf<Profil?>(null) }

    Box(modifier = Modifier.width(360.dp)) {
        OutlinedTextField(
            value = if (selectionVide) "" else dernier?.nom.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(Res.string.packages_profiles)) },
            trailingIcon = {
                Icon(painter = painterResource(Res.drawable.baseline_arrow_drop_down_24), contentDescription = null)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // Un champ en lecture seule garde le clic pour lui : une surface transparente le reçoit.
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(top = 8.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable { ouverte = true },
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
 * Une ligne du catalogue. Un clic l'ouvre dans le volet de détail ; la case, elle, coche. Un paquet
 * déjà désactivé ne se coche pas : il se réactive, d'un bouton explicite.
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
                PastilleRisque(ligne.entree.risque)
                Text(
                    text = ligne.entree.nom,
                    modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
