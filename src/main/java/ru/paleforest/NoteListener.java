package ru.paleforest;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import ru.paleforest.ConfigManager.Pos;

public final class NoteListener implements Listener {
    private record Attempt(List<Integer> played, long started) {}

    private final PalePortalPlugin plugin;
    private final Map<UUID, Attempt> attempts = new HashMap<>();

    // NotePlayEvent не содержит игрока.
    // Для управляемого проигрывания сохраняем точного автора.
    private Player author;
    private Block playedBlock;

    public NoteListener(PalePortalPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK
            || event.getClickedBlock() == null) return;

        Block block = event.getClickedBlock();
        ConfigManager config = plugin.settings();

        if (!block.getWorld().equals(config.sourceWorld())
            || !config.musicBlocks().contains(Pos.of(block))
            || !(block.getBlockData() instanceof NoteBlock noteBlock)) {
            return;
        }

        // Ванильное проигрывание заменяем своим, чтобы не угадывать
        // исполнителя по ближайшему игроку.
        event.setCancelled(true);

        NotePlayEvent noteEvent = new NotePlayEvent(
            block, noteBlock.getInstrument(), noteBlock.getNote()
        );

        author = event.getPlayer();
        playedBlock = block;
        try {
            Bukkit.getPluginManager().callEvent(noteEvent);

            if (!noteEvent.isCancelled()) {
                float pitch = (float) Math.pow(
                    2, (noteEvent.getNote().getId() - 12) / 12.0
                );
                block.getWorld().playSound(
                    block.getLocation().add(0.5, 0.5, 0.5),
                    Sound.BLOCK_NOTE_BLOCK_HARP, 1, pitch
                );
            }
        } finally {
            author = null;
            playedBlock = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNote(NotePlayEvent event) {
        // Красный камень не имеет игрока-исполнителя
        // и не может разблокировать портал.
        if (author == null || !event.getBlock().equals(playedBlock)) return;

        Player player = author;
        UUID id = player.getUniqueId();
        ConfigManager config = plugin.settings();

        if (!plugin.portal().ready()) {
            attempts.remove(id);
            player.sendMessage("Сначала восстановите рамку портала.");
            return;
        }

        List<Pos> blocks = config.musicBlocks();
        List<Integer> notes = config.notes();
        if (blocks.size() != 5 || new HashSet<>(blocks).size() != 5) {
            player.sendMessage("Пять музыкальных блоков ещё не настроены.");
            return;
        }

        Location center = config.frameCenter();
        double radius = config.raw().getDouble("music.radius");
        if (center == null
            || event.getBlock().getLocation().add(0.5, 0.5, 0.5)
                .distanceSquared(center) > radius * radius) {
            fail(player);
            return;
        }

        long now = System.nanoTime();
        long timeout = config.raw().getLong("music.timeout-seconds")
            * 1_000_000_000L;

        Attempt attempt = attempts.get(id);
        if (attempt != null && now - attempt.started() > timeout) {
            fail(player);
            attempt = null;
        }

        if (attempt == null) {
            attempt = new Attempt(new ArrayList<>(), now);
            attempts.put(id, attempt);
        }

        int index = attempt.played().size();
        int note = event.getNote().getId();

        if (!blocks.get(index).equals(Pos.of(event.getBlock()))
            || notes.get(index) != note) {
            fail(player);
            return;
        }

        attempt.played().add(note);
        if (attempt.played().size() == 5) {
            attempts.remove(id);
            plugin.portal().unlock(player);
        }
    }

    private void fail(Player player) {
        attempts.remove(player.getUniqueId());
        player.playSound(
            player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f
        );
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
