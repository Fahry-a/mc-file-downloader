package net.foenui.mc.fileDownloader;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Inti download murni-Java tanpa ketergantungan Bukkit.
 * Dipakai bersama oleh mode plugin (DownloadManager) dan mode standalone (cli.StandaloneMain).
 */
public final class DownloadCore {

    private DownloadCore() {
    }

    /** Opsi download. Dibangun dari config.yml (plugin) atau config.json (standalone). */
    public static final class Options {
        public int timeoutSeconds = 30;
        /** Batas ukuran bytes. 0 = tanpa batas (tidak disarankan). */
        public long maxBytes = 100L * 1024 * 1024;
        public String userAgent = "FileDownloader/1.0";
        public boolean blockPrivateAddresses = true;
        public int progressStepPercent = 10;
        public String sftpPrivateKey = "";
        public String sftpPassphrase = "";
        public String sftpKnownHosts = "";
        public boolean sftpStrictHostKeyChecking = false;
        /** Folder acuan untuk path key/known_hosts yang relatif. Boleh null (= cwd). */
        public File keyBaseDir = null;
    }

    /** Token cancel yang bisa dibagikan antar thread. */
    public static final class Cancel {
        public volatile boolean cancelled = false;
    }

    /** Callback progress. Implementasi menentukan cara menampilkannya (chat / console). */
    public interface ProgressListener {
        /** @param totalBytes -1 kalau ukuran tidak diketahui */
        void onProgress(String fileName, int percent, long doneBytes, long totalBytes);

        default void onMessage(String message) {
        }
    }

