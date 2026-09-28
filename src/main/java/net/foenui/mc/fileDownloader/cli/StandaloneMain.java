package net.foenui.mc.fileDownloader.cli;

import net.foenui.mc.fileDownloader.DownloadCore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Mode standalone: {@code java -jar FileDownloader-*-all.jar [--config config.json]}.
 * Sekali jalan: baca config.json, download semua jobs berurutan, selesai.
 * Kalau config.json belum ada, dibuatkan otomatis lalu program berhenti
 * agar user bisa mengisinya dulu.
 */
public final class StandaloneMain {

    private StandaloneMain() {
    }

    public static void main(String[] args) {
        File configFile = new File("config.json");
        for (int i = 0; i < args.length; i++) {
            if ((args[i].equals("--config") || args[i].equals("-c")) && i + 1 < args.length) {
                configFile = new File(args[i + 1]);
                i++;
            } else if (args[i].equals("--help") || args[i].equals("-h")) {
                printUsage();
                return;
            }
        }

        if (!configFile.isFile()) {
            try {
                File parent = configFile.getAbsoluteFile().getParentFile();
                if (parent != null) parent.mkdirs();
                Files.write(configFile.toPath(), defaultConfig().getBytes(StandardCharsets.UTF_8));
                System.out.println("config.json belum ada, sudah dibuatkan: " + configFile.getAbsolutePath());
                System.out.println("Isi daftar 'jobs' lalu jalankan lagi.");
            } catch (Exception e) {
                System.err.println("Gagal membuat config.json: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        JSONObject cfg;
        try {
            cfg = new JSONObject(Files.readString(configFile.toPath(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("Gagal membaca " + configFile.getAbsolutePath() + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        File baseDir = configFile.getAbsoluteFile().getParentFile();
        DownloadCore.Options opt = new DownloadCore.Options();
        opt.timeoutSeconds = cfg.optInt("timeoutSeconds", 30);
        opt.maxBytes = cfg.optLong("maxFileSizeMb", 100) * 1024L * 1024L;
        opt.userAgent = cfg.optString("userAgent", "FileDownloader/1.0 (standalone)");
        opt.blockPrivateAddresses = cfg.optBoolean("blockPrivateAddresses", true);
        opt.progressStepPercent = cfg.optInt("progressStepPercent", 10);
        opt.sftpPrivateKey = cfg.optString("sftpPrivateKey", "");
        opt.sftpPassphrase = cfg.optString("sftpPassphrase", "");
        opt.sftpKnownHosts = cfg.optString("sftpKnownHosts", "");
        opt.sftpStrictHostKeyChecking = cfg.optBoolean("sftpStrictHostKeyChecking", false);
        opt.keyBaseDir = baseDir;

        File downloadFolder = new File(cfg.optString("downloadFolder", "downloads"));
        if (!downloadFolder.isAbsolute()) {
            downloadFolder = new File(baseDir, cfg.optString("downloadFolder", "downloads"));
        }
        if (!downloadFolder.exists() && !downloadFolder.mkdirs()) {
            System.err.println("Gagal membuat folder: " + downloadFolder.getAbsolutePath());
            System.exit(1);
        }

        boolean overwrite = cfg.optBoolean("overwriteExisting", false);
        JSONArray jobs = cfg.optJSONArray("jobs");
        if (jobs == null || jobs.length() == 0) {
            System.out.println("Tidak ada jobs di config.json (" + configFile.getAbsolutePath() + ").");
            return;
        }

        System.out.println("FileDownloader standalone: " + jobs.length() + " job(s), folder: " + downloadFolder.getAbsolutePath());
        int ok = 0;
        int failed = 0;
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.optJSONObject(i);
            if (job == null) {
                System.out.println("[" + (i + 1) + "/" + jobs.length() + "] SKIP: entri bukan object.");
                failed++;
                continue;
            }
            String urlString = job.optString("url", "").trim();
            if (urlString.isEmpty()) {
                System.out.println("[" + (i + 1) + "/" + jobs.length() + "] SKIP: field 'url' kosong.");
                failed++;
                continue;
            }
            String fileName = DownloadCore.sanitizeFileName(
                    job.optString("file", "").isEmpty() ? DownloadCore.guessFileName(urlString) : job.optString("file", ""));
            if (fileName.isEmpty()) {
                fileName = "download-" + System.currentTimeMillis();
            }
            boolean jobOverwrite = job.optBoolean("overwrite", overwrite);
            System.out.println("[" + (i + 1) + "/" + jobs.length() + "] " + urlString + " -> " + fileName);

            File outFile = new File(downloadFolder, fileName);
            try {
                String base = downloadFolder.getCanonicalPath();
                if (!outFile.getCanonicalPath().startsWith(base + File.separator)) {
                    throw new IllegalArgumentException("Nama file tidak valid.");
                }
            } catch (Exception e) {
                System.out.println("  GAGAL: " + e.getMessage());
                failed++;
                continue;
            }
            if (outFile.exists() && !jobOverwrite) {
                System.out.println("  SKIP: file sudah ada (set overwrite=true untuk timpa).");
                failed++;
                continue;
            }

            try {
                long bytes = runOne(urlString, outFile, fileName, opt);
                System.out.println("  SELESAI (" + DownloadCore.formatSize(bytes) + "): " + outFile.getAbsolutePath());
                ok++;
            } catch (Exception e) {
                try {
                    Files.deleteIfExists(outFile.toPath());
                } catch (Exception ignored) {
                }
                System.out.println("  GAGAL: " + e.getMessage());
                failed++;
            }
        }

        System.out.println("Ringkasan: " + ok + " berhasil, " + failed + " gagal.");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static long runOne(String urlString, File outFile, String fileName, DownloadCore.Options opt) throws Exception {
        URI uri = URI.create(urlString.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("URL tidak valid.");
        }
        if (opt.blockPrivateAddresses && DownloadCore.isPrivateHost(uri.getHost())) {
            throw new IllegalArgumentException("Host diblokir (private/local address).");
        }

        DownloadCore.Cancel cancel = new DownloadCore.Cancel();
        DownloadCore.ProgressListener listener = (name, percent, done, total) -> {
            if (percent >= 0) {
                System.out.println("  ..." + name + ": " + percent + "% (" + DownloadCore.formatSize(done) + "/" + DownloadCore.formatSize(total) + ")");
            } else {
                System.out.println("  ..." + name + ": " + DownloadCore.formatSize(done));
            }
        };

        switch (scheme) {
            case "ftp":
                return DownloadCore.downloadFtp(uri, false, outFile, fileName, opt, cancel, listener);
            case "ftps":
                return DownloadCore.downloadFtp(uri, true, outFile, fileName, opt, cancel, listener);
            case "sftp":
                return DownloadCore.downloadSftp(uri, outFile, fileName, opt, cancel, listener);
            case "http":
            case "https":
                return DownloadCore.downloadHttp(uri.toURL(), outFile, fileName, opt, cancel, listener);
            default:
                throw new IllegalArgumentException("Protokol tidak didukung. Gunakan http(s)://, ftp://, ftps://, atau sftp://");
        }
    }

    private static void printUsage() {
        System.out.println("Penggunaan: java -jar FileDownloader-*-all.jar [--config <path-ke-config.json>]");
        System.out.println("Kalau config belum ada, dibuatkan otomatis lalu program berhenti.");
    }

    private static String defaultConfig() {
        return "{\n"
                + "  \"_notes\": [\n"
                + "    \"Daftar download sekali jalan. Contoh job:\",\n"
                + "    \"{ \\\"url\\\": \\\"https://example.com/file.zip\\\", \\\"file\\\": \\\"file.zip\\\" }\",\n"
                + "    \"{ \\\"url\\\": \\\"ftp://user:pass@host:21/path/file.zip\\\" }\",\n"
                + "    \"{ \\\"url\\\": \\\"sftp://user:pass@host:22/path/file.zip\\\" }\",\n"
                + "    \"Field 'file' opsional (ditebak dari URL). 'overwrite' per-job opsional.\"\n"
                + "  ],\n"
                + "  \"downloadFolder\": \"downloads\",\n"
                + "  \"maxFileSizeMb\": 100,\n"
                + "  \"timeoutSeconds\": 30,\n"
                + "  \"userAgent\": \"FileDownloader/1.0 (standalone)\",\n"
                + "  \"overwriteExisting\": false,\n"
                + "  \"blockPrivateAddresses\": true,\n"
                + "  \"progressStepPercent\": 10,\n"
                + "  \"sftpPrivateKey\": \"\",\n"
                + "  \"sftpPassphrase\": \"\",\n"
                + "  \"sftpKnownHosts\": \"\",\n"
                + "  \"sftpStrictHostKeyChecking\": false,\n"
                + "  \"jobs\": []\n"
                + "}\n";
    }
}
