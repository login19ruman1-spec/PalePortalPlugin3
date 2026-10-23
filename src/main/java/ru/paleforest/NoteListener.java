package ru.paleforest;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.*;
import ru.paleforest.ConfigManager.Pos;

/**
 * Handles the five-note portal melody directly from the player's left-click.
 * We deliberately do not synthesize NotePlayEvent: relying on a nested event
 * to preserve the author can be fragile with other plugins/listeners.
 */
public final class NoteListener implements Listener {
    private record Attempt(List<Integer> played, long started) {}

    private final PalePortalPlugin plugin;
    private final Map<UUID, Attempt> attempts = new HashMap<>();

    public NoteListener(PalePortalPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if ((event.getAction() != Action.LEFT_CLICK_BLOCK
                && event.getAction() != Action.RIGHT_CLICK_BLOCK)
            || event.getClickedBlock() == null) return;

        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        ConfigManager config = plugin.settings();

        if (!block.getWorld().equals(config.sourceWorld())
            || !config.musicBlocks().contains(Pos.of(block))
            || !(block.getBlockData() instanceof NoteBlock noteBlock)) {
            return;
        }

        // The configured note block is played by this handler, consistently,
        // even if another plugin would otherwise interfere with vanilla clicks.
        event.setCancelled(true);
        int note = noteBlock.getNote().getId();
        float pitch = (float) Math.pow(2, (note - 12) / 12.0);
        block.getWorld().playSound(
            block.getLocation().add(0.5, 0.5, 0.5),
            Sound.BLOCK_NOTE_BLOCK_HARP, 1, pitch
        );

        UUID id = player.getUniqueId();
        if (!plugin.portal().ready()) {
            attempts.remove(id);
            opMessage(player, "§cПортал пока не готов: рамка из укреплённого глубинного сланца не распознана целиком.");
            plugin.getLogger().warning("Попытка сыграть мелодию, но рамка не готова. Игрок=" + player.getName() + ", блок=" + Pos.of(block).key());
            return;
        }

        List<Pos> blocks = config.musicBlocks();
        List<Integer> notes = config.notes();
        if (blocks.size() != 5 || new HashSet<>(blocks).size() != 5
            || notes.size() != 5) {
            attempts.remove(id);
            opMessage(player, "Настройте пять разных музыкальных блоков и пять нот.");
            return;
        }

        Location center = config.frameCenter();
        double radius = config.raw().getDouble("music.radius", 30.0);
        if (center == null
            || block.getLocation().add(0.5, 0.5, 0.5).distanceSquared(center)
                > radius * radius) {
            fail(player, "Музыкальный блок дальше допустимого расстояния от портала.");
            return;
        }

        long now = System.nanoTime();
        long timeout = config.raw().getLong("music.timeout-seconds", 30L)
            * 1_000_000_000L;
        Attempt attempt = attempts.get(id);

        if (attempt != null && now - attempt.started() > timeout) {
            attempts.remove(id);
            attempt = null;
            opMessage(player, "Время мелодии истекло. Начните заново.");
        }

        if (attempt == null) {
            attempt = new Attempt(new ArrayList<>(), now);
            attempts.put(id, attempt);
        }

        int index = attempt.played().size();
        if (index >= 5 || !blocks.get(index).equals(Pos.of(block))
            || notes.get(index) != note) {
            String expected = index < 5
                ? "Ожидается блок №" + (index + 1) + " с нотой " + notes.get(index)
                : "Начните мелодию заново";
            fail(player, "Неверная нота или порядок. " + expected + ". Мелодия сброшена.");
            plugin.getLogger().info("Мелодия сброшена: игрок=" + player.getName()
                + ", индекс=" + index + ", блок=" + Pos.of(block).key()
                + ", нота=" + note);
            return;
        }

        attempt.played().add(note);
        int progress = attempt.played().size();
        opMessage(player, "§7Мелодия: §d" + progress + "/5 §8(6 → 10 → 13 → 10 → 6)");

        if (progress == 5) {
            attempts.remove(id);
            opMessage(player, "§dМелодия сыграна верно! Портал активируется...");
            plugin.portal().unlock(player);
        }
    }

    private void fail(Player player, String message) {
        attempts.remove(player.getUniqueId());
        player.playSound(
            player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f
        );
        opMessage(player, message);
    }

    private void opMessage(Player player, String message) {
        if (player.isOp() || player.hasPermission("paleportal.admin")) {
            player.sendMessage(message);
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player)) continue;
            if ((viewer.isOp() || viewer.hasPermission("paleportal.admin"))
                    && viewer.getWorld().equals(player.getWorld())) {
                viewer.sendMessage("§8[§5PalePortal§8] §r" + player.getName() + ": " + message);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE
            || event.getPlayer().isSneaking()) return;

        if (event.getBlock().getWorld().equals(
                plugin.settings().sourceWorld())
            && plugin.settings().musicBlocks().contains(
                Pos.of(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        attempts.remove(event.getPlayer().getUniqueId());
    }
}
