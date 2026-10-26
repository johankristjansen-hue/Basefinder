package com.basefinder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Client-side base finder. It only looks at chunks the server already sends to your client
 * (i.e. inside your render distance), scores them by how many player-made blocks they hold,
 * and tells you where the high scores are.
 */
public class BaseFinderClient implements ClientModInitializer {
	public static final String MOD_ID = "basefinder";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final String TAG = "[BaseFinder] ";
	private static final int MAX_QUEUE = 4000;
	/** Reports closer than this many chunks to an existing report are merged into it. */
	private static final int MERGE_RADIUS = 4;

	private record PendingChunk(String dimension, LevelChunk chunk) {
	}

	private static final class Report {
		final int chunkX;
		final int chunkZ;
		int score;

		Report(int chunkX, int chunkZ, int score) {
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.score = score;
		}
	}

	private static final class DimensionState {
		final Map<Long, ChunkScore> scores = new HashMap<>();
		final List<Report> reports = new ArrayList<>();
	}

	private final Deque<PendingChunk> queue = new ArrayDeque<>();
	private final Map<String, DimensionState> dimensions = new HashMap<>();
	private BaseFinderConfig config;

	@Override
	public void onInitializeClient() {
		config = BaseFinderConfig.load();

		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			if (!config.enabled) {
				return;
			}
			if (queue.size() >= MAX_QUEUE) {
				queue.poll();
			}
			queue.add(new PendingChunk(dimensionName(level.dimension().toString()), chunk));
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			queue.clear();
			dimensions.clear();
		});

		LOGGER.info("Base Finder loaded (threshold {}, requireStrong {})", config.threshold, config.requireStrong);
	}

	private void tick(Minecraft mc) {
		// Scan a couple of chunks per tick so flying around never causes a lag spike.
		int budget = config.chunksPerTick;
		while (budget-- > 0 && !queue.isEmpty()) {
			process(mc, queue.poll());
		}
	}

	private void process(Minecraft mc, PendingChunk pending) {
		LevelChunk chunk = pending.chunk();
		int cx = chunk.getPos().getMinBlockX() >> 4;
		int cz = chunk.getPos().getMinBlockZ() >> 4;
		DimensionState dim = dimensions.computeIfAbsent(pending.dimension(), k -> new DimensionState());

		if (dim.scores.containsKey(key(cx, cz))) {
			return; // already scanned this chunk
		}

		ChunkScore score = ChunkScanner.scan(chunk);
		if (score.points() <= 0) {
			return;
		}
		dim.scores.put(key(cx, cz), score);

		// Add up this chunk and its already-scanned neighbours, so a base that straddles
		// several chunks still adds up to a high score.
		int total = 0;
		int strong = 0;
		int bestPoints = -1;
		int bestX = cx;
		int bestZ = cz;
		String bestSummary = score.summary();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				ChunkScore s = dim.scores.get(key(cx + dx, cz + dz));
				if (s == null) {
					continue;
				}
				total += s.points();
				strong += s.strong();
				if (s.points() > bestPoints) {
					bestPoints = s.points();
					bestX = cx + dx;
					bestZ = cz + dz;
					bestSummary = s.summary();
				}
			}
		}

		if (total < config.threshold) {
			return;
		}
		if (config.requireStrong && strong == 0 && total < config.threshold * 4) {
			return;
		}

		for (Report report : dim.reports) {
			if (Math.abs(report.chunkX - bestX) <= MERGE_RADIUS && Math.abs(report.chunkZ - bestZ) <= MERGE_RADIUS) {
				// Same base as an earlier report. Only speak up again if it turned out much bigger.
				if (total >= report.score * 2) {
					report.score = total;
					announce(mc, pending.dimension(), bestX, bestZ, total, bestSummary);
				}
				return;
			}
		}

		dim.reports.add(new Report(bestX, bestZ, total));
		announce(mc, pending.dimension(), bestX, bestZ, total, bestSummary);
	}

	private void announce(Minecraft mc, String dimension, int chunkX, int chunkZ, int score, String summary) {
		int x = chunkX * 16 + 8;
		int z = chunkZ * 16 + 8;
		String line = dimension + " | X " + x + " Z " + z + " | score " + score + " | " + summary;

		LOGGER.info("Possible base: {}", line);
		appendToLog(line);
		if (config.chat && mc.player != null) {
			mc.player.sendSystemMessage(Component.literal(TAG + "Possible base: " + line));
		}
	}

	private static void appendToLog(String line) {
		Path file = FabricLoader.getInstance().getGameDir().resolve("basefinder-log.txt");
		String text = LocalDateTime.now().withNano(0) + " | " + line + System.lineSeparator();
		try {
			Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOGGER.warn("Could not write basefinder-log.txt", e);
		}
	}

	private static long key(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
	}

	/** "ResourceKey[minecraft:dimension / minecraft:overworld]" -> "overworld" */
	private static String dimensionName(String raw) {
		int slash = raw.lastIndexOf('/');
		String name = slash >= 0 ? raw.substring(slash + 1) : raw;
		name = name.replace("]", "").trim();
		return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
	}
}
