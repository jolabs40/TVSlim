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
 * Signs a TV Slim installer for auto-update.
 *
 * <pre>
 *   java outils/SignerMiseAJour.java TVSlim-Windows-1.2.3.msi 1.2.3
 * </pre>
 *
 * The private key (PKCS#8, base64) comes from the TVSLIM_CLE_SIGNATURE environment variable, never from a
 * file or an argument, which could end up in a shell history. Writes TVSlim-Windows-1.2.3.msi.sig next to
 * the installer.
 *
 * The signed message binds the version to the file hash: "TVSlim-Windows\n&lt;version&gt;\n&lt;sha256&gt;".
 * The app verifies exactly the same message (src/jvmMain/.../maj/VerificationSignature.kt), and a test
 * checks that both agree.
 *
 * No dependencies: runs as is with JDK 17 or later.
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
