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

data class AppareilDecouvert(
    val nom: String,
    val hote: String,
    val port: Int,
    /** User-given device name, when one can be found. */
    val nomConvivial: String? = null,
    /**
     * Android 11+ wireless debugging, which uses TLS that the embedded ADB client does not support. Still listed
     * so the UI can say what to enable instead.
     */
    val sansFil: Boolean = false,
) {
    val libelle: String get() = nomConvivial ?: hote
}

/** Devices found so far, and whether a first full scan has completed. */
data class ResultatDecouverte(
    val appareils: List<AppareilDecouvert> = emptyList(),
    val premierTourTermine: Boolean = false,
)

/**
 * Finds TVs reachable over ADB.
 *
 * Two sources, merged by address:
 *  - mDNS, as on the phone: `_adb._tcp` (network debugging), `_adb-tls-connect._tcp` (wireless debugging) and
 *    `_googlecast._tcp`, which carries the user-given name (e.g. "Living room"), matched to the others by IP;
 *  - a scan of port 5555 ([BalayageReseau]), since many TVs announce nothing.
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
            // Closing JmDNS sends goodbye messages and takes 1 to 2 s; this runs on Dispatchers.IO (flowOn), not
            // on the UI thread.
            instances.forEach { runCatching { it.close() } }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val TAG = "Decouverte"

        /** Classic network debugging, as on TVs listening on port 5555. */
        const val TYPE_ADB = "_adb._tcp.local."

        /** Android 11+ wireless debugging, once the device is paired. */
        const val TYPE_ADB_TLS = "_adb-tls-connect._tcp.local."

        /** Built-in Chromecast, which carries the user-given device name. */
        const val TYPE_CAST = "_googlecast._tcp.local."

        val TYPES = listOf(TYPE_ADB, TYPE_ADB_TLS, TYPE_CAST)

        /** Rescan period, so a TV switched on later eventually shows up. */
        const val INTERVALLE_BALAYAGE_MS = 20_000L

        /** `fn` (friendly name) from the cast TXT record, else `md` (model). */
        fun nomConvivial(info: ServiceInfo): String? =
            listOf("fn", "md")
                .firstNotNullOfOrNull { cle -> info.getPropertyString(cle) }
                ?.takeIf { it.isNotBlank() }
    }
}
