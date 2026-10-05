package dev.vox.lss.sponge;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * The Sponge twin of {@code dev.vox.lss.platform.SectionConstruction} (V-2/S2): the ONLY
 * main-source site invoking a {@link LevelChunkSection} constructor in the sponge module.
 * Sponge runs vanilla's section ctor, which accepts the read-only biome view like the
 * Fabric twin — unlike Paper's, which needs the mutable container. See the xplat twin's
 * javadoc for the per-line ctor churn.
 */
final class SpongeSectionConstruction {
    private SpongeSectionConstruction() {}

    /** An all-air section with default biomes (vanilla's empty-section fill). */
    static LevelChunkSection empty(PalettedContainerFactory factory) {
        return new LevelChunkSection(factory);
    }

    /**
     * A section from parsed containers (the NBT serializer's object path). Vanilla's
     * biome codec yields a read-only container; it must be kept as is — substituting
     * default biomes would ship the wrong biome data.
     */
    static LevelChunkSection fromContainers(PalettedContainer<BlockState> states,
                                            PalettedContainerRO<Holder<Biome>> biomes,
                                            PalettedContainerFactory factory) {
        return new LevelChunkSection(states, biomes);
    }

    /**
     * A section reusing {@code biomes} BY REFERENCE with replaced states (the mask
     * filter's rebuild). Never a default-biome fallback here: a masked section must keep
     * its real biomes, so a non-mutable container is a loud failure, not a rebuild.
     */
    static LevelChunkSection withStates(PalettedContainer<BlockState> states,
                                        PalettedContainerRO<Holder<Biome>> biomes) {
        if (!(biomes instanceof PalettedContainer<Holder<Biome>> biomeContainer)) {
            throw new IllegalStateException("section biomes are not a mutable PalettedContainer");
        }
        return new LevelChunkSection(states, biomeContainer);
    }

    /** The section's biome container (the read side the mask filter narrows). */
    static PalettedContainerRO<Holder<Biome>> biomes(LevelChunkSection section) {
        return section.getBiomes();
    }
}
