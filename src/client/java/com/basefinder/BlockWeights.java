package com.basefinder;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/**
 * How "base-like" each block is. Blocks are matched by their registry id (e.g. "chest",
 * "red_concrete") so this keeps working even if Mojang renames Java classes between versions.
 *
 * Weight 0  = ignored
 * Weight 1-3 = common building / utility blocks (also show up in villages, mineshafts, ...)
 * Weight 8+ = "strong" blocks that natural world generation almost never creates in bulk
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
		// --- strong signals ---------------------------------------------------------------
		if (id.contains("shulker_box")
				|| id.equals("ender_chest")
				|| id.equals("beacon")
				|| id.equals("enchanting_table")
				|| id.contains("anvil")
				|| id.equals("hopper")
				|| id.equals("observer")
				|| id.equals("dropper")
				|| id.equals("comparator")
				|| id.equals("lodestone")
				|| id.equals("respawn_anchor")
				|| id.equals("conduit")
				|| id.endsWith("_concrete")
				|| id.endsWith("glazed_terracotta")
				|| id.contains("stained_glass")
				|| id.equals("tinted_glass")
				|| id.equals("diamond_block")
				|| id.equals("emerald_block")
				|| id.equals("netherite_block")) {
			return 10;
		}

		// --- medium signals ---------------------------------------------------------------
		if (id.equals("dispenser") || id.equals("piston") || id.equals("sticky_piston")) {
			return 5;
		}
		if (id.equals("brewing_stand") || id.equals("note_block") || id.equals("jukebox")) {
			return 4;
		}

		// --- common utility blocks --------------------------------------------------------
		if (id.equals("chest")
				|| id.equals("trapped_chest")
				|| id.equals("barrel")
				|| id.equals("furnace")
				|| id.equals("blast_furnace")
				|| id.equals("smoker")
				|| id.equals("crafting_table")
				|| id.endsWith("_bed")) {
			return 3;
		}
		if (id.endsWith("_sign") || id.contains("lantern")) {
			return 2;
		}

		// --- cheap building blocks (low weight, helps early-game bases) -------------------
		if (id.endsWith("_planks")
				|| id.endsWith("_door")
				|| id.equals("glass")
				|| id.equals("glass_pane")
				|| id.equals("cobblestone")
				|| id.equals("stone_bricks")
				|| id.equals("bricks")
				|| id.contains("torch")) {
			return 1;
		}

		return 0;
	}
}
