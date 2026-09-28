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
import org.bukkit.command.CommandSender;

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
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DownloadManager {

    private final FileDownloaderPlugin plugin;
    private final Map<UUID, ActiveDownload> active = new ConcurrentHashMap<>();

    public DownloadManager(FileDownloaderPlugin plugin) {
        this.plugin = plugin;
    }

    public int activeCount() {
        return active.size();
    }

    public Map<UUID, ActiveDownload> getActive() {
        return active;
    }

    public void cancelAll() {
        for (ActiveDownload d : active.values()) {
            d.cancelled.set(true);
        }
        active.clear();
    }

    public boolean cancel(UUID id) {
        ActiveDownload d = active.remove(id);
        if (d == null) return false;
        d.cancelled.set(true);
        return true;
    }

    public void downloadAsync(CommandSender sender, String urlString, String rawFileName) {
        int timeoutSec = plugin.getConfig().getInt("timeout-seconds", 30);
        long maxBytes = plugin.getConfig().getLong("max-file-size-mb", 100) * 1024L * 1024L;
        String userAgent = plugin.getConfig().getString("user-agent", "FileDownloader/1.0 (PaperMC)");
        boolean blockPrivate = plugin.getConfig().getBoolean("block-private-addresses", true);

        URL url = null;
        URI uri;
        String scheme;
        try {
            uri = URI.create(urlString.trim());
            scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            switch (scheme) {
                case "http":
                case "https":
                    url = uri.toURL();
                    break;
                case "ftp":
                case "ftps":
                case "sftp":
                    break;
                default:
                    plugin.log(sender, "§cProtokol tidak didukung. Gunakan §ehttp(s)://§c, §eftp://§c, §eftps://§c, atau §esftp://");
                    return;
            }
            if (uri.getHost() == null) {
                plugin.log(sender, "§cURL tidak valid.");
                return;
            }
            if (blockPrivate && isPrivateHost(uri.getHost())) {
                plugin.log(sender, "§cHost diblokir (private/local address).");
                return;
            }
        } catch (Exception e) {
            plugin.log(sender, "§cURL tidak valid: " + e.getMessage());
            return;
        }

        String fileName = sanitizeFileName(rawFileName != null ? rawFileName : guessFileName(urlString));
        if (fileName.isEmpty()) {
            fileName = "download-" + System.currentTimeMillis();
        }

        File outFile = new File(plugin.getDownloadFolder(), fileName);
        // cegah traversal: pastikan canonical path masih di dalam folder
        try {
            String base = plugin.getDownloadFolder().getCanonicalPath();
            String target = outFile.getCanonicalPath();
            if (!target.startsWith(base + File.separator) && !target.equals(base)) {
                plugin.log(sender, "§cNama file tidak valid.");
                return;
            }
        } catch (IOException e) {
            plugin.log(sender, "§cGagal validasi path: " + e.getMessage());
            return;
        }

        if (outFile.exists() && !plugin.getConfig().getBoolean("overwrite-existing", false)) {
            plugin.log(sender, "§cFile sudah ada: §e" + fileName + " §7(ganti nama atau hapus dulu)");
            return;
        }

        UUID taskId = UUID.randomUUID();
        ActiveDownload task = new ActiveDownload(taskId, urlString, fileName);
        active.put(taskId, task);

        String finalFileName = fileName;
        URI finalUri = uri;
        String finalScheme = scheme;
        URL finalUrl = url;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.log(sender, "§7Mulai download §b" + urlString + " §7-> §e" + finalFileName + " §8(id: " + shortId(taskId) + ")");
            try {
                long bytes;
                switch (finalScheme) {
                    case "ftp":
                        bytes = downloadFtp(finalUri, false, outFile, task, sender, timeoutSec, maxBytes);
                        break;
                    case "ftps":
                        bytes = downloadFtp(finalUri, true, outFile, task, sender, timeoutSec, maxBytes);
                        break;
                    case "sftp":
                        bytes = downloadSftp(finalUri, outFile, task, sender, timeoutSec, maxBytes);
                        break;
                    default:
                        bytes = downloadInternal(finalUrl, outFile, task, sender, timeoutSec, maxBytes, userAgent);
                        break;
                }
                active.remove(taskId);
                if (task.cancelled.get()) {
                    try { Files.deleteIfExists(outFile.toPath()); } catch (IOException ignored) {}
                    plugin.log(sender, "§eDownload dibatalkan: §7" + finalFileName);
                } else {
                    plugin.log(sender, "§aSelesai! §e" + finalFileName + " §7(" + formatSize(bytes) + ") tersimpan di §8" + outFile.getAbsolutePath());
                }
            } catch (Exception e) {
                active.remove(taskId);
                try { Files.deleteIfExists(outFile.toPath()); } catch (IOException ignored) {}
                if (task.cancelled.get()) {
                    plugin.log(sender, "§eDownload dibatalkan: §7" + finalFileName);
                } else {
                    plugin.log(sender, "§cGagal download: " + e.getMessage());
                }
            }
        });
    }

    private long downloadInternal(URL url, File outFile, ActiveDownload task,
                                  CommandSender sender, int timeoutSec, long maxBytes,
                                  String userAgent) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(timeoutSec * 1000);
        conn.setReadTimeout(timeoutSec * 1000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", userAgent);
        conn.connect();

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " " + conn.getResponseMessage());
        }

        long contentLength = conn.getContentLengthLong();
        if (maxBytes > 0 && contentLength > maxBytes) {
            throw new IOException("Ukuran file (" + formatSize(contentLength) + ") melebihi batas (" + formatSize(maxBytes) + ").");
        }

        int notifyEveryPercent = plugin.getConfig().getInt("progress-step-percent", 10);
        if (notifyEveryPercent < 5) notifyEveryPercent = 5;

        long total = 0;
        int lastNotified = 0;

        try (InputStream raw = conn.getInputStream();
             BufferedInputStream in = new BufferedInputStream(raw);
             OutputStream fos = new FileOutputStream(outFile);
             BufferedOutputStream out = new BufferedOutputStream(fos)) {

            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (task.cancelled.get()) break;
                out.write(buf, 0, n);
                total += n;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new IOException("Ukuran file melebihi batas (" + formatSize(maxBytes) + ").");
                }
                if (contentLength > 0) {
                    int pct = (int) (total * 100 / contentLength);
                    if (pct - lastNotified >= notifyEveryPercent) {
                        lastNotified = pct;
                        task.progress = pct;
                        final long fTotal = total;
                        plugin.getServer().getScheduler().runTask(plugin, () ->
                                plugin.log(sender, "§7Progress §e" + task.fileName + ": §b" + pct + "% §7(" + formatSize(fTotal) + "/" + formatSize(contentLength) + ")"));
                    }
                } else if (total % (5 * 1024 * 1024) < 8192) {
                    final long fTotal = total;
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            plugin.log(sender, "§7Downloaded §b" + formatSize(fTotal) + " §7... §e" + task.fileName));
                }
            }
            out.flush();
        } finally {
            conn.disconnect();
        }

        if (task.cancelled.get()) return total;
        return total;
    }

    // ================= FTP / FTPS =================

    private long downloadFtp(URI uri, boolean secure, File outFile, ActiveDownload task,
                             CommandSender sender, int timeoutSec, long maxBytes) throws IOException {
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
        ftp.setConnectTimeout(timeoutSec * 1000);
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
            if (maxBytes > 0 && size > maxBytes) {
                throw new IOException("Ukuran file (" + formatSize(size) + ") melebihi batas (" + formatSize(maxBytes) + ").");
            }

            InputStream in = ftp.retrieveFileStream(remotePath);
            if (in == null) {
                throw new IOException("Gagal mengambil file: " + ftp.getReplyString().trim());
            }
            long total = copyStream(in, outFile, task, sender, size, maxBytes);
            in.close();

            if (task.cancelled.get()) {
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

    // ================= SFTP =================

    private long downloadSftp(URI uri, File outFile, ActiveDownload task,
                              CommandSender sender, int timeoutSec, long maxBytes) throws Exception {
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
        String keyPath = plugin.getConfig().getString("sftp-private-key", "");
        if (keyPath != null && !keyPath.trim().isEmpty()) {
            File kf = new File(keyPath.trim());
            if (!kf.isAbsolute()) {
                kf = new File(plugin.getDataFolder(), keyPath.trim());
            }
            if (!kf.isFile()) {
                throw new IOException("Private key SFTP tidak ditemukan: " + kf.getAbsolutePath());
            }
            String passphrase = plugin.getConfig().getString("sftp-passphrase", "");
            if (passphrase != null && !passphrase.isEmpty()) {
                jsch.addIdentity(kf.getAbsolutePath(), passphrase.getBytes(StandardCharsets.UTF_8));
            } else {
                jsch.addIdentity(kf.getAbsolutePath());
            }
        }

        boolean strict = plugin.getConfig().getBoolean("sftp-strict-host-key-checking", false);
        String knownHosts = plugin.getConfig().getString("sftp-known-hosts", "");
        if (strict && knownHosts != null && !knownHosts.trim().isEmpty()) {
            File kh = new File(knownHosts.trim());
            if (!kh.isAbsolute()) {
                kh = new File(plugin.getDataFolder(), knownHosts.trim());
            }
            jsch.setKnownHosts(kh.getAbsolutePath());
        }

        Session session = jsch.getSession(creds[0], host, port);
        if (creds[1] != null) {
            session.setPassword(creds[1].getBytes(StandardCharsets.UTF_8));
        }
        session.setConfig("StrictHostKeyChecking", strict ? "yes" : "no");
        session.setTimeout(timeoutSec * 1000);
        session.connect(timeoutSec * 1000);

        ChannelSftp ch = null;
        try {
            ch = (ChannelSftp) session.openChannel("sftp");
            ch.connect(timeoutSec * 1000);

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
            if (maxBytes > 0 && size > maxBytes) {
                throw new IOException("Ukuran file (" + formatSize(size) + ") melebihi batas (" + formatSize(maxBytes) + ").");
            }

            InputStream in = ch.get(remotePath);
            return copyStream(in, outFile, task, sender, size, maxBytes);
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
    private long copyStream(InputStream rawIn, File outFile, ActiveDownload task,
                            CommandSender sender, long contentLength, long maxBytes) throws IOException {
        int notifyEveryPercent = plugin.getConfig().getInt("progress-step-percent", 10);
        if (notifyEveryPercent < 5) notifyEveryPercent = 5;
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
                if (task.cancelled.get()) break;
                out.write(buf, 0, n);
                total += n;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new IOException("Ukuran file melebihi batas (" + formatSize(maxBytes) + ").");
                }
                if (contentLength > 0) {
                    int pct = (int) (total * 100 / contentLength);
                    if (pct - lastNotified >= notifyEveryPercent) {
                        lastNotified = pct;
                        task.progress = pct;
                        final long fTotal = total;
                        plugin.getServer().getScheduler().runTask(plugin, () ->
                                plugin.log(sender, "§7Progress §e" + task.fileName + ": §b" + pct + "% §7(" + formatSize(fTotal) + "/" + formatSize(contentLength) + ")"));
                    }
                } else if (total - lastMsgBytes >= unknownStep) {
                    lastMsgBytes = total;
                    final long fTotal = total;
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            plugin.log(sender, "§7Downloaded §b" + formatSize(fTotal) + " §7... §e" + task.fileName));
                }
            }
            out.flush();
        }
        return total;
    }

    /** Pecah userinfo "user:pass" dari URI (mendukung percent-encoding). Kembali {user, pass}, bisa null. */
    private static String[] parseUserInfo(URI uri) {
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

    private boolean isPrivateHost(String host) {
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

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.2f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }

    public static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    public static final class ActiveDownload {
        public final UUID id;
        public final String url;
        public final String fileName;
        public final long startedAt = System.currentTimeMillis();
        public volatile int progress = 0;
        public final AtomicBoolean cancelled = new AtomicBoolean(false);

        ActiveDownload(UUID id, String url, String fileName) {
            this.id = id;
            this.url = url;
            this.fileName = fileName;
        }
    }
}
