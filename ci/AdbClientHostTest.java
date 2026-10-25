package tv.clearframe.home;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;

// Runs AdbClient on the CI machine against the emulator's adbd (localhost:5555), with the host
// adb server stopped. Modes:
//   host <adbkey>     sign in with the host's authorized key and run read-only commands
//   newkey <dir>      sign in with a key the emulator hasn't seen: adbd must show "Allow USB debugging?"
public final class AdbClientHostTest {
    public static void main(String[] args) throws Exception {
        KeyPair keys = args[0].equals("host") ? hostKey(args[1]) : storedKey(args[1]);
        AdbClient c = AdbClient.connect(keys, "127.0.0.1", 5555, () -> System.out.println("WAITING_FOR_ALLOW"));
        System.out.println("CONNECTED");
        System.out.println("sdk=" + c.shell("getprop ro.build.version.sdk").trim());
        System.out.println("home=" + c.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").trim().replace('\n', ' '));
        int n = c.shell("pm list packages").split("\n").length;
        System.out.println("packages=" + n);
        String flags = c.shell("dumpsys package | grep -E 'Package \\[|pkgFlags=\\[' | head -4");
        System.out.println("flags-sample=" + flags.replace('\n', '|'));
        c.close();
        if (n < 10) throw new AssertionError("too few packages");
        System.out.println("ADB_CLIENT_OK");
    }

    private static KeyPair hostKey(String path) throws Exception {
        String pem = new String(Files.readAllBytes(Paths.get(path)), "UTF-8")
                .replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(pem)));
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
        PublicKey pub = kf.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        return new KeyPair(pub, priv);
    }

    private static KeyPair storedKey(String dir) throws Exception {
        java.io.File d = new java.io.File(dir), pf = new java.io.File(d, "pub"), sf = new java.io.File(d, "priv");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (pf.isFile()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pf.toPath()))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(sf.toPath()))));
        }
        d.mkdirs();
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair k = g.generateKeyPair();
        Files.write(pf.toPath(), k.getPublic().getEncoded());
        Files.write(sf.toPath(), k.getPrivate().getEncoded());
        return k;
    }

}
