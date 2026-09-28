package net.foenui.mc.fileDownloader;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class FileDownloaderPlugin extends JavaPlugin {

    private DownloadManager downloadManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        File folder = getDownloadFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            getLogger().warning("Gagal membuat folder downloads: " + folder.getAbsolutePath());
        }

        this.downloadManager = new DownloadManager(this);

        DownloadCommand cmd = new DownloadCommand(this);
        if (getCommand("download") != null) {
            getCommand("download").setExecutor(cmd);
            getCommand("download").setTabCompleter(cmd);
        } else {
            getLogger().severe("Command 'download' tidak terdaftar di plugin.yml!");
        }

        getLogger().info("FileDownloader aktif. Folder: " + folder.getAbsolutePath());
    }

    @Override
    public void onDisable() {
        if (downloadManager != null) {
            downloadManager.cancelAll();
        }
        getLogger().info("FileDownloader nonaktif.");
    }

    public File getDownloadFolder() {
        String sub = getConfig().getString("download-folder", "downloads");
        return new File(getDataFolder(), sub);
    }

    public DownloadManager getDownloadManager() {
        return downloadManager;
    }

    public void log(CommandSender sender, String msg) {
        sender.sendMessage("§8[§bFileDownloader§8] §r" + msg);
    }
}
