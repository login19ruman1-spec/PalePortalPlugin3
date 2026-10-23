# PalePortal 1.0.1 — melody and teleport fix

Paper 1.21.4 plugin source.

## Fixes in this revision
- The configured note blocks are handled directly from the player's left-click. The previous implementation depended on a synthetic nested `NotePlayEvent` and temporary author state, which could fail to advance the melody.
- Correct note-block clicks now report progress (`Мелодия: N/5`); wrong block/order/pitch resets the attempt with a message.
- Portal crossing detection accounts for the player's width around the center plane, reducing missed teleports when movement skips the exact plane coordinate.
- The existing unlock effects/particles and configured `forest.entry` destination remain in place.

## Build
Run from the project root:
```sh
mvn --batch-mode clean package
```
Install `target/PalePortal-1.0.1.jar` and fully restart Paper.

## Configure and use
- Enable and configure `frame` corners in `plugins/PalePortal/config.yml`.
- Configure five `music.blocks` coordinates in the intended playing order.
- Configure five note IDs in `music.notes`.
- All five note blocks must be within `music.radius` (default 30 blocks, 3D distance from frame center).
- Execute `/paleportal setmusic` for each block in the desired order if using the in-game selection flow.
- Left-click the five configured note blocks in order. The player should see `Мелодия: 1/5` through `5/5`; after the fifth correct note, the plugin unlocks the portal and shows effects.
- Walk through the opening to teleport to `forest.entry`. `/paleportal forest — телепорт в Бледный лес
/paleportal return` returns to the saved location.

Note: Maven is not installed in the environment used to prepare this archive, so the project has not been compiled here.
