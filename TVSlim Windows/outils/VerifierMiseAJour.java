import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;

/**
 * Checks that a TV Slim installer was signed with the project key. This is the check the app runs before
 * installing an update, so anyone can repeat it by hand.
 *
 * <pre>
 *   java outils/VerifierMiseAJour.java TVSlim-Windows-1.2.3.msi 1.2.3 [gradle.properties]
 * </pre>
 *
 * Reads the signature next to the installer (.msi.sig) and the public key from gradle.properties
 * (clePubliqueMisesAJour), the same key the app embeds. Exits 0 if the signature is valid, 1 otherwise.
 *
 * The release workflow runs it right after signing: a private key that does not match the embedded key
 * would produce a release that every install refuses.
 *
 * The message format is the one in SignerMiseAJour.java and VerificationSignature.kt; a test checks that
 * all three agree. No dependencies: JDK 17 or later.
 */
public class VerifierMiseAJour {

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2 || arguments.length > 3) {
            System.err.println("usage : java VerifierMiseAJour.java <installateur.msi> <version> [gradle.properties]");
            System.exit(2);
        }
        Path installateur = Path.of(arguments[0]);
        String version = arguments[1];
        Path proprietes = Path.of(arguments.length == 3 ? arguments[2] : "gradle.properties");

        Properties lues = new Properties();
        try (var flux = Files.newInputStream(proprietes)) {
            lues.load(flux);
        }
        String cle = lues.getProperty("clePubliqueMisesAJour", "").trim();
        if (cle.isEmpty()) {
            System.err.println("clePubliqueMisesAJour est absente de " + proprietes);
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

        Path fichierSignature = installateur.resolveSibling(installateur.getFileName() + ".sig");
        boolean valide;
        try {
            PublicKey publique = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(cle)));
            Signature verification = Signature.getInstance("Ed25519");
            verification.initVerify(publique);
            verification.update(message);
            valide = verification.verify(Base64.getDecoder().decode(Files.readString(fichierSignature).trim()));
        } catch (java.io.IOException | IllegalArgumentException | java.security.GeneralSecurityException erreur) {
            System.err.println(erreur);
            valide = false;
        }

        System.out.println((valide ? "Signature valide" : "SIGNATURE INVALIDE") + " : "
                + installateur.getFileName() + "  sha256=" + empreinte);
        System.exit(valide ? 0 : 1);
    }
}
