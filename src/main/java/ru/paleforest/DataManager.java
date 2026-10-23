package ru.paleforest;

import java.io.File;
import java.nio.file.*;
import org.bukkit.configuration.file.YamlConfiguration;

public final class DataManager {
    private final PalePortalPlugin plugin;
    private final File file;
    private final YamlConfiguration yaml = new YamlConfiguration();

    public DataManager(PalePortalPlugin plugin) throws Exception {
        this.plugin = plugin;
        Files.createDirectories(plugin.getDataFolder().toPath());
        file = new File(plugin.getDataFolder(), "data.yml");

        if (file.exists()) {
            // Ошибка чтения не должна приводить к перезаписи
            // существующего состояния пустыми значениями.
            yaml.load(file);
        }
    }

    public YamlConfiguration yaml() {
        return yaml;
    }

    public void save() {
        Path target = file.toPath();
        Path temp = target.resolveSibling("data.yml.tmp");

        try {
            yaml.save(temp.toFile());
            try {
                Files.move(
                    temp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                    temp, target, StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (Exception exception) {
            plugin.getLogger().severe(
                "Не удалось сохранить data.yml: "
                + exception.getMessage()
            );
        }
    }
}
