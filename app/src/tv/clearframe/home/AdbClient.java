package tv.clearframe.home;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

// A minimal ADB client for the TV's own adbd on 127.0.0.1:5555, so the TV can be cleaned up
// with the remote alone. It speaks the plain (non-TLS) ADB protocol that Android 8 uses:
// CNXN, RSA AUTH, then one "shell:" stream per command. Protocol per AOSP
// system/core/adb/protocol.txt; key format and signing as in Cameron Gutman's AdbLib.
final class AdbClient implements Closeable {

    interface Listener { void waitingForAllow(); }

    static final class NotAllowedException extends IOException {
        private static final long serialVersionUID = 1L;
        NotAllowedException() { super("The TV didn't allow debugging."); }
    }

    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;
    private static final int KEY_BITS = 2048, KEY_WORDS = KEY_BITS / 32;
    // PKCS#1 v1.5 type 1 padding plus the SHA-1 DigestInfo prefix; adbd's 20-byte token is the digest.
    private static final byte[] SIGNATURE_PADDING;
    static {
        byte[] info = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        SIGNATURE_PADDING = new byte[KEY_BITS / 8 - 20];
        SIGNATURE_PADDING[0] = 0x00;
        SIGNATURE_PADDING[1] = 0x01;
        int ff = SIGNATURE_PADDING.length - 3 - info.length;
        for (int i = 0; i < ff; i++) SIGNATURE_PADDING[2 + i] = (byte) 0xff;
        SIGNATURE_PADDING[2 + ff] = 0x00;
        System.arraycopy(info, 0, SIGNATURE_PADDING, 3 + ff, info.length);
    }

    static final String HOST = "127.0.0.1";
    static final int PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 4000, READ_TIMEOUT_MS = 15000, ALLOW_TIMEOUT_MS = 120000, RETRY_MS = 3000;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private AdbClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    static AdbClient connect(Context ctx, Listener listener) throws IOException, GeneralSecurityException {
        return connect(keys(ctx), HOST, PORT, listener);
    }

    // Also used by ci/AdbClientHostTest.java against the emulator's adbd.
    static AdbClient connect(KeyPair keys, String host, int port, Listener listener) throws IOException, GeneralSecurityException {
        AdbClient c = open(host, port);
        try {
            if (c.signIn(keys)) return c;
            // The TV doesn't know this key yet: offering it makes the TV show "Allow USB debugging?".
            c.send(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKey((RSAPublicKey) keys.getPublic()));
            if (listener != null) listener.waitingForAllow();
            // The BRAVIA (Android 8) saves the key on OK but never answers this connection, so a
            // fresh connection also signs in every few seconds; it doesn't offer the key again.
            c.socket.setSoTimeout(RETRY_MS);
            long end = System.currentTimeMillis() + ALLOW_TIMEOUT_MS;
            while (System.currentTimeMillis() < end) {
                Message m;
                try { m = c.read(); }
                catch (SocketTimeoutException e) {
                    AdbClient again = retry(keys, host, port);
                    if (again != null) { c.close(); return again; }
                    continue;
                }
                if (m.command != A_CNXN) break;
                c.socket.setSoTimeout(READ_TIMEOUT_MS);
                return c;
            }
            throw new NotAllowedException();
        } catch (IOException | GeneralSecurityException e) {
            c.close();
            throw e;
        }
    }

    private static AdbClient open(String host, int port) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            AdbClient c = new AdbClient(s);
            c.send(A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            return c;
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }

    // Answers the TV's token with this key's signature. True once the TV accepts the connection,
    // false if it doesn't know the key (it sends a new token).
    private boolean signIn(KeyPair keys) throws IOException, GeneralSecurityException {
        boolean signed = false;
        while (true) {
            Message m = read();
            if (m.command == A_CNXN) return true;
            if (m.command != A_AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("Unexpected reply from the TV.");
            if (signed) return false;
            send(A_AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
            signed = true;
        }
    }

    // One sign-in on a new connection while the TV's message is up; null if the key isn't allowed yet.
    private static AdbClient retry(KeyPair keys, String host, int port) throws GeneralSecurityException {
        AdbClient c;
        try { c = open(host, port); } catch (IOException e) { return null; }
        boolean ok = false;
        try { ok = c.signIn(keys); }
        catch (IOException e) { /* try again next time */ }
        finally { if (!ok) c.close(); }
        return ok ? c : null;
    }

    // Runs one shell command and returns everything it printed, with \r removed.
    String shell(String command) throws IOException {
        int local = nextLocalId++, remote = 0;
        send(A_OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            Message m = read();
            if (m.arg1 != local) continue; // not ours
            if (m.command == A_OKAY) { remote = m.arg0; continue; }
            if (m.command == A_WRTE) {
                buf.write(m.data, 0, m.data.length);
                send(A_OKAY, local, m.arg0, null);
                continue;
            }
            if (m.command == A_CLSE) {
                if (remote != 0) send(A_CLSE, local, remote, null);
                break;
            }
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    // ---- wire format ----

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length, sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~command);
        if (len > 0) b.put(data);
        out.write(b.array());
        out.flush();
    }

    private Message read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        b.getInt(); // checksum
        int magic = b.getInt();
        if (magic != ~m.command || len < 0 || len > 1 << 20) throw new IOException("Garbled reply from the TV.");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---- keys ----

    private static KeyPair keys(Context ctx) throws IOException, GeneralSecurityException {
        File priv = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.isFile() && pub.isFile()) {
            try {
                return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
            } catch (GeneralSecurityException e) {
                // unreadable key: make a new one below; the TV will ask to allow it again
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(KEY_BITS);
        KeyPair k = g.generateKeyPair();
        writeAll(priv, k.getPrivate().getEncoded());
        writeAll(pub, k.getPublic().getEncoded());
        return k;
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key);
        c.update(SIGNATURE_PADDING);
        return c.doFinal(token);
    }

    // adbd's RSAPublicKey struct: word count, n0inv, modulus and R^2 mod n as little-endian
    // 32-bit words, then the exponent; base64-encoded, followed by " name" and a NUL.
    private static byte[] publicKey(RSAPublicKey key) {
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        BigInteger n = key.getModulus();
        BigInteger rr = BigInteger.ZERO.setBit(KEY_WORDS * 32).modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = n.remainder(r32).modInverse(r32);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + KEY_WORDS * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(KEY_WORDS);
        b.putInt(n0inv.negate().intValue());
        BigInteger t = n;
        for (int i = 0; i < KEY_WORDS; i++) {
            BigInteger[] qr = t.divideAndRemainder(r32);
            t = qr[0];
            b.putInt(qr[1].intValue());
        }
        t = rr;
        for (int i = 0; i < KEY_WORDS; i++) {
            BigInteger[] qr = t.divideAndRemainder(r32);
            t = qr[0];
            b.putInt(qr[1].intValue());
        }
        b.putInt(key.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " clearframe@tv\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
    }
}
