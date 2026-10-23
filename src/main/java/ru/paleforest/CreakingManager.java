package ru.paleforest;

import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public final class CreakingManager implements Listener {
    public enum Kind {
        NORMAL("Обычный скрипун"),
        SMART("Умный скрипун"),
        KIND("Добрый скрипун"),
        ANCIENT("Древний скрипун");

        final String title;

        Kind(String title) {
            this.title = title;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "normal", "обычный" -> NORMAL;
                case "smart", "умный" -> SMART;
                case "kind", "добрый" -> KIND;
                case "ancient", "древний", "boss" -> ANCIENT;
                default -> throw new IllegalArgumentException(
                    "Типы: normal, smart, kind, ancient"
                );
            };
        }
    }

    private final PalePortalPlugin plugin;
    private final NamespacedKey key;
    private final Random random = new Random();

    private final Map<UUID, Long> attacks = new HashMap<>();
    private final Map<UUID, UUID> frightenedBy = new HashMap<>();
    private final Map<UUID, Long> frightenedUntil = new HashMap<>();
    private final Map<UUID, Location> lastLocations = new HashMap<>();

    private final BukkitTask brain;
    private final BukkitTask spawner;
    private long ticks;

    public CreakingManager(PalePortalPlugin plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "creaking_type");

        brain = Bukkit.getScheduler().runTaskTimer(
            plugin, this::tick, 5, 5
        );
        spawner = Bukkit.getScheduler().runTaskTimer(
            plugin, this::spawnCycle,
            plugin.getConfig().getLong("mobs.spawn-period-seconds") * 20,
            plugin.getConfig().getLong("mobs.spawn-period-seconds") * 20
        );
    }

    private Kind kind(Entity entity) {
        if (!(entity instanceof Creaking)) return null;

        String value = entity.getPersistentDataContainer().get(
            key, PersistentDataType.STRING
        );
        if (value == null) return null;

        try {
            return Kind.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private double setting(Kind kind, String field) {
        return plugin.getConfig().getDouble(
            "mobs." + kind.key() + "." + field
        );
    }

    public Creaking spawn(Kind kind, Location location) {
        Creaking entity = (Creaking) location.getWorld().spawnEntity(
            location, EntityType.CREAKING
        );

        entity.getPersistentDataContainer().set(
            key, PersistentDataType.STRING, kind.name()
        );
        entity.setPersistent(true);
        entity.setRemoveWhenFarAway(false);
        // Keep all Creaking nametags completely hidden (including custom labels).
        entity.setCustomName(null);
        entity.setCustomNameVisible(false);

        // NORMAL оставляет ванильную механику взгляда.
        // Остальными управляет только этот плагин.
        entity.setAI(kind == Kind.NORMAL);

        var health = entity.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) health.setBaseValue(setting(kind, "health"));
        entity.setHealth(setting(kind, "health"));

        var damage = entity.getAttribute(Attribute.ATTACK_DAMAGE);
        if (damage != null) {
            damage.setBaseValue(
                kind == Kind.KIND ? 0 : setting(kind, "damage")
            );
        }

        var speed = entity.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(setting(kind, "speed"));

        return entity;
    }

    private List<Player> targets(Creaking entity, double radius) {
        double squared = radius * radius;
        return entity.getWorld().getPlayers().stream()
            .filter(player -> !player.isDead()
                && (player.getGameMode() == GameMode.SURVIVAL
                    || player.getGameMode() == GameMode.ADVENTURE))
            .filter(player -> player.getLocation().distanceSquared(
                entity.getLocation()) <= squared)
            .sorted(Comparator.comparingDouble(player ->
                player.getLocation().distanceSquared(entity.getLocation())
            ))
            .toList();
    }

    private boolean clearBody(Location at) {
        if (!PalePortalPlugin.safe(at, 3)) return false;

        // Запас по ширине тела: не проходим через углы стен.
        for (double dx : new double[]{-0.45, 0.45}) {
            for (double dz : new double[]{-0.45, 0.45}) {
                for (int dy = 0; dy < 3; dy++) {
                    var block = at.clone().add(dx, dy, dz).getBlock();
                    if (!block.isPassable() || block.isLiquid()) return false;
                }
            }
        }
        return true;
    }

    private void move(Creaking entity, Vector direction, double speed) {
        direction.setY(0);
        if (speed <= 0 || direction.lengthSquared() < 0.01) return;

        // За пять тиков проходим speed * 5 блоков.
        double remaining = Math.min(speed * 5, direction.length());
        Vector unit = direction.normalize();
        Location at = entity.getLocation();

        // Проверяем весь путь, а не только конечную точку.
        while (remaining > 0.001) {
            double step = Math.min(0.2, remaining);
            Location next = at.clone().add(unit.clone().multiply(step));
            boolean found = false;

            for (int dy : new int[]{0, 1, -1}) {
                Location candidate = next.clone().add(0, dy, 0);
                if (clearBody(candidate)) {
                    at = candidate;
                    found = true;
                    break;
                }
            }

            if (!found) break;
            remaining -= step;
        }

        at.setDirection(unit);
        entity.teleport(at);
    }

    private void tick() {
        ticks += 5;
        Set<UUID> alive = new HashSet<>();

        for (Entity raw : plugin.forest().getEntities()) {
            Kind kind = kind(raw);
            if (kind == null || raw.isDead()) continue;

            Creaking entity = (Creaking) raw;
            UUID id = entity.getUniqueId();
            alive.add(id);

            Location particleAt = entity.getLocation().add(0, 1, 0);
            World world = entity.getWorld();

            world.spawnParticle(
                Particle.SOUL, particleAt, 1, 0.3, 0.5, 0.3, 0
            );
            switch (kind) {
                case SMART -> world.spawnParticle(
                    Particle.DUST, particleAt, 2, 0.3, 0.5, 0.3, 0,
                    new Particle.DustOptions(Color.RED, 1)
                );
                case KIND -> world.spawnParticle(
                    Particle.HAPPY_VILLAGER, particleAt,
                    2, 0.3, 0.5, 0.3, 0
                );
                case ANCIENT -> world.spawnParticle(
                    Particle.PORTAL, particleAt,
                    3, 0.4, 0.6, 0.4, 0.02
                );
                default -> { }
            }

            if (ticks % 40 == 0) {
                Location previous = lastLocations.put(id, entity.getLocation());
                if (previous != null
                    && previous.distanceSquared(entity.getLocation()) > 0.01) {
                    world.playSound(
                        entity.getLocation(),
                        Sound.ENTITY_CREAKING_AMBIENT, 0.5f, 0.9f
                    );
                }
            }

            if (kind == Kind.NORMAL) continue;

            if (kind == Kind.KIND) {
                UUID attacker = frightenedBy.get(id);
                Player player = attacker == null
                    ? null : Bukkit.getPlayer(attacker);

                if (player != null && player.getWorld().equals(world)
                    && ticks < frightenedUntil.getOrDefault(id, 0L)) {
                    move(
                        entity,
                        entity.getLocation().toVector().subtract(
                            player.getLocation().toVector()
                        ),
                        setting(kind, "speed")
                    );
                } else {
                    frightenedBy.remove(id);
                    frightenedUntil.remove(id);
                }
                continue;
            }

            List<Player> nearby = targets(entity, 32);
            if (nearby.isEmpty()) continue;

            Player target = nearby.getFirst();
            if (target.getLocation().distanceSquared(
                    entity.getLocation()) > 2.5) {
                move(
                    entity,
                    target.getLocation().toVector().subtract(
                        entity.getLocation().toVector()
                    ),
                    setting(kind, "speed")
                );
            }

            if (ticks - attacks.getOrDefault(id, -100L) < 20) continue;

            if (kind == Kind.ANCIENT) {
                List<Player> victims = targets(
                    entity, setting(kind, "attack-radius")
                );
                if (!victims.isEmpty()) {
                    attacks.put(id, ticks);
                    for (Player victim : victims) {
                        victim.damage(setting(kind, "damage"), entity);
                    }
                }
            } else if (target.getLocation().distanceSquared(
                    entity.getLocation()) <= 4
                && entity.hasLineOfSight(target)) {
                attacks.put(id, ticks);
                target.damage(setting(kind, "damage"), entity);
            }
        }

        attacks.keySet().retainAll(alive);
        frightenedBy.keySet().retainAll(alive);
        frightenedUntil.keySet().retainAll(alive);
        lastLocations.keySet().retainAll(alive);
    }

    private void spawnCycle() {
        var config = plugin.getConfig();
        World world = plugin.forest();

        if (!config.getBoolean("mobs.enabled")
            || world.getPlayers().isEmpty()) return;

        long loaded = world.getEntities().stream()
            .filter(entity -> kind(entity) != null).count();

        int limit = Math.max(0, config.getInt("mobs.max-loaded"));
        int count = Math.min(
            Math.max(0, config.getInt("mobs.per-cycle")),
            (int) Math.max(0, limit - loaded)
        );

        Location entry = plugin.settings().entry(world);
        double radius = config.getDouble("mobs.spawn-radius");
        double minimum = Math.max(
            0, config.getDouble("mobs.minimum-player-distance")
        );

        for (int i = 0; i < count; i++) {
            for (int attempt = 0; attempt < 40; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double r = Math.sqrt(random.nextDouble()) * radius;

                int x = entry.getBlockX()
                    + (int) Math.round(Math.cos(angle) * r);
                int z = entry.getBlockZ()
                    + (int) Math.round(Math.sin(angle) * r);

                // Не загружаем далёкие чанки ради спавна.
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

                int y = world.getHighestBlockYAt(
                    x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES
                ) + 1;
                Location at = new Location(world, x + 0.5, y, z + 0.5);

                if (!clearBody(at)) continue;

                boolean close = world.getPlayers().stream().anyMatch(
                    player -> player.getLocation().distanceSquared(at)
                        < minimum * minimum
                );
                if (close) continue;

                Kind[] variants = Kind.values();
                spawn(variants[random.nextInt(variants.length)], at);
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Kind attacking = kind(event.getDamager());
        if (attacking == Kind.KIND) {
            event.setCancelled(true);
        } else if (attacking != null) {
            event.setDamage(setting(attacking, "damage"));
        }

        if (kind(event.getEntity()) != Kind.KIND) return;

        Player player = null;
        if (event.getDamager() instanceof Player direct) {
            player = direct;
        } else if (event.getDamager() instanceof Projectile projectile
            && projectile.getShooter() instanceof Player shooter) {
            player = shooter;
        }

        if (player != null) {
            UUID id = event.getEntity().getUniqueId();
            frightenedBy.put(id, player.getUniqueId());
            frightenedUntil.put(id, ticks + 200);
        }
    }

    public void close() {
        brain.cancel();
        spawner.cancel();
    }
}
