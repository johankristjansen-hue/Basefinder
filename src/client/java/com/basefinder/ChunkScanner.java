package com.basefinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Walks the blocks of a chunk the server already sent us and adds up their weights. */
final class ChunkScanner {
	/** At most this many block positions are remembered per chunk (for the highlight). */
	static final int MAX_POSITIONS = 100;

	private ChunkScanner() {
	}

	/** minY is the lowest block height of the dimension (-64 in the overworld, 0 elsewhere). */
	static ChunkScore scan(LevelChunk chunk, int minY) {
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();

		Map<Block, int[]> counts = new IdentityHashMap<>();
		int[] positions = new int[MAX_POSITIONS * 3];
		int found = 0;
		int points = 0;
		int strong = 0;

		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section == null || section.hasOnlyAir()) {
				continue;
			}
			// Cheap palette check first: skip the 4096-block loop if nothing interesting is in here.
			if (!section.maybeHas(state -> BlockWeights.weight(state.getBlock()) > 0)) {
				continue;
			}

			int baseY = minY + i * 16;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						Block block = section.getBlockState(x, y, z).getBlock();
						int weight = BlockWeights.weight(block);
						if (weight <= 0) {
							continue;
						}
						points += weight;
						if (weight >= BlockWeights.STRONG) {
							strong++;
						}
						counts.computeIfAbsent(block, b -> new int[1])[0]++;

						if (found < MAX_POSITIONS) {
							positions[found * 3] = minX + x;
							positions[found * 3 + 1] = baseY + y;
							positions[found * 3 + 2] = minZ + z;
							found++;
						}
					}
				}
			}
		}

		if (points == 0) {
			return ChunkScore.EMPTY;
		}
		return new ChunkScore(points, strong, summarise(counts), Arrays.copyOf(positions, found * 3));
	}

	/** "12 chest, 3 shulker_box, 40 oak_planks" - the three biggest contributors. */
	private static String summarise(Map<Block, int[]> counts) {
		List<Map.Entry<Block, int[]>> entries = new ArrayList<>(counts.entrySet());
		entries.sort((a, b) -> Integer.compare(
				b.getValue()[0] * BlockWeights.weight(b.getKey()),
				a.getValue()[0] * BlockWeights.weight(a.getKey())));

		StringBuilder out = new StringBuilder();
		for (int i = 0; i < Math.min(3, entries.size()); i++) {
			if (i > 0) {
				out.append(", ");
			}
			Map.Entry<Block, int[]> entry = entries.get(i);
			out.append(entry.getValue()[0]).append(' ').append(BlockWeights.idOf(entry.getKey()));
		}
		return out.toString();
	}
}
