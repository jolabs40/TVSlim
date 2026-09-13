package net.jolabs40.tvslim.windows.reseau

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.windows.adb.PORT_ADB_PAR_DEFAUT
import net.jolabs40.tvslim.windows.outils.Traces
import net.jolabs40.tvslim.windows.outils.detail
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** Un téléviseur repéré sur le réseau local. */
data class AppareilDecouvert(
    val nom: String,
    val hote: String,
    val port: Int,
    /** Le nom que la personne a donné à l'appareil, s'il se laisse trouver. */
    val nomConvivial: String? = null,
    /**
     * Débogage sans fil d'Android 11+ : la session y est chiffrée en TLS, ce que le client ADB
     * embarqué ne sait pas faire. On le montre quand même, pour pouvoir dire quoi activer à la place.
     */
    val sansFil: Boolean = false,
) {
    /** Ce qu'on affiche : le nom donné par la personne, sinon l'adresse. */
    val libelle: String get() = nomConvivial ?: hote
}

/** Ce que la recherche a trouvé jusqu'ici, et si un premier tour complet a eu lieu. */
data class ResultatDecouverte(
    val appareils: List<AppareilDecouvert> = emptyList(),
    val premierTourTermine: Boolean = false,
)

/**
 * Trouve les téléviseurs joignables en ADB, sans rien demander à la personne.
 *
 * Deux sources, fusionnées par adresse :
 *  - **mDNS**, comme le téléphone : `_adb._tcp` pour le débogage réseau, `_adb-tls-connect._tcp`
 *    pour le débogage sans fil, et `_googlecast._tcp`, qui porte le nom donné à l'appareil
 *    (« Salon ») — rapproché des deux autres par l'adresse IP ;
 *  - un **balayage** du port 5555 ([BalayageReseau]) : beaucoup de téléviseurs n'annoncent rien,
 *    et c'est justement le cas où un débutant ne saurait pas où lire l'adresse.
 */
class DecouverteTv(
    private val balayage: BalayageReseau = BalayageReseau(),
    private val interfaces: () -> List<InterfaceLocale> = InterfacesReseau::actives,
) {

    fun flux(): Flow<ResultatDecouverte> = channelFlow {
        val annonces = ConcurrentHashMap<String, AppareilDecouvert>()
        val nomsParHote = ConcurrentHashMap<String, String>()
        val balayes = AtomicReference<Set<String>>(emptySet())
        val tourTermine = AtomicBoolean(false)

        fun publier() {
            val parAnnonce = annonces.values.toList()
            val dejaVus = parAnnonce.map { "${it.hote}:${it.port}" }.toSet()
            val parBalayage = balayes.get()
                .filterNot { "$it:$PORT_ADB_PAR_DEFAUT" in dejaVus }
                .map { AppareilDecouvert(nom = it, hote = it, port = PORT_ADB_PAR_DEFAUT) }
            val appareils = (parAnnonce + parBalayage)
                .map { it.copy(nomConvivial = it.nomConvivial ?: nomsParHote[it.hote]) }
                .sortedWith(compareBy<AppareilDecouvert>({ it.sansFil }, { it.libelle }))
            trySend(ResultatDecouverte(appareils, tourTermine.get()))
        }

        val ecouteur = object : ServiceListener {
            override fun serviceAdded(evenement: ServiceEvent) {
                evenement.dns.requestServiceInfo(evenement.type, evenement.name)
            }

            override fun serviceRemoved(evenement: ServiceEvent) {
                if (annonces.values.removeIf { it.nom == evenement.name }) publier()
            }

            override fun serviceResolved(evenement: ServiceEvent) {
                val info = evenement.info ?: return
                val adresse = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                if (evenement.type.startsWith(TYPE_CAST)) {
                    nomConvivial(info)?.let { nomsParHote[adresse] = it }
                } else {
                    annonces["$adresse:${info.port}"] = AppareilDecouvert(
                        nom = info.name,
                        hote = adresse,
                        port = info.port,
                        sansFil = evenement.type.startsWith(TYPE_ADB_TLS),
                    )
                }
                publier()
            }
        }

        val locales = interfaces()
        val instances = locales.mapNotNull { carte ->
            runCatching {
                JmDNS.create(carte.adresse, "tvslim").also { dns ->
                    TYPES.forEach { type -> dns.addServiceListener(type, ecouteur) }
                }
            }.onFailure {
                Traces.avertir(TAG, "mDNS indisponible" + detail(carte.nom), it)
            }.getOrNull()
        }

        launch {
            while (isActive) {
                balayes.set(balayage.balayer(locales))
                tourTermine.set(true)
                publier()
                delay(INTERVALLE_BALAYAGE_MS)
            }
        }

        awaitClose {
            // Fermer JmDNS envoie ses messages d'adieu et prend une à deux secondes : ce bloc
            // tourne sur Dispatchers.IO (voir flowOn), l'interface n'attend pas.
            instances.forEach { runCatching { it.close() } }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val TAG = "Decouverte"

        /** Débogage réseau classique, celui des téléviseurs sur le port 5555. */
        const val TYPE_ADB = "_adb._tcp.local."

        /** Débogage sans fil d'Android 11+, une fois l'appareil appairé. */
        const val TYPE_ADB_TLS = "_adb-tls-connect._tcp.local."

        /** Chromecast intégré : c'est lui qui porte le nom donné à l'appareil. */
        const val TYPE_CAST = "_googlecast._tcp.local."

        val TYPES = listOf(TYPE_ADB, TYPE_ADB_TLS, TYPE_CAST)

        /** Le tour de balayage suivant : un téléviseur qu'on vient d'allumer finit par apparaître. */
        const val INTERVALLE_BALAYAGE_MS = 20_000L

        /** `fn` (friendly name) dans les attributs du service cast, `md` à défaut (le modèle). */
        fun nomConvivial(info: ServiceInfo): String? =
            listOf("fn", "md")
                .firstNotNullOfOrNull { cle -> info.getPropertyString(cle) }
                ?.takeIf { it.isNotBlank() }
    }
}
