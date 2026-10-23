package ru.paleforest;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.generator.*;

public final class PaleWorldGenerator extends ChunkGenerator {
    private static double value(long seed, int x, int z) {
        long h = seed ^ (x * 341873128712L) ^ (z * 132897987541L);
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        return ((h ^ (h >>> 31)) >>> 11) * 0x1.0p-53;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double noise(long seed, double x, double z) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        double fx = smooth(x - ix);
        double fz = smooth(z - iz);

        double a = value(seed, ix, iz);
        double b = value(seed, ix + 1, iz);
        double c = value(seed, ix, iz + 1);
        double d = value(seed, ix + 1, iz + 1);

        return (a + (b - a) * fx) * (1 - fz)
            + (c + (d - c) * fx) * fz;
    }

    @Override
    public void generateNoise(
        WorldInfo world, Random random,
        int chunkX, int chunkZ, ChunkData data
    ) {
        int[][] ground = new int[16][16];

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wx = chunkX * 16 + x;
                int wz = chunkZ * 16 + z;

                int top = 64 + (int) Math.round(
                    noise(world.getSeed(), wx / 48.0, wz / 48.0) * 10
                    + noise(
                        world.getSeed() + 71, wx / 13.0, wz / 13.0
                    ) * 3
                );

                boolean pond = noise(
                    world.getSeed() + 991, wx / 22.0, wz / 22.0
                ) < 0.16;

                if (pond) top -= 3;
                ground[x][z] = top;

                data.setBlock(
                    x, data.getMinHeight(), z, Material.BEDROCK
                );
                data.setRegion(
                    x, data.getMinHeight() + 1, z,
                    x + 1, top - 2, z + 1, Material.STONE
                );
                data.setRegion(
                    x, top - 2, z, x + 1, top, z + 1, Material.DIRT
                );

                data.setBlock(
                    x, top, z,
                    pond ? Material.CLAY : Material.PALE_MOSS_BLOCK
                );

                if (pond) {
                    data.setBlock(x, top + 1, z, Material.WATER);
                    data.setBlock(x, top + 2, z, Material.WATER);
                } else if (random.nextDouble() < 0.15) {
                    data.setBlock(
                        x, top + 1, z, Material.PALE_MOSS_CARPET
                    );
                } else if (random.nextDouble() < 0.12) {
                    data.setBlock(x, top + 1, z, Material.SHORT_GRASS);
                }
            }
        }

        // Деревья целиком внутри чанка: без обращения к соседним чанкам.
        for (int x : new int[]{3, 11}) {
            for (int z : new int[]{3, 11}) {
                int y = ground[x][z];
                if (data.getType(x, y, z) != Material.PALE_MOSS_BLOCK
                    || random.nextDouble() > 0.8) {
                    continue;
                }

                int trunk = 5 + random.nextInt(3);
                for (int dy = 1; dy <= trunk; dy++) {
                    data.setBlock(x, y + dy, z, Material.PALE_OAK_LOG);
                }

                for (int dy = trunk - 2; dy <= trunk + 1; dy++) {
                    int radius = dy == trunk + 1 ? 1 : 2;

                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            if (radius == 2
                                && Math.abs(dx) == 2
                                && Math.abs(dz) == 2) continue;

                            if (data.getType(x + dx, y + dy, z + dz)
                                == Material.PALE_OAK_LOG) continue;

                            Leaves leaves = (Leaves)
                                Material.PALE_OAK_LEAVES.createBlockData();
                            leaves.setPersistent(true);
                            data.setBlock(
                                x + dx, y + dy, z + dz, leaves
                            );
                        }
                    }
                }
            }
        }
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo world) {
        return new BiomeProvider() {
            @Override
            public Biome getBiome(
                WorldInfo info, int x, int y, int z
            ) {
                return Biome.PALE_GARDEN;
            }

            @Override
            public List<Biome> getBiomes(WorldInfo info) {
                return List.of(Biome.PALE_GARDEN);
            }
        };
    }

    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateBedrock() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }
}
