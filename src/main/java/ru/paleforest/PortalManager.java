package ru.paleforest;

import java.time.Instant;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import ru.paleforest.ConfigManager.Pos;

public final class PortalManager implements Listener {
    private final PalePortalPlugin plugin;
    private final ConfigManager config;
    private final DataManager data;

    private final Set<Pos> frame = new HashSet<>();
    private final Set<Pos> opening = new HashSet<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();

    private World world;
    private Location center;
    private boolean intact;
    private boolean unlocked;
    private BukkitTask checking;
    private BukkitTask particles;

    public PortalManager(PalePortalPlugin plugin) {
        this.plugin = plugin;
        config = plugin.settings();
        data = plugin.data();

        if (!config.raw().getBoolean("frame.enabled")) return;

        world = config.sourceWorld();
        if (world == null || world.equals(plugin.forest())) {
            throw new IllegalArgumentException(
                "Не найден исходный мир рамки либо выбран мир Бледного леса"
            );
        }

        center = config.frameCenter();
        discover();

        intact = data.yaml().getBoolean("portal.intact");
        unlocked = data.yaml().getBoolean("portal.unlocked");
        check();

        checking = Bukkit.getScheduler().runTaskTimer(
            plugin, this::check, 1,
            config.raw().getLong("portal.check-period-ticks")
        );
        particles = Bukkit.getScheduler().runTaskTimer(
            plugin, this::particles, 1,
            config.raw().getLong("portal.particle-period-ticks")
        );
    }

