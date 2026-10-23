package ru.paleforest;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;

public final class ConfigManager {
    private final PalePortalPlugin plugin;

    public ConfigManager(PalePortalPlugin plugin) {
        this.plugin = plugin;
        validate();
    }

    public FileConfiguration raw() {
        return plugin.getConfig();
    }

    public record Pos(int x, int y, int z) {
        public static Pos of(Block block) {
            return new Pos(block.getX(), block.getY(), block.getZ());
        }

        public static Pos parse(String text) {
            String[] parts = text.split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException(
                    "Координаты должны иметь формат x:y:z"
                );
            }
            return new Pos(
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim())
            );
        }

        public String key() {
            return x + ":" + y + ":" + z;
        }

        public Block block(World world) {
            return world.getBlockAt(x, y, z);
        }

        public Location center(World world) {
            return new Location(world, x + 0.5, y + 0.5, z + 0.5);
        }
    }

    public Pos corner(String name) {
        return Pos.parse(raw().getString("frame." + name, "0:0:0"));
    }

    public World sourceWorld() {
        return Bukkit.getWorld(raw().getString("frame.world", "world"));
    }

    public Location frameCenter() {
        World world = sourceWorld();
        if (world == null) return null;

        Pos a = corner("min");
        Pos b = corner("max");
        return new Location(
            world,
            ((double) a.x() + b.x()) / 2 + 0.5,
            ((double) a.y() + b.y()) / 2 + 0.5,
            ((double) a.z() + b.z()) / 2 + 0.5
        );
    }

    public List<Pos> musicBlocks() {
        return raw().getStringList("music.blocks").stream()
            .map(Pos::parse).toList();
    }

    public List<Integer> notes() {
        return raw().getIntegerList("music.notes");
    }

    public Material material() {
        return Material.valueOf(
            raw().getString(
                "portal.material", "LIGHT_BLUE_STAINED_GLASS"
            ).toUpperCase(Locale.ROOT)
        );
    }

    public Location entry(World world) {
        return new Location(
            world,
            raw().getDouble("forest.entry.x", 0.5),
            raw().getDouble("forest.entry.y", 90),
            raw().getDouble("forest.entry.z", 0.5),
            (float) raw().getDouble("forest.entry.yaw"),
            (float) raw().getDouble("forest.entry.pitch")
        );
    }

    private void validate() {
        if (material() != Material.LIGHT_BLUE_STAINED_GLASS
            && material() != Material.BLUE_STAINED_GLASS) {
            throw new IllegalArgumentException(
                "Материал портала должен быть синим или голубым стеклом"
            );
        }

        if (notes().size() != 5
            || notes().stream().anyMatch(n -> n < 0 || n > 24)) {
            throw new IllegalArgumentException(
                "В music.notes нужны пять ID от 0 до 24"
            );
        }

        for (String path : List.of(
            "music.timeout-seconds",
            "music.radius",
            "portal.particle-period-ticks",
            "portal.check-period-ticks",
            "portal.cooldown-seconds",
            "mobs.spawn-period-seconds",
            "mobs.spawn-radius",
            "mobs.ancient.attack-radius"
        )) {
            double value = raw().getDouble(path);
            if (!Double.isFinite(value) || value <= 0) {
                throw new IllegalArgumentException(
                    "Настройка " + path + " должна быть положительной"
                );
            }
        }

        for (String type : List.of("normal", "smart", "kind", "ancient")) {
            String prefix = "mobs." + type + ".";
            double health = raw().getDouble(prefix + "health");
            double damage = raw().getDouble(prefix + "damage");
            double speed = raw().getDouble(prefix + "speed");

            if (!Double.isFinite(health) || health <= 0 || health > 1024
                || !Double.isFinite(damage) || damage < 0 || damage > 1024
                || !Double.isFinite(speed) || speed < 0 || speed > 1) {
                throw new IllegalArgumentException(
                    "Некорректные характеристики: " + type
                );
            }
        }

        List<Pos> blocks = musicBlocks();
        if (!blocks.isEmpty() && (
            blocks.size() != 5
            || new HashSet<>(blocks).size() != 5
        )) {
            plugin.getLogger().warning(
                "Для мелодии нужны пять разных музыкальных блоков."
            );
        }

        Location center = frameCenter();
        if (center != null) {
            double radius = raw().getDouble("music.radius");
            for (Pos pos : blocks) {
                if (pos.center(center.getWorld()).distanceSquared(center)
                    > radius * radius) {
                    plugin.getLogger().warning(
                        "Музыкальный блок вне радиуса: " + pos.key()
                    );
                }
            }
        }
    }
}