    /** Download_Http/Https. */
    public static long downloadHttp(URL url, File outFile, String fileName,
                                    Options opt, Cancel cancel, ProgressListener listener) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(opt.timeoutSeconds * 1000);
        conn.setReadTimeout(opt.timeoutSeconds * 1000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", opt.userAgent);
        conn.connect();

        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " " + conn.getResponseMessage());
            }
            long contentLength = conn.getContentLengthLong();
            checkSize(contentLength, opt.maxBytes);
            return copyStream(conn.getInputStream(), outFile, fileName, opt, cancel, listener, contentLength);
        } finally {
            conn.disconnect();
        }
    }

    /** Download FTP (secure=false) / FTPS explicit TLS (secure=true). */
    public static long downloadFtp(URI uri, boolean secure, File outFile, String fileName,
                                   Options opt, Cancel cancel, ProgressListener listener) throws IOException {
        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 21 : uri.getPort();
        String[] creds = parseUserInfo(uri);
        String user = creds[0] != null ? creds[0] : "anonymous";
        String pass = creds[1] != null ? creds[1] : "anonymous@";
        String remotePath = uri.getPath();
        if (remotePath == null || remotePath.isEmpty() || remotePath.endsWith("/")) {
            throw new IOException("Path FTP harus menunjuk ke file, bukan folder.");
        }

        FTPClient ftp = secure ? new FTPSClient("TLS", false) : new FTPClient();
        ftp.setConnectTimeout(opt.timeoutSeconds * 1000);
        try {
            ftp.connect(host, port);
            if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                throw new IOException("Server FTP menolak koneksi: " + ftp.getReplyString());
            }
            if (!ftp.login(user, pass)) {
                throw new IOException("Login FTP gagal untuk user '" + user + "'.");
            }
            if (secure) {
                try {
                    FTPSClient ftps = (FTPSClient) ftp;
                    ftps.execPBSZ(0);
                    ftps.execPROT("P");
                } catch (IOException ignored) {
                    // server tidak dukung proteksi data channel, lanjut tanpa itu
                }
            }
            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTP.BINARY_FILE_TYPE);

            long size = -1;
            FTPFile[] list = ftp.listFiles(remotePath);
            if (list != null && list.length == 1 && list[0].isFile()) {
                size = list[0].getSize();
            }
            checkSize(size, opt.maxBytes);

            InputStream in = ftp.retrieveFileStream(remotePath);
            if (in == null) {
                throw new IOException("Gagal mengambil file: " + ftp.getReplyString().trim());
            }
            long total = copyStream(in, outFile, fileName, opt, cancel, listener, size);
            in.close();

            if (cancel.cancelled) {
                return total;
            }
            if (!ftp.completePendingCommand()) {
                throw new IOException("Transfer FTP tidak selesai: " + ftp.getReplyString().trim());
            }
            return total;
        } finally {
            try {
                if (ftp.isConnected()) {
                    ftp.logout();
                    ftp.disconnect();
                }
            } catch (IOException ignored) {
            }
        }
    }

    /** Download SFTP. */
    public static long downloadSftp(URI uri, File outFile, String fileName,
                                    Options opt, Cancel cancel, ProgressListener listener) throws Exception {
        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 22 : uri.getPort();
        String[] creds = parseUserInfo(uri);
        if (creds[0] == null || creds[0].isEmpty()) {
            throw new IOException("SFTP butuh username. Contoh: sftp://user@host/path/file.zip");
        }
        String remotePath = uri.getPath();
        if (remotePath == null || remotePath.isEmpty() || remotePath.endsWith("/")) {
            throw new IOException("Path SFTP harus menunjuk ke file, bukan folder.");
        }

        JSch jsch = new JSch();
        if (opt.sftpPrivateKey != null && !opt.sftpPrivateKey.trim().isEmpty()) {
            File kf = resolveBase(opt.keyBaseDir, opt.sftpPrivateKey.trim());
            if (!kf.isFile()) {
                throw new IOException("Private key SFTP tidak ditemukan: " + kf.getAbsolutePath());
            }
            if (opt.sftpPassphrase != null && !opt.sftpPassphrase.isEmpty()) {
                jsch.addIdentity(kf.getAbsolutePath(), opt.sftpPassphrase.getBytes(StandardCharsets.UTF_8));
            } else {
                jsch.addIdentity(kf.getAbsolutePath());
            }
        }

        if (opt.sftpStrictHostKeyChecking && opt.sftpKnownHosts != null && !opt.sftpKnownHosts.trim().isEmpty()) {
            File kh = resolveBase(opt.keyBaseDir, opt.sftpKnownHosts.trim());
            jsch.setKnownHosts(kh.getAbsolutePath());
        }

        Session session = jsch.getSession(creds[0], host, port);
        if (creds[1] != null) {
            session.setPassword(creds[1].getBytes(StandardCharsets.UTF_8));
        }
        session.setConfig("StrictHostKeyChecking", opt.sftpStrictHostKeyChecking ? "yes" : "no");
        session.setTimeout(opt.timeoutSeconds * 1000);
        session.connect(opt.timeoutSeconds * 1000);

        ChannelSftp ch = null;
        try {
            ch = (ChannelSftp) session.openChannel("sftp");
            ch.connect(opt.timeoutSeconds * 1000);

            SftpATTRS attrs;
            try {
                attrs = ch.lstat(remotePath);
            } catch (SftpException e) {
                throw new IOException("File SFTP tidak ditemukan: " + remotePath);
            }
            if (attrs.isDir()) {
                throw new IOException("Path SFTP adalah folder, bukan file.");
            }
            long size = attrs.getSize();
            checkSize(size, opt.maxBytes);

            InputStream in = ch.get(remotePath);
            return copyStream(in, outFile, fileName, opt, cancel, listener, size);
        } finally {
            try {
                if (ch != null && ch.isConnected()) {
                    ch.disconnect();
                }
            } catch (Exception ignored) {
            }
            try {
                if (session.isConnected()) {
                    session.disconnect();
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ================= Helper =================

    /** Salin stream ke file dengan cek batas ukuran, cancel, dan notifikasi progress. */
    public static long copyStream(InputStream rawIn, File outFile, String fileName,
                                  Options opt, Cancel cancel, ProgressListener listener,
                                  long contentLength) throws IOException {
        int step = opt.progressStepPercent;
        if (step < 5) step = 5;
        final long unknownStep = 5L * 1024 * 1024;

        long total = 0;
        int lastNotified = 0;
        long lastMsgBytes = 0;

        try (BufferedInputStream in = new BufferedInputStream(rawIn);
             OutputStream fos = new FileOutputStream(outFile);
             BufferedOutputStream out = new BufferedOutputStream(fos)) {

            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (cancel.cancelled) break;
                out.write(buf, 0, n);
                total += n;
                if (opt.maxBytes > 0 && total > opt.maxBytes) {
                    throw new IOException("Ukuran file melebihi batas (" + formatSize(opt.maxBytes) + ").");
                }
                if (contentLength > 0) {
                    int pct = (int) (total * 100 / contentLength);
                    if (pct - lastNotified >= step) {
                        lastNotified = pct;
                        listener.onProgress(fileName, pct, total, contentLength);
                    }
                } else if (total - lastMsgBytes >= unknownStep) {
                    lastMsgBytes = total;
                    listener.onProgress(fileName, -1, total, -1);
                }
            }
            out.flush();
        }
        return total;
    }

    private static void checkSize(long size, long maxBytes) throws IOException {
        if (maxBytes > 0 && size > maxBytes) {
            throw new IOException("Ukuran file (" + formatSize(size) + ") melebihi batas (" + formatSize(maxBytes) + ").");
        }
    }

    private static File resolveBase(File base, String path) {
        File f = new File(path);
        if (!f.isAbsolute() && base != null) {
            f = new File(base, path);
        }
        return f;
    }

    /** Pecah userinfo "user:pass" dari URI (mendukung percent-encoding). Kembali {user, pass}, bisa null. */
    public static String[] parseUserInfo(URI uri) {
        String raw = uri.getRawUserInfo();
        if (raw == null || raw.isEmpty()) {
            return new String[]{null, null};
        }
        try {
            int i = raw.indexOf(':');
            if (i >= 0) {
                return new String[]{
                        URLDecoder.decode(raw.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(raw.substring(i + 1), StandardCharsets.UTF_8)
                };
            }
            return new String[]{URLDecoder.decode(raw, StandardCharsets.UTF_8), null};
        } catch (Exception e) {
            int i = raw.indexOf(':');
            if (i >= 0) {
                return new String[]{raw.substring(0, i), raw.substring(i + 1)};
            }
            return new String[]{raw, null};
        }
    }

    public static boolean isPrivateHost(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isAnyLocalAddress()
                    || addr.isLoopbackAddress()
                    || addr.isSiteLocalAddress()
                    || addr.isLinkLocalAddress()
                    || addr.isMulticastAddress();
        } catch (Exception e) {
            return false; // kalau gagal resolve, biarkan koneksi yang gagal
        }
    }

    public static String sanitizeFileName(String name) {
        if (name == null) return "";
        // ambil segmen terakhir kalau user kasih path
        name = name.replace('\\', '/');
        if (name.contains("/")) {
            name = name.substring(name.lastIndexOf('/') + 1);
        }
        name = name.trim();
        // buang query string kalau ke-copy dari URL
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        int h = name.indexOf('#');
        if (h >= 0) name = name.substring(0, h);
        // hanya izinkan karakter aman
        name = name.replaceAll("[^a-zA-Z0-9._\\- ]", "_");
        // cegah hidden/dot-only & traversal
        while (name.equals(".") || name.equals("..")) name = "_" + name;
        name = name.replaceAll("^\\.+", "_");
        if (name.length() > 128) name = name.substring(0, 128);
        return name.trim();
    }

    public static String guessFileName(String urlString) {
        try {
            String path = URI.create(urlString).getPath();
            if (path == null || path.isEmpty() || path.endsWith("/")) {
                return "download-" + System.currentTimeMillis();
            }
            String base = path.substring(path.lastIndexOf('/') + 1);
            String clean = sanitizeFileName(base);
            return clean.isEmpty() ? "download-" + System.currentTimeMillis() : clean;
        } catch (Exception e) {
            return "download-" + System.currentTimeMillis();
        }
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.2f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }

    public static String shortId(java.util.UUID id) {
        return id.toString().substring(0, 8);
    }
}
