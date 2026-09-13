import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Signe un installateur de TV Slim pour la mise à jour automatique.
 *
 * <pre>
 *   java outils/SignerMiseAJour.java TVSlim-Windows-1.2.3.msi 1.2.3
 * </pre>
 *
 * La clé privée (PKCS#8, base64) vient de la variable d'environnement TVSLIM_CLE_SIGNATURE — jamais
 * d'un fichier ni d'un argument, qui finiraient dans un historique. Écrit
 * TVSlim-Windows-1.2.3.msi.sig à côté de l'installateur.
 *
 * Le message signé lie la version à l'empreinte du fichier : « TVSlim-Windows\n&lt;version&gt;\n&lt;sha256&gt; ».
 * L'application vérifie exactement le même (src/jvmMain/.../maj/VerificationSignature.kt), et un
 * test s'assure que les deux s'accordent.
 *
 * Aucune dépendance : ce fichier se lance tel quel avec un JDK 17 ou plus récent.
 */
public class SignerMiseAJour {

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            System.err.println("usage : java SignerMiseAJour.java <installateur.msi> <version>");
            System.exit(2);
        }
        String cle = System.getenv("TVSLIM_CLE_SIGNATURE");
        if (cle == null || cle.isBlank()) {
            System.err.println("TVSLIM_CLE_SIGNATURE est absente de l'environnement.");
            System.exit(2);
        }

        Path installateur = Path.of(arguments[0]);
        String version = arguments[1];
        if (!version.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,5}")) {
            System.err.println("Version invalide : " + version);
            System.exit(2);
        }

        MessageDigest condensat = MessageDigest.getInstance("SHA-256");
        try (var flux = Files.newInputStream(installateur)) {
            byte[] tampon = new byte[64 * 1024];
            int lu;
            while ((lu = flux.read(tampon)) >= 0) {
                condensat.update(tampon, 0, lu);
            }
        }
        String empreinte = HexFormat.of().formatHex(condensat.digest());
        byte[] message = ("TVSlim-Windows\n" + version + "\n" + empreinte).getBytes(StandardCharsets.UTF_8);

        PrivateKey privee = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(cle.trim())));
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privee);
        signature.update(message);

        Path sortie = installateur.resolveSibling(installateur.getFileName() + ".sig");
        Files.writeString(sortie, Base64.getEncoder().encodeToString(signature.sign()) + "\n");
        System.out.println(sortie.getFileName() + "  sha256=" + empreinte);
    }
}
