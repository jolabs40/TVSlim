package net.jolabs40.tvslim.windows.maj

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ce que la mise à jour accepte de proposer — et surtout ce qu'elle refuse. */
class ChoixPublicationTest {

    private val depot = "jolabs40/TVSlim"

    private fun fichier(nom: String, tag: String) =
        FichierPublie(nom, "https://github.com/$depot/releases/download/$tag/$nom", 1_000)

    private fun publication(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        fichiers: List<FichierPublie>? = null,
    ): PublicationGithub {
        val version = tag.removePrefix("windows-v")
        return PublicationGithub(
            tag = tag,
            body = "Notes $version",
            draft = draft,
            prerelease = prerelease,
            page = "https://github.com/$depot/releases/tag/$tag",
            assets = fichiers ?: listOf(
                fichier("TVSlim-Windows-$version.msi", tag),
                fichier("TVSlim-Windows-$version.msi.sig", tag),
                fichier("TVSlim-Windows-$version-portable.zip", tag),
            ),
        )
    }

    @Test
    fun `les versions se comparent nombre par nombre, pas comme du texte`() {
        assertTrue(Version.lire("1.10.0")!! > Version.lire("1.9.9")!!)
        assertEquals(Version(2, 0, 13), Version.lire(" 2.0.13 "))
        listOf("1.2", "1.2.3.4", "v1.2.3", "1.2.3-beta", "", "a.b.c").forEach {
            assertNull(it, Version.lire(it))
        }
    }

    @Test
    fun `la plus haute version Windows est retenue, les publications Android sont ignorees`() {
        val maj = ChoixPublication.choisir(
            listOf(
                publication("windows-v1.1.0"),
                PublicationGithub(tag = "android-v3.0.0", page = "https://github.com/$depot/releases/tag/android-v3.0.0"),
                publication("windows-v1.2.0"),
                publication("windows-v1.0.5"),
            ),
            actuelle = Version(1, 0, 0),
            depot = depot,
        )

        assertEquals(Version(1, 2, 0), maj?.version)
        assertEquals("TVSlim-Windows-1.2.0.msi", maj?.installateur?.name)
        assertEquals("TVSlim-Windows-1.2.0.msi.sig", maj?.signature?.name)
        assertEquals("Notes 1.2.0", maj?.notes)
    }

    @Test
    fun `rien quand la version en cours est deja la plus recente`() {
        assertNull(ChoixPublication.choisir(listOf(publication("windows-v1.2.0")), Version(1, 2, 0), depot))
        assertNull(ChoixPublication.choisir(listOf(publication("windows-v1.2.0")), Version(1, 3, 0), depot))
    }

    @Test
    fun `brouillons et preversions ne sont jamais proposes`() {
        val publications = listOf(
            publication("windows-v2.0.0", draft = true),
            publication("windows-v1.5.0", prerelease = true),
        )

        assertNull(ChoixPublication.choisir(publications, Version(1, 0, 0), depot))
    }

    @Test
    fun `une publication sans signature n'est pas proposee`() {
        val tag = "windows-v1.2.0"
        val sansSignature = publication(tag, fichiers = listOf(fichier("TVSlim-Windows-1.2.0.msi", tag)))

        assertNull(ChoixPublication.choisir(listOf(sansSignature), Version(1, 0, 0), depot))
    }

    @Test
    fun `un fichier heberge ailleurs que sur le depot est refuse`() {
        val tag = "windows-v1.2.0"
        val detourne = publication(
            tag,
            fichiers = listOf(
                FichierPublie("TVSlim-Windows-1.2.0.msi", "https://exemple.invalide/TVSlim-Windows-1.2.0.msi"),
                fichier("TVSlim-Windows-1.2.0.msi.sig", tag),
            ),
        )

        assertNull(ChoixPublication.choisir(listOf(detourne), Version(1, 0, 0), depot))
        assertFalse(ChoixPublication.urlDuDepot("https://github.com/autre/TVSlim/releases/download/x/a.msi", depot))
        assertFalse(
            ChoixPublication.urlDuDepot(
                "https://github.com/jolabs40/TVSlim/releases/download/../../autre/depot/a.msi",
                depot,
            ),
        )
        assertFalse(ChoixPublication.urlDuDepot("http://github.com/jolabs40/TVSlim/releases/download/x/a.msi", depot))
        assertTrue(ChoixPublication.urlDuDepot("https://github.com/jolabs40/tvslim/releases/download/windows-v1.2.0/a.msi", depot))
    }

    @Test
    fun `la reponse de l'API GitHub se lit, champs inconnus compris`() {
        val reponse = """
            [{"url":"https://api.github.com/repos/jolabs40/TVSlim/releases/1","tag_name":"windows-v1.0.1",
              "name":"TV Slim 1.0.1","draft":false,"prerelease":false,
              "html_url":"https://github.com/jolabs40/TVSlim/releases/tag/windows-v1.0.1",
              "body":"Correctifs","author":{"login":"jolabs40"},
              "assets":[
                {"name":"TVSlim-Windows-1.0.1.msi","size":84000000,"content_type":"application/x-msi",
                 "browser_download_url":"https://github.com/jolabs40/TVSlim/releases/download/windows-v1.0.1/TVSlim-Windows-1.0.1.msi"},
                {"name":"TVSlim-Windows-1.0.1.msi.sig","size":89,
                 "browser_download_url":"https://github.com/jolabs40/TVSlim/releases/download/windows-v1.0.1/TVSlim-Windows-1.0.1.msi.sig"}
              ]}]
        """.trimIndent()

        val publications = Json { ignoreUnknownKeys = true }
            .decodeFromString(ListSerializer(PublicationGithub.serializer()), reponse)
        val maj = ChoixPublication.choisir(publications, Version(1, 0, 0), depot)

        assertEquals(Version(1, 0, 1), maj?.version)
        assertEquals("Correctifs", maj?.notes)
        assertNull(maj?.portable)
        assertEquals("https://github.com/jolabs40/TVSlim/releases/tag/windows-v1.0.1", maj?.page)
    }
}
