package net.foenui.mc.fileDownloader;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class DownloadCommand implements CommandExecutor, TabCompleter {

    private final FileDownloaderPlugin plugin;

    public DownloadCommand(FileDownloaderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("filedownloader.use")) {
            plugin.log(sender, "§cKamu tidak punya izin.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "help":
                sendHelp(sender, label);
                return true;
            case "list":
            case "ls":
                handleList(sender);
                return true;
            case "active":
            case "queue":
                handleActive(sender);
                return true;
            case "cancel":
                handleCancel(sender, Arrays.copyOfRange(args, 1, args.length));
                return true;
            case "delete":
            case "del":
            case "rm":
                handleDelete(sender, Arrays.copyOfRange(args, 1, args.length));
                return true;
            case "folder":
            case "dir":
                plugin.log(sender, "§7Folder: §8" + plugin.getDownloadFolder().getAbsolutePath());
                return true;
            default:
                break;
        }

        // /download <url> [nama-file]
        String url = args[0];
        String fileName = args.length >= 2 ? args[1] : null;
        if (args.length >= 2 && fileName != null) {
            // dukung nama file berspasi: gabung sisa args
            if (args.length > 2) {
                fileName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            }
        }
        plugin.getDownloadManager().downloadAsync(sender, url, fileName);
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        plugin.log(sender, "§b§lFileDownloader Help:");
        sender.sendMessage("§e/" + label + " <url> [nama-file] §7- download (http/https/ftp/ftps/sftp)");
        sender.sendMessage("§7  contoh: §bftp://user:pass@host/file.zip §7atau §bsftp://user@host/file.zip");
        sender.sendMessage("§e/" + label + " list §7- lihat file hasil download");
        sender.sendMessage("§e/" + label + " active §7- lihat download yang berjalan");
        sender.sendMessage("§e/" + label + " cancel <id|all> §7- batalkan download");
        sender.sendMessage("§e/" + label + " delete <nama-file> §7- hapus file");
        sender.sendMessage("§e/" + label + " folder §7- lihat lokasi folder");
    }

    private void handleList(CommandSender sender) {
        File folder = plugin.getDownloadFolder();
        File[] files = folder.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            plugin.log(sender, "§7Belum ada file. Folder: §8" + folder.getAbsolutePath());
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        plugin.log(sender, "§b§lDaftar file (" + files.length + "):");
        int max = Math.min(files.length, 20);
        for (int i = 0; i < max; i++) {
            File f = files[i];
            sender.sendMessage("§8- §e" + f.getName() + " §7(" + DownloadManager.formatSize(f.length()) + ")");
        }
        if (files.length > max) {
            sender.sendMessage("§7... dan " + (files.length - max) + " file lainnya.");
        }
    }

    private void handleActive(CommandSender sender) {
        Map<UUID, DownloadManager.ActiveDownload> active = plugin.getDownloadManager().getActive();
        if (active.isEmpty()) {
            plugin.log(sender, "§7Tidak ada download yang berjalan.");
            return;
        }
        plugin.log(sender, "§b§lDownload aktif (" + active.size() + "):");
        for (DownloadManager.ActiveDownload d : active.values()) {
            sender.sendMessage("§8- §7" + DownloadManager.shortId(d.id) + " §e" + d.fileName + " §7" + d.progress + "% §8(" + d.url + ")");
        }
    }

    private void handleCancel(CommandSender sender, String[] args) {
        Map<UUID, DownloadManager.ActiveDownload> active = plugin.getDownloadManager().getActive();
        if (active.isEmpty()) {
            plugin.log(sender, "§7Tidak ada download yang berjalan.");
            return;
        }
        if (args.length == 0) {
            plugin.log(sender, "§7Pakai: §e/download cancel <id|all>");
            handleActive(sender);
            return;
        }
        if (args[0].equalsIgnoreCase("all")) {
            plugin.getDownloadManager().cancelAll();
            plugin.log(sender, "§eSemua download dibatalkan.");
            return;
        }
        String prefix = args[0].toLowerCase();
        UUID match = null;
        for (UUID id : active.keySet()) {
            if (id.toString().toLowerCase().startsWith(prefix)
                    || DownloadManager.shortId(id).startsWith(prefix)) {
                match = id;
                break;
            }
        }
        if (match == null) {
            plugin.log(sender, "§cID tidak ditemukan: " + args[0]);
            return;
        }
        plugin.getDownloadManager().cancel(match);
        plugin.log(sender, "§eDownload dibatalkan: §7" + DownloadManager.shortId(match));
    }

    private void handleDelete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("filedownloader.admin")) {
            plugin.log(sender, "§cButuh izin §efiledownloader.admin§c untuk hapus file.");
            return;
        }
        if (args.length == 0) {
            plugin.log(sender, "§7Pakai: §e/download delete <nama-file>");
            return;
        }
        String name = DownloadManager.sanitizeFileName(String.join(" ", args));
        File target = new File(plugin.getDownloadFolder(), name);
        try {
            String base = plugin.getDownloadFolder().getCanonicalPath();
            if (!target.getCanonicalPath().startsWith(base + File.separator)) {
                plugin.log(sender, "§cNama file tidak valid.");
                return;
            }
        } catch (Exception e) {
            plugin.log(sender, "§cGagal validasi path.");
            return;
        }
        if (!target.isFile()) {
            plugin.log(sender, "§cFile tidak ditemukan: §e" + name);
            return;
        }
        if (target.delete()) {
            plugin.log(sender, "§aDihapus: §e" + name);
        } else {
            plugin.log(sender, "§cGagal menghapus: §e" + name);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = Arrays.asList("help", "list", "active", "cancel", "delete", "folder");
            String prefix = args[0].toLowerCase();
            // kalau user lagi ngetik URL, jangan ganggu
            if (args[0].contains(":") || args[0].contains(".")) return Collections.emptyList();
            return subs.stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("cancel")) {
                return plugin.getDownloadManager().getActive().keySet().stream()
                        .map(DownloadManager::shortId)
                        .filter(id -> id.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (sub.equals("delete")) {
                File folder = plugin.getDownloadFolder();
                File[] files = folder.listFiles(File::isFile);
                if (files == null) return Collections.emptyList();
                List<String> names = new ArrayList<>();
                for (File f : files) {
                    if (f.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                        names.add(f.getName());
                    }
                }
                return names;
            }
        }
        return Collections.emptyList();
    }
}
