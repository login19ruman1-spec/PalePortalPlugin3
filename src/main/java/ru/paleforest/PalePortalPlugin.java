                case "spawn" -> {
                    if (args.length != 2) {
                        throw new IllegalArgumentException(
                            "Usage: /paleportal spawn "
                            + "<normal|smart|kind|ancient>"
                        );
                    }

                    if (!player.getWorld().equals(forest)) {
                        throw new IllegalArgumentException(
                            "Use this command inside the Pale Forest."
                        );
                    }

                    CreakingManager.Kind kind =
                        CreakingManager.Kind.parse(args[1]);

                    Location spawnAt = player.getLocation().clone();
                    org.bukkit.util.Vector forward =
                        spawnAt.getDirection().setY(0);

                    if (forward.lengthSquared() > 0.001) {
                        spawnAt.add(forward.normalize().multiply(3));
                    }

                    spawnAt.setX(spawnAt.getBlockX() + 0.5);
                    spawnAt.setZ(spawnAt.getBlockZ() + 0.5);

                    Location safeSpawn = null;

                    for (int offset : new int[]{0, 1, -1, 2, -2}) {
                        Location candidate =
                            spawnAt.clone().add(0, offset, 0);

                        if (safe(candidate, 3)) {
                            safeSpawn = candidate;
                            break;
                        }
                    }

                    if (safeSpawn == null) {
                        throw new IllegalArgumentException(
                            "No safe spawn position nearby. "
                            + "Move to an open, level area."
                        );
                    }

                    mobs.spawn(kind, safeSpawn);
                    player.sendMessage(
                        "Spawned: "
                        + kind.name().toLowerCase(Locale.ROOT)
                    );
                }

                default -> player.sendMessage(
                    "Unknown subcommand. Use /paleportal help"
                );
            }
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(exception.getMessage());
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(
        CommandSender sender,
        Command command,
        String alias,
        String[] args
    ) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(
                List.of("help", "return")
            );

            if (sender.hasPermission("paleportal.admin")) {
                options.addAll(List.of(
                    "reload",
                    "setportal",
                    "setmusic",
                    "cancel",
                    "spawn"
                ));
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

            return List.of("normal", "smart", "kind", "ancient")
                .stream()
                .filter(option -> option.startsWith(prefix))
                .toList();
        }

        return List.of();
    }
}
