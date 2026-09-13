package net.jolabs40.tvslim.device

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.ResultatShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les indices qu'ADB donne sur chaque paquet. La sortie suit le format relevé sur la TCL sous Android 14
 * le 2026-09-13 — chemins d'usine et mis à jour, un identifiant par utilisateur, sorties `--brief` des
 * requêtes d'intention —, recomposée autour de quelques paquets.
 */
class IndicesPaquetsTest {

    private val sortieTcl = """
        @@TVSLIM_FICHIERS
        package:/product/overlay/MtkMdnsOffloadServiceOverlay.apk=com.mediatek.android.tv.mdns.offload.overlay uid:10118,1010118
        package:/apex/com.android.tethering/priv-app/ServiceConnectivityResources@UTT2.250416.001/ServiceConnectivityResources.apk=com.android.connectivity.resources uid:10102
        package:/data/app/~~BFrwO2y2GoKrjngWosmqgQ==/flar2.homebutton-zbojoxdPjFpGbLxfTzjbVA==/base.apk=flar2.homebutton uid:10125
        package:/system_ext/app/TGuard/TGuard.apk=com.tcl.guard uid:1000
        package:/data/app/~~Z7uCSaOtVZnzrLNOW89RgA==/com.google.android.katniss-mO9HKX1UmTQ6u2nN54tiEQ==/base.apk=com.google.android.katniss uid:10036
        package:/system/app/SecureElement/SecureElement.apk=com.android.se uid:1068
        package:/system_ext/priv-app/TclTvInput/TclTvInput.apk=com.tcl.tvinput uid:1000
        @@TVSLIM_USINE
        package:/product/overlay/MtkMdnsOffloadServiceOverlay.apk=com.mediatek.android.tv.mdns.offload.overlay
        package:/apex/com.android.tethering/priv-app/ServiceConnectivityResources@UTT2.250416.001/ServiceConnectivityResources.apk=com.android.connectivity.resources
        package:/system_ext/app/TGuard/TGuard.apk=com.tcl.guard
        package:/product/priv-app/Katniss/Katniss.apk=com.google.android.katniss
        package:/system/app/SecureElement/SecureElement.apk=com.android.se
        package:/system_ext/priv-app/TclTvInput/TclTvInput.apk=com.tcl.tvinput
        @@TVSLIM_ENTREE_TV
        2 services found:
          Service #0:
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
            com.tcl.tvinput/.TvPassThroughService
          Service #1:
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
            com.tcl.tvinput/.TunerInputService
        @@TVSLIM_ACCESSIBILITE
        No services found
        @@TVSLIM_CLAVIER
        1 services found:
          Service #0:
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
            flar2.homebutton/.utils.BMIME
        @@TVSLIM_DEMARRAGE
        1 receivers found:
          Receiver #0:
            priority=1000 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.tcl.guard/.receiver.BootReceiver
        No receivers found
        @@TVSLIM_ICONES
        1 activities found:
          Activity #0:
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            flar2.homebutton/.MainActivity
    """.trimIndent()

    @Test
    fun `chaque paquet porte son emplacement, son identite et ce qu'il declare`() {
        val indices = LectureIndices.interpreter(sortieTcl)

        val guard = indices.getValue("com.tcl.guard")
        assertEquals("system_ext/app", guard.emplacement)
        assertTrue(guard.droitsSysteme)
        assertFalse(guard.misAJour)
        assertFalse(guard.icone)
        assertEquals(setOf(DeclarationSensible.DEMARRAGE), guard.declarations)

        // Mise à jour : l'emplacement est celui de la version d'usine, pas celui de /data/app.
        val katniss = indices.getValue("com.google.android.katniss")
        assertEquals("/product/priv-app/Katniss/Katniss.apk", katniss.chemin)
        assertEquals("product/priv-app", katniss.emplacement)
        assertTrue(katniss.misAJour)
        assertTrue(katniss.privilegiee)
        assertEquals(10036, katniss.uid)
        assertFalse(katniss.uidReserve)

        val tuner = indices.getValue("com.tcl.tvinput")
        assertEquals(setOf(DeclarationSensible.ENTREE_TV), tuner.declarations)
        assertTrue(tuner.privilegiee)

        val elementSecurise = indices.getValue("com.android.se")
        assertEquals(1068, elementSecurise.uid)
        assertTrue(elementSecurise.uidReserve)
        assertFalse(elementSecurise.droitsSysteme)

        // Un identifiant par utilisateur : le premier est celui de l'utilisateur principal.
        val surcouche = indices.getValue("com.mediatek.android.tv.mdns.offload.overlay")
        assertEquals(10118, surcouche.uid)
        assertEquals("product/overlay", surcouche.emplacement)
        assertEquals("apex/com.android.tethering/priv-app", indices.getValue("com.android.connectivity.resources").emplacement)

        // Installée par la personne : sans version d'usine, le chemin reste celui de /data/app.
        val bouton = indices.getValue("flar2.homebutton")
        assertEquals("data/app", bouton.emplacement)
        assertFalse(bouton.misAJour)
        assertTrue(bouton.icone)
        assertEquals(setOf(DeclarationSensible.CLAVIER), bouton.declarations)
    }

    @Test
    fun `sans marqueur, rien n'est invente`() {
        assertTrue(LectureIndices.interpreter("").isEmpty())
        assertTrue(LectureIndices.interpreter("/system/bin/sh: cmd: not found").isEmpty())
    }

    @Test
    fun `la commande annonce chaque section sans ouvrir de commentaire shell`() {
        val commande = LectureIndices.COMMANDE
        assertTrue(commande, commande.split(' ', ';').map { it.trim() }.none { it.startsWith("#") })

        val marqueurs = listOf(LectureIndices.MARQUEUR_FICHIERS, LectureIndices.MARQUEUR_USINE, LectureIndices.MARQUEUR_ICONES) +
            DeclarationSensible.entries.map(LectureIndices::marqueur)
        assertEquals("Deux sections du même nom fusionneraient", marqueurs.size, marqueurs.toSet().size)
        marqueurs.forEach { assertTrue("La commande doit annoncer $it", commande.contains("echo $it;") || commande.endsWith("echo $it")) }
        assertFalse("Rien qu'une lecture", commande.contains(" disable") || commande.contains("uninstall"))
    }

    @Test
    fun `une derniere requete en echec n'efface pas les sections deja lues`() = runTest {
        // Le code de sortie d'une commande composée est celui de sa dernière requête.
        val lecteur = LecteurDistant(
            object : ExecuteurCommande {
                override suspend fun executer(commande: String) = ResultatShell(code = 255, sortie = sortieTcl)
            },
        )

        assertEquals(1000, lecteur.indices().getValue("com.tcl.guard").uid)
    }
}
