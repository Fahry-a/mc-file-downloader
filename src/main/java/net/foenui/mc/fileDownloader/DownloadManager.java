package net.foenui.mc.fileDownloader;

import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adapter Bukkit di atas DownloadCore: izin, async scheduler, dan pesan ke sender. */
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
            if (d.cancelHook != null) d.cancelHook.run();
        }
        active.clear();
    }

    public boolean cancel(UUID id) {
        ActiveDownload d = active.remove(id);
        if (d == null) return false;
        d.cancelled.set(true);
        if (d.cancelHook != null) d.cancelHook.run();
        return true;
    }

    DownloadCore.Options buildOptions() {
        DownloadCore.Options opt = new DownloadCore.Options();
        opt.timeoutSeconds = plugin.getConfig().getInt("timeout-seconds", 30);
        opt.maxBytes = plugin.getConfig().getLong("max-file-size-mb", 100) * 1024L * 1024L;
        opt.userAgent = plugin.getConfig().getString("user-agent", "FileDownloader/1.0 (PaperMC)");
        opt.blockPrivateAddresses = plugin.getConfig().getBoolean("block-private-addresses", true);
        opt.progressStepPercent = plugin.getConfig().getInt("progress-step-percent", 10);
        opt.sftpPrivateKey = plugin.getConfig().getString("sftp-private-key", "");
        opt.sftpPassphrase = plugin.getConfig().getString("sftp-passphrase", "");
        opt.sftpKnownHosts = plugin.getConfig().getString("sftp-known-hosts", "");
        opt.sftpStrictHostKeyChecking = plugin.getConfig().getBoolean("sftp-strict-host-key-checking", false);
        opt.keyBaseDir = plugin.getDataFolder();
        return opt;
    }

    public void downloadAsync(CommandSender sender, String urlString, String rawFileName) {
        DownloadCore.Options opt = buildOptions();

        URI uri;
        String scheme;
        try {
            uri = URI.create(urlString.trim());
            scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            switch (scheme) {
                case "http":
                case "https":
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
            if (opt.blockPrivateAddresses && DownloadCore.isPrivateHost(uri.getHost())) {
                plugin.log(sender, "§cHost diblokir (private/local address).");
                return;
            }
        } catch (Exception e) {
            plugin.log(sender, "§cURL tidak valid: " + e.getMessage());
            return;
        }

        String fileName = DownloadCore.sanitizeFileName(rawFileName != null ? rawFileName : DownloadCore.guessFileName(urlString));
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
        URL finalUrl;
        try {
            finalUrl = (finalScheme.equals("http") || finalScheme.equals("https")) ? finalUri.toURL() : null;
        } catch (Exception e) {
            plugin.log(sender, "§cURL tidak valid: " + e.getMessage());
            active.remove(taskId);
            return;
        }

        DownloadCore.Cancel cancel = new DownloadCore.Cancel();
        DownloadCore.ProgressListener listener = new DownloadCore.ProgressListener() {
            @Override
            public void onProgress(String name, int percent, long done, long total) {
                task.progress = Math.max(percent, 0);
                final String msg = percent >= 0
                        ? "§7Progress §e" + name + ": §b" + percent + "% §7(" + DownloadCore.formatSize(done) + "/" + DownloadCore.formatSize(total) + ")"
                        : "§7Downloaded §b" + DownloadCore.formatSize(done) + " §7... §e" + name;
                plugin.getServer().getScheduler().runTask(plugin, () -> plugin.log(sender, msg));
            }
        };

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.log(sender, "§7Mulai download §b" + urlString + " §7-> §e" + finalFileName + " §8(id: " + DownloadCore.shortId(taskId) + ")");
            try {
                long bytes;
                switch (finalScheme) {
                    case "ftp":
                        bytes = DownloadCore.downloadFtp(finalUri, false, outFile, finalFileName, opt, cancel, listener);
                        break;
                    case "ftps":
                        bytes = DownloadCore.downloadFtp(finalUri, true, outFile, finalFileName, opt, cancel, listener);
                        break;
                    case "sftp":
                        bytes = DownloadCore.downloadSftp(finalUri, outFile, finalFileName, opt, cancel, listener);
                        break;
                    default:
                        bytes = DownloadCore.downloadHttp(finalUrl, outFile, finalFileName, opt, cancel, listener);
                        break;
                }
                active.remove(taskId);
                if (task.cancelled.get()) {
                    try { Files.deleteIfExists(outFile.toPath()); } catch (IOException ignored) {}
                    plugin.log(sender, "§eDownload dibatalkan: §7" + finalFileName);
                } else {
                    plugin.log(sender, "§aSelesai! §e" + finalFileName + " §7(" + DownloadCore.formatSize(bytes) + ") tersimpan di §8" + outFile.getAbsolutePath());
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

        // hubungkan cancel command ke token core (polling di copyStream)
        task.cancelHook = () -> cancel.cancelled = true;
    }

    // Delegasi helper (dipakai DownloadCommand juga)
    public static String sanitizeFileName(String name) {
        return DownloadCore.sanitizeFileName(name);
    }

    public static String formatSize(long bytes) {
        return DownloadCore.formatSize(bytes);
    }

    public static String shortId(UUID id) {
        return DownloadCore.shortId(id);
    }

    public static final class ActiveDownload {
        public final UUID id;
        public final String url;
        public final String fileName;
        public final long startedAt = System.currentTimeMillis();
        public volatile int progress = 0;
        public final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile Runnable cancelHook = null;

        ActiveDownload(UUID id, String url, String fileName) {
            this.id = id;
            this.url = url;
            this.fileName = fileName;
        }
    }
}