    private void discover() {
        Pos a = config.corner("min");
        Pos b = config.corner("max");

        int x0 = Math.min(a.x(), b.x());
        int x1 = Math.max(a.x(), b.x());
        int y0 = Math.min(a.y(), b.y());
        int y1 = Math.max(a.y(), b.y());
        int z0 = Math.min(a.z(), b.z());
        int z1 = Math.max(a.z(), b.z());

        boolean fixedX = x0 == x1;
        if (fixedX == (z0 == z1)) {
            throw new IllegalArgumentException(
                "Углы должны задавать вертикальную плоскость"
            );
        }

        long widthLong = fixedX
            ? (long) z1 - z0 + 1 : (long) x1 - x0 + 1;
        long heightLong = (long) y1 - y0 + 1;

        if (widthLong < 3 || heightLong < 3
            || widthLong > 128 || heightLong > 128
            || y0 < world.getMinHeight()
            || y1 >= world.getMaxHeight()) {
            throw new IllegalArgumentException(
                "Границы рамки некорректны; допустима плоскость до 128x128"
            );
        }

        int width = (int) widthLong;
        int height = (int) heightLong;
        String signature = world.getUID() + ":"
            + x0 + ":" + y0 + ":" + z0 + ":"
            + x1 + ":" + y1 + ":" + z1;

        if (signature.equals(data.yaml().getString("portal.signature"))) {
            data.yaml().getStringList("portal.frame")
                .forEach(s -> frame.add(Pos.parse(s)));
            data.yaml().getStringList("portal.opening")
                .forEach(s -> opening.add(Pos.parse(s)));

            if (frame.isEmpty() || opening.isEmpty()) {
                throw new IllegalArgumentException(
                    "Повреждена сохранённая геометрия портала"
                );
            }
            return;
        }

        Pos[][] grid = new Pos[width][height];
        boolean[][] wall = new boolean[width][height];
        boolean[][] outside = new boolean[width][height];
        ArrayDeque<int[]> queue = new ArrayDeque<>();

        for (int u = 0; u < width; u++) {
            for (int v = 0; v < height; v++) {
                Pos pos = new Pos(
                    fixedX ? x0 : x0 + u,
                    y0 + v,
                    fixedX ? z0 + u : z0
                );
                grid[u][v] = pos;
                wall[u][v] = pos.block(world).getType()
                    == Material.REINFORCED_DEEPSLATE;

                if (wall[u][v]) frame.add(pos);

                if ((u == 0 || v == 0
                    || u == width - 1 || v == height - 1)
                    && !wall[u][v]) {
                    outside[u][v] = true;
                    queue.add(new int[]{u, v});
                }
            }
        }

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] cell = queue.removeFirst();
            for (int[] d : directions) {
                int u = cell[0] + d[0];
                int v = cell[1] + d[1];

                if (u < 0 || v < 0 || u >= width || v >= height
                    || wall[u][v] || outside[u][v]) continue;

                outside[u][v] = true;
                queue.add(new int[]{u, v});
            }
        }

        for (int u = 0; u < width; u++) {
            for (int v = 0; v < height; v++) {
                if (wall[u][v] || outside[u][v]) continue;

                Pos pos = grid[u][v];
                Material type = pos.block(world).getType();
                if (!type.isAir() && !glass(type) && type != Material.BARRIER) {
                    throw new IllegalArgumentException(
                        "Освободите внутренний проём: " + pos.key()
                    );
                }
                opening.add(pos);
            }
        }

        if (frame.isEmpty() || opening.isEmpty()) {
            throw new IllegalArgumentException(
                "Не найден замкнутый проём внутри рамки из укреплённого глубинного сланца"
            );
        }

        data.yaml().set("portal.signature", signature);
        data.yaml().set(
            "portal.frame", frame.stream().map(Pos::key).toList()
        );
        data.yaml().set(
            "portal.opening", opening.stream().map(Pos::key).toList()
        );
        data.yaml().set("portal.unlocked", false);
        data.yaml().set("portal.intact", false);
        data.save();
    }

    private static boolean glass(Material type) {
        return type == Material.LIGHT_BLUE_STAINED_GLASS
            || type == Material.BLUE_STAINED_GLASS;
    }

    private void save() {
        data.yaml().set("portal.intact", intact);
        data.yaml().set("portal.unlocked", unlocked);
        data.save();
    }

    private void invalidate() {
        intact = false;
        unlocked = false;

        if (world != null) {
            for (Pos pos : opening) {
                Block block = pos.block(world);
                if (glass(block.getType()) || block.getType() == Material.BARRIER) {
                    block.setType(Material.AIR, false);
                }
            }
        }
        save();
    }

    public void check() {
        if (world == null) return;

        boolean valid = !frame.isEmpty() && frame.stream().allMatch(
            pos -> pos.block(world).getType()
                == Material.REINFORCED_DEEPSLATE
        );

        boolean changed = valid != intact || unlocked != valid;
        intact = valid;
        // Рамка из укреплённого глубинного сланца работает без музыкальной активации.
        // Проём всегда открыт, пока рамка цела.
        unlocked = valid;

        // Внутри рамки всегда свободный проём: телепортация срабатывает
        // при пересечении плоскости, поэтому блок портала не нужен.
        for (Pos pos : opening) {
            Block block = pos.block(world);
            Material type = block.getType();
            if (!valid) {
                if (type == Material.BARRIER || glass(type)) {
                    block.setType(Material.AIR, false);
                }
            } else {
                if (type == Material.BARRIER || glass(type)) {
                    block.setType(Material.AIR, false);
                }
            }
        }

        if (changed) save();
    }

    public boolean ready() {
        return world != null && intact;
    }

    public void unlock(Player player) {
        check();
        if (!ready()) return;

        if (unlocked) {
            player.sendMessage("Портал уже открыт.");
            return;
        }

        unlocked = true;
        save();
        // Принудительно убираем любые старые стеклянные блоки из проёма,
        // иначе игрок физически не сможет пройти сквозь портал.
        for (Pos pos : opening) {
            Block block = pos.block(world);
            if (glass(block.getType()) || block.getType() == Material.BARRIER)
                block.setType(Material.AIR, false);
        }

        world.playSound(
            center, Sound.BLOCK_PORTAL_TRIGGER, 1, 0.8f
        );
        world.playSound(
            center, Sound.ENTITY_ENDER_DRAGON_GROWL, 1, 0.8f
        );
        world.spawnParticle(
            Particle.PORTAL, center, 50, 1, 1.5, 0.5, 0.1
        );
        world.spawnParticle(
            Particle.SOUL_FIRE_FLAME, center, 50, 1, 1.5, 0.5, 0.02
        );
        player.sendMessage("Портал в Бледный лес открыт!");

        plugin.getLogger().info(
            "Активация портала: UUID=" + player.getUniqueId()
            + ", время=" + Instant.now()
        );
    }

    private void particles() {
        if (!ready() || !unlocked) return;

        // Purple, animated portal surface made only from particles: it looks
        // like a Nether portal but stays passable and cannot trigger vanilla Nether travel.
        for (Pos pos : opening) {
            Location at = pos.center(world);
            world.spawnParticle(Particle.PORTAL, at, 18, 0.22, 0.42, 0.12, 0.12);
            world.spawnParticle(Particle.DRAGON_BREATH, at, 3, 0.18, 0.35, 0.10, 0.01);
            world.spawnParticle(Particle.WITCH, at, 2, 0.20, 0.35, 0.10, 0.01);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (!ready() || !unlocked || to == null
            || !player.getWorld().equals(world)) return;

        if (from.getX() == to.getX()
            && from.getY() == to.getY()
            && from.getZ() == to.getZ()) return;

        // Пересекаем геометрическую плоскость портала. Не используем
        // столкновение с блоком стекла: оно физически не даёт пройти внутрь.
        Pos a = config.corner("min");
        Pos b = config.corner("max");
        boolean fixedX = a.x() == b.x();
        double plane = fixedX ? a.x() + 0.5 : a.z() + 0.5;
        double fromAxis = fixedX ? from.getX() : from.getZ();
        double toAxis = fixedX ? to.getX() : to.getZ();
        // Treat the player's width as part of the crossing test. Depending
        // on tick movement, the player's position can skip the exact center
        // plane (x/z + 0.5), which previously caused missed teleports.
        double segmentMin = Math.min(fromAxis, toAxis);
        double segmentMax = Math.max(fromAxis, toAxis);
        boolean crossed = segmentMin <= plane + 0.80
            && segmentMax >= plane - 0.80;
        if (!crossed) return;

        int minU = opening.stream().mapToInt(p -> fixedX ? p.z() : p.x())
            .min().orElse(Integer.MAX_VALUE);
        int maxU = opening.stream().mapToInt(p -> fixedX ? p.z() : p.x())
            .max().orElse(Integer.MIN_VALUE);
        int minY = opening.stream().mapToInt(Pos::y).min()
            .orElse(Integer.MAX_VALUE);
        int maxY = opening.stream().mapToInt(Pos::y).max()
            .orElse(Integer.MIN_VALUE);
        double horizontal = fixedX ? to.getZ() : to.getX();
        if (horizontal < minU - 0.3 || horizontal > maxU + 1.3
            || to.getY() + 1.8 < minY || to.getY() > maxY + 1.2) return;

        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        if (cooldown.getOrDefault(id, 0L) > now) return;

        Location destination = safeForestEntry();
        if (destination == null) {
            plugin.getLogger().warning("Не удалось найти безопасную точку входа в Бледный лес.");
            player.sendMessage("Не удалось найти безопасное место в Бледном лесу. Используйте /paleportal forest после настройки forest.entry.");
            cooldown.put(id, now + 3000);
            return;
        }

        Location back = from.clone();
        if (!PalePortalPlugin.safe(back, 2)) back = world.getSpawnLocation();
        data.yaml().set("returns." + id, back);
        data.save();
        cooldown.put(id, now + config.raw().getLong("portal.cooldown-seconds") * 1000);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !player.getWorld().equals(world)
                || !ready() || !unlocked) return;
            Location target = safeForestEntry();
            if (target == null) {
                plugin.getLogger().warning("Безопасная точка входа в Бледный лес не найдена.");
                return;
            }
            if (player.teleport(target)) {
                player.setFallDistance(0);
                player.sendMessage("Вы вошли в Бледный лес.");
            }
        });
    }

    private Location safeForestEntry() {
        World forest = plugin.forest();
        Location configured = config.entry(forest);
        if (PalePortalPlugin.safe(configured, 2)) return configured;

        Location spawn = forest.getSpawnLocation();
        if (PalePortalPlugin.safe(spawn, 2)) return spawn;

        int baseX = configured.getBlockX();
        int baseZ = configured.getBlockZ();
        int baseY = Math.max(forest.getMinHeight() + 2,
            Math.min(configured.getBlockY(), forest.getMaxHeight() - 4));
        for (int radius = 0; radius <= 16; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = -8; dy <= 8; dy++) {
                        int y = baseY + dy;
                        if (y <= forest.getMinHeight() || y >= forest.getMaxHeight() - 2) continue;
                        Location candidate = new Location(forest, baseX + dx + 0.5, y, baseZ + dz + 0.5);
                        if (PalePortalPlugin.safe(candidate, 2)) return candidate;
                    }
                }
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (world != null && event.getBlock().getWorld().equals(world)
            && frame.contains(Pos.of(event.getBlock()))) {
            invalidate();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        if (world != null && event.getLocation().getWorld().equals(world)
            && event.blockList().stream().anyMatch(
                block -> frame.contains(Pos.of(block))
            )) {
            invalidate();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        if (world != null && event.getBlock().getWorld().equals(world)
            && event.blockList().stream().anyMatch(
                block -> frame.contains(Pos.of(block))
            )) {
            invalidate();
        }
    }

    public void close(boolean removeVisual) {
        if (checking != null) checking.cancel();
        if (particles != null) particles.cancel();

        if (removeVisual && world != null) {
            for (Pos pos : opening) {
                Block block = pos.block(world);
                if (glass(block.getType()) || block.getType() == Material.BARRIER) {
                    block.setType(Material.AIR, false);
                }
            }
        }
    }
}
