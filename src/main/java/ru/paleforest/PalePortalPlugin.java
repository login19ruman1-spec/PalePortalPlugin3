package ru.paleforest;

import java.util.*;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.weather.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import ru.paleforest.ConfigManager.Pos;

public final class PalePortalPlugin extends JavaPlugin
        implements Listener, CommandExecutor, TabCompleter {

    private ConfigManager settings;
    private DataManager data;
    private PortalManager portal;
    private CreakingManager mobs;
    private World forest;
    private BukkitTask atmosphere;

    private record Corner(UUID world, Pos position) {}

    private final Map<UUID, Corner> corners = new HashMap<>();
    private final Map<UUID, List<Pos>> musicSelection = new HashMap<>();
    private final Set<UUID> vision = new HashSet<>();
    private final Set<UUID> darkness = new HashSet<>();

    public ConfigManager settings() {
        return settings;
    }

    public DataManager data() {
        return data;
    }

    public PortalManager portal() {
        return portal;
    }

    public World forest() {
        return forest;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            data = new DataManager(this);

            PluginCommand command = Objects.requireNonNull(
                getCommand("paleportal"),
                "Команда paleportal отсутствует в plugin.yml"
            );

            command.setExecutor(this);
            command.setTabCompleter(this);

            start();
        } catch (Exception exception) {
            getLogger().log(
                java.util.logging.Level.SEVERE,
                "Не удалось запустить PalePortal",
                exception
            );

            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void start() {
        settings = new ConfigManager(this);

        String name = getConfig().getString(
            "forest.name",
            "pale_forest"
        );

        if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(
                "Некорректное имя мира Бледного леса"
            );
        }

        if (forest != null && !forest.getName().equals(name)) {
            throw new IllegalArgumentException(
                "Для изменения имени мира перезапустите сервер"
            );
        }

        forest = new WorldCreator(name)
            .seed(getConfig().getLong("forest.seed"))
            .generator(new PaleWorldGenerator())
            .createWorld();

        if (forest == null) {
            throw new IllegalStateException(
                "Не удалось открыть мир Бледного леса"
            );
        }

        forest.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        forest.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        forest.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        forest.setTime(6000);
        forest.setStorm(false);
        forest.setThundering(false);

        Location entry = settings.entry(forest);

        if (!Double.isFinite(entry.getX())
                || !Double.isFinite(entry.getY())
                || !Double.isFinite(entry.getZ())
                || Math.abs(entry.getX()) > 29_000_000
                || Math.abs(entry.getZ()) > 29_000_000
                || entry.getY() < forest.getMinHeight() + 2
                || entry.getY() > forest.getMaxHeight() - 5) {

            throw new IllegalArgumentException(
                "Некорректная точка входа в Бледный лес"
            );
        }

        String entryKey = forest.getUID() + ":"
            + entry.getBlockX() + ":"
            + entry.getBlockY() + ":"
            + entry.getBlockZ();

        if (!entryKey.equals(
                data.yaml().getString("forest.prepared-entry"))) {

            prepareEntry(entry);
            data.yaml().set("forest.prepared-entry", entryKey);
            data.save();
        }

        forest.setSpawnLocation(entry);

        portal = new PortalManager(this);
        mobs = new CreakingManager(this);

        var manager = getServer().getPluginManager();

        manager.registerEvents(this, this);
        manager.registerEvents(portal, this);
        manager.registerEvents(new NoteListener(this), this);
        manager.registerEvents(mobs, this);

        atmosphere = getServer().getScheduler().runTaskTimer(
            this,
            this::updateAtmosphere,
            1L,
            40L
        );

        getLogger().info(
            "PalePortal включён. Мир: " + forest.getName()
        );
    }

    private void prepareEntry(Location at) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                forest.getBlockAt(
                    at.getBlockX() + dx,
                    at.getBlockY() - 1,
                    at.getBlockZ() + dz
                ).setType(Material.PALE_MOSS_BLOCK, false);

                for (int dy = 0; dy < 4; dy++) {
                    forest.getBlockAt(
                        at.getBlockX() + dx,
                        at.getBlockY() + dy,
                        at.getBlockZ() + dz
                    ).setType(Material.AIR, false);
                }
            }
        }
    }

    public static boolean safe(Location at, int height) {
        if (at == null || at.getWorld() == null || height < 1) {
            return false;
        }

        World world = at.getWorld();

        if (!Double.isFinite(at.getX())
                || !Double.isFinite(at.getY())
                || !Double.isFinite(at.getZ())
                || at.getY() <= world.getMinHeight()
                || at.getY() >= world.getMaxHeight() - height) {
            return false;
        }

        Material ground = at.clone()
            .add(0, -1, 0)
            .getBlock()
            .getType();

        if (!ground.isSolid()
                || ground == Material.MAGMA_BLOCK
                || ground == Material.CAMPFIRE
                || ground == Material.SOUL_CAMPFIRE
                || ground == Material.CACTUS) {
            return false;
        }

        for (int y = 0; y < height; y++) {
            Block block = at.clone().add(0, y, 0).getBlock();

            if (!block.isPassable()
                    || block.isLiquid()
                    || block.getType() == Material.FIRE
                    || block.getType() == Material.SOUL_FIRE
                    || block.getType() == Material.POWDER_SNOW) {
                return false;
            }
        }

        return true;
    }

    private boolean ownEffect(PotionEffect effect) {
        return effect != null
            && effect.getAmplifier() == 0
            && effect.isAmbient()
            && !effect.hasParticles()
            && !effect.hasIcon()
            && effect.getDuration() <= 100;
    }

    private void applyEffect(
            Player player,
            PotionEffectType type,
            Set<UUID> owned) {

        PotionEffect current = player.getPotionEffect(type);
        UUID id = player.getUniqueId();

        if (current == null
                || (owned.contains(id) && ownEffect(current))) {

            player.addPotionEffect(
                new PotionEffect(
                    type,
                    100,
                    0,
                    true,
                    false,
                    false
                )
            );

            owned.add(id);
        } else {
            owned.remove(id);
        }
    }

    private void removeEffect(
            Player player,
            PotionEffectType type,
            Set<UUID> owned) {

        if (owned.remove(player.getUniqueId())
                && ownEffect(player.getPotionEffect(type))) {
            player.removePotionEffect(type);
        }
    }

    private void clean(Player player) {
        removeEffect(
            player,
            PotionEffectType.NIGHT_VISION,
            vision
        );

        removeEffect(
            player,
            PotionEffectType.DARKNESS,
            darkness
        );
    }

    private void updateAtmosphere() {
        forest.setTime(6000);
        forest.setStorm(false);
        forest.setThundering(false);

        for (Player player : getServer().getOnlinePlayers()) {
            if (!player.getWorld().equals(forest)) {
                clean(player);
                continue;
            }

            applyEffect(
                player,
                PotionEffectType.NIGHT_VISION,
                vision
            );

            if (getConfig().getBoolean("forest.darkness")) {
                applyEffect(
                    player,
                    PotionEffectType.DARKNESS,
                    darkness
                );
            } else {
                removeEffect(
                    player,
                    PotionEffectType.DARKNESS,
                    darkness
                );
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBed(PlayerBedEnterEvent event) {
        if (event.getPlayer().getWorld().equals(forest)) {
            event.setCancelled(true);

            event.getPlayer().sendMessage(
                "В Бледном лесу невозможно уснуть."
            );
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (event.getFrom().getWorld().equals(forest)) {
            event.setCancelled(true);

            event.getPlayer().sendMessage(
                "Порталы здесь не работают. "
                + "Для выхода: /paleportal return"
            );
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent event) {
        if (event.getWorld().equals(forest)
                && event.toWeatherState()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onThunder(ThunderChangeEvent event) {
        if (event.getWorld().equals(forest)
                && event.toThunderState()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        clean(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        clean(player);
        corners.remove(player.getUniqueId());
        musicSelection.remove(player.getUniqueId());
    }

    private void stop(boolean removeVisual) {
        HandlerList.unregisterAll(
            (org.bukkit.plugin.Plugin) this
        );

        if (atmosphere != null) {
            atmosphere.cancel();
            atmosphere = null;
        }

        if (portal != null) {
            portal.close(removeVisual);
            portal = null;
        }

        if (mobs != null) {
            mobs.close();
            mobs = null;
        }

        for (Player player : getServer().getOnlinePlayers()) {
            clean(player);
        }

        corners.clear();
        musicSelection.clear();
    }

    @Override
    public void onDisable() {
        stop(false);

        if (data != null) {
            data.save();
        }
    }

    private void reloadPlugin(CommandSender sender) {
        stop(true);
        reloadConfig();

        try {
            start();

            sender.sendMessage(
                "Конфигурация PalePortal перезагружена."
            );
        } catch (Exception exception) {
            sender.sendMessage(
                "Не удалось применить конфигурацию: "
                + exception.getMessage()
            );

            getLogger().log(
                java.util.logging.Level.SEVERE,
                "Ошибка перезагрузки PalePortal",
                exception
            );

            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private Block target(Player player, Material material) {
        Block block = player.getTargetBlockExact(8);

        if (block == null || block.getType() != material) {
            throw new IllegalArgumentException(
                "Посмотрите на нужный блок "
                + "с расстояния до 8 блоков."
            );
        }

        return block;
    }

    private void setPortal(Player player) {
        if (player.getWorld().equals(forest)) {
            throw new IllegalArgumentException(
                "Рамка должна находиться вне Бледного леса."
            );
        }

        Block block = target(
            player,
            Material.REINFORCED_DEEPSLATE
        );

        UUID id = player.getUniqueId();
        Corner first = corners.get(id);

        if (first == null) {
            corners.put(
                id,
                new Corner(
                    player.getWorld().getUID(),
                    Pos.of(block)
                )
            );

            player.sendMessage(
                "Первый угол сохранён. "
                + "Выберите противоположный внешний угол "
                + "и повторите команду."
            );

            return;
        }

        if (!first.world().equals(
                player.getWorld().getUID())) {

            corners.remove(id);

            throw new IllegalArgumentException(
                "Углы должны находиться в одном мире. "
                + "Начните выбор заново."
            );
        }

        Pos second = Pos.of(block);
        Pos a = first.position();

        boolean fixedX = a.x() == second.x();
        boolean fixedZ = a.z() == second.z();

        if (fixedX == fixedZ
                || Math.abs((long) a.y() - second.y()) < 2) {

            throw new IllegalArgumentException(
                "Нужны противоположные углы "
                + "одной вертикальной плоскости."
            );
        }

        getConfig().set("frame.enabled", true);
        // Выбор двух углов только настраивает геометрию и никогда
        // не должен оставлять портал открытым от предыдущей конфигурации.
        data.yaml().set("portal.unlocked", false);
        data.yaml().set("portal.intact", false);
        data.save();
        getConfig().set(
            "frame.world",
            player.getWorld().getName()
        );
        getConfig().set("frame.min", a.key());
        getConfig().set("frame.max", second.key());

        getConfig().set("music.blocks", List.of());
        saveConfig();

        corners.remove(id);
        reloadPlugin(player);
    }

    private void setMusic(Player player) {
        if (!getConfig().getBoolean("frame.enabled")) {
            throw new IllegalArgumentException(
                "Сначала настройте рамку."
            );
        }

        Location center = settings.frameCenter();
        Block block = target(player, Material.NOTE_BLOCK);

        if (center == null
                || !Objects.equals(
                    center.getWorld(),
                    player.getWorld()
                )) {

            throw new IllegalArgumentException(
                "Музыкальные блоки должны быть в мире рамки."
            );
        }

        Pos position = Pos.of(block);
        double radius = getConfig().getDouble("music.radius");

        if (position.center(player.getWorld())
                .distanceSquared(center) > radius * radius) {

            throw new IllegalArgumentException(
                "Этот блок находится вне радиуса "
                + "от центра рамки."
            );
        }

        List<Pos> selected = musicSelection.computeIfAbsent(
            player.getUniqueId(),
            ignored -> new ArrayList<>()
        );

        if (selected.contains(position)) {
            throw new IllegalArgumentException(
                "Этот блок уже выбран."
            );
        }

        int index = selected.size();

        NoteBlock noteBlock = (NoteBlock) block.getBlockData();
        noteBlock.setNote(
            new Note(settings.notes().get(index))
        );
        block.setBlockData(noteBlock, false);

        selected.add(position);

        player.sendMessage(
            "Музыкальный блок "
            + selected.size()
            + " из 5 сохранён."
        );

        if (selected.size() == 5) {
            getConfig().set(
                "music.blocks",
                selected.stream().map(Pos::key).toList()
            );

            saveConfig();
            musicSelection.remove(player.getUniqueId());
            reloadPlugin(player);
        }
    }

    private void returnPlayer(Player player) {
        if (!player.getWorld().equals(forest)) {
            throw new IllegalArgumentException(
                "Эта команда доступна в Бледном лесу."
            );
        }

        Location back = data.yaml().getLocation(
            "returns." + player.getUniqueId()
        );

        if (!safe(back, 2)
                || Objects.equals(back.getWorld(), forest)) {

            World source = settings.sourceWorld();

            if (source == null || source.equals(forest)) {
                source = getServer().getWorlds().stream()
                    .filter(world -> !world.equals(forest))
                    .findFirst()
                    .orElse(null);
            }

            back = source == null
                ? null
                : source.getSpawnLocation();
        }

        if (!safe(back, 2)) {
            throw new IllegalArgumentException(
                "Безопасная точка возврата не найдена. "
                + "Администратор должен настроить "
                + "спавн исходного мира."
            );
        }

        if (player.teleport(back)) {
            player.setFallDistance(0);
        }
    }

    private void spawnCreaking(Player player, String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                "Использование: /paleportal spawn "
                + "<normal|smart|kind|ancient>"
            );
        }

        if (!player.getWorld().equals(forest)) {
            throw new IllegalArgumentException(
                "Используйте команду внутри Бледного леса."
            );
        }

        CreakingManager.Kind kind =
            CreakingManager.Kind.parse(args[1]);

        Location spawnAt = player.getLocation().clone();
        Vector forward = spawnAt.getDirection().setY(0);

        if (forward.lengthSquared() > 0.001) {
            spawnAt.add(forward.normalize().multiply(3));
        }

        spawnAt.setX(spawnAt.getBlockX() + 0.5);
        spawnAt.setZ(spawnAt.getBlockZ() + 0.5);

        Location safeSpawn = null;

        for (int offset : new int[]{0, 1, -1, 2, -2}) {
            Location candidate = spawnAt.clone()
                .add(0, offset, 0);

            if (safe(candidate, 3)) {
                safeSpawn = candidate;
                break;
            }
        }

        if (safeSpawn == null) {
            throw new IllegalArgumentException(
                "Рядом нет безопасного места для скрипуна. "
                + "Перейдите на открытую ровную площадку."
            );
        }

        mobs.spawn(kind, safeSpawn);

        String title = switch (kind) {
            case NORMAL -> "обычный скрипун";
            case SMART -> "умный скрипун";
            case KIND -> "добрый скрипун";
            case ANCIENT -> "древний скрипун";
        };

        player.sendMessage("Создан: " + title + ".");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(
            "/paleportal return — выйти из Бледного леса"
        );

        if (!sender.hasPermission("paleportal.admin")) {
            return;
        }

        sender.sendMessage(
            "/paleportal reload — применить конфигурацию"
        );
        sender.sendMessage(
            "/paleportal setportal — выбрать угол рамки"
        );
        sender.sendMessage(
            "/paleportal setmusic — выбрать блок мелодии"
        );
        sender.sendMessage(
            "/paleportal cancel — сбросить выбор"
        );
        sender.sendMessage(
            "/paleportal spawn <normal|smart|kind|ancient>"
        );
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args) {

        String sub = args.length == 0
            ? "help"
            : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("help")) {
            sendHelp(sender);
            return true;
        }

        try {
            if (sub.equals("return")) {
                if (!(sender instanceof Player player)) {
                    throw new IllegalArgumentException(
                        "Команда доступна только игроку."
                    );
                }

                returnPlayer(player);
                return true;
            }

            if (!sender.hasPermission("paleportal.admin")) {
                throw new IllegalArgumentException(
                    "Недостаточно прав."
                );
            }

            if (sub.equals("reload")) {
                reloadPlugin(sender);
                return true;
            }

            if (!(sender instanceof Player player)) {
                throw new IllegalArgumentException(
                    "Команда доступна только игроку."
                );
            }

            switch (sub) {
                case "setportal" -> setPortal(player);

                case "setmusic" -> setMusic(player);

                case "cancel" -> {
                    corners.remove(player.getUniqueId());
                    musicSelection.remove(player.getUniqueId());

                    player.sendMessage("Выбор сброшен.");
                }

                case "spawn" -> spawnCreaking(player, args);

                default -> player.sendMessage(
                    "Неизвестная команда. "
                    + "Справка: /paleportal help"
                );
            }
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(
                exception.getMessage() == null
                    ? "Некорректные параметры команды."
                    : exception.getMessage()
            );
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args) {

        if (args.length == 1) {
            List<String> options = new ArrayList<>(
                List.of("help", "return")
            );

            if (sender.hasPermission("paleportal.admin")) {
                options.addAll(
                    List.of(
                        "reload",
                        "setportal",
                        "setmusic",
                        "cancel",
                        "spawn"
                    )
                );
            }

            String prefix = args[0].toLowerCase(Locale.ROOT);

            return options.stream()
                .filter(option -> option.startsWith(prefix))
                .toList();
        }

        if (args.length == 2
                && args[0].equalsIgnoreCase("spawn")
                && sender.hasPermission("paleportal.admin")) {

            String prefix = args[1].toLowerCase(Locale.ROOT);

            return List.of(
                "normal",
                "smart",
                "kind",
                "ancient"
            ).stream()
                .filter(option -> option.startsWith(prefix))
                .toList();
        }

        return List.of();
    }
}
