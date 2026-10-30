package com.basefinder;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/**
 * How "base-like" each block is. Blocks are matched by their registry id (e.g. "observer",
 * "red_concrete") so this keeps working even if Mojang renames Java classes between versions.
 *
 * Only blocks that natural world generation (villages, mineshafts, trial chambers, ...) does not
 * create in bulk are counted. Everyday blocks like chests, ender chests, crafting tables, furnaces,
 * beds, planks, torches and glass are ignored on purpose.
 *
 * Weight 0  = ignored
 * Weight 3+ = counted
 * Weight 8+ = "strong" (the best signs of a base)
 */
final class BlockWeights {
	/** Weight at or above this counts as a "strong" signal. */
	static final int STRONG = 8;

	private static final Map<Block, Integer> CACHE = new IdentityHashMap<>();

	private BlockWeights() {
	}

	static int weight(Block block) {
		Integer cached = CACHE.get(block);
		if (cached != null) {
			return cached;
		}
		String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
		int weight = compute(id);
		CACHE.put(block, weight);
		return weight;
	}

	static String idOf(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).getPath();
	}

	private static int compute(String id) {
		// --- the strongest signs of a base ------------------------------------------------
		if (id.contains("shulker_box") || id.equals("beacon") || id.equals("conduit")) {
			return 10;
		}
		if (id.equals("observer")) {
			return 8;
		}

		// --- redstone machines and similar ------------------------------------------------
		if (id.equals("hopper")
				|| id.equals("dropper")
				|| id.equals("comparator")
				|| id.equals("lodestone")
				|| id.equals("respawn_anchor")) {
			return 6;
		}

		// --- weaker signs, only matter in large numbers -----------------------------------
		if (id.equals("piston")
				|| id.equals("sticky_piston")
				|| id.contains("anvil")
				|| id.endsWith("_concrete")) {
			return 3;
		}

		return 0;
	}
}
