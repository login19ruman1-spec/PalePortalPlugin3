package ru.paleforest;

import java.util.List;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

/** Uses vanilla terrain generation while restricting the world to Pale Garden biome. */
public final class PaleGardenBiomeProvider extends BiomeProvider {
    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        return Biome.PALE_GARDEN;
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
        return List.of(Biome.PALE_GARDEN);
    }
}
