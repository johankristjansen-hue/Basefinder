package com.basefinder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Client-side base finder. It only looks at chunks the server already sends to your client
 * (i.e. inside your render distance), scores them by how many player-made blocks they hold,
 * and tells you where the high scores are. Press B (changeable in Controls) to list them.
 */
public class BaseFinderClient implements ClientModInitializer {
	public static final String MOD_ID = "basefinder";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final String TAG = "[BaseFinder] ";
	private static final int MAX_QUEUE = 4000;
	/** Reports closer than this many chunks to an existing report are merged into it. */
	private static final int MERGE_RADIUS = 4;
	/** How many bases the key press lists. */
	private static final int MAX_SHOWN = 8;

	private record PendingChunk(String dimension, LevelChunk chunk) {
	}

	private static final class Report {
		final int chunkX;
		final int chunkZ;
		int score;
		String summary;

		Report(int chunkX, int chunkZ, int score, String summary) {
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.score = score;
			this.summary = summary;
		}
	}

	private static final class DimensionState {
		final Map<Long, ChunkScore> scores = new HashMap<>();
		final List<Report> reports = new ArrayList<>();
	}

	private final Deque<PendingChunk> queue = new ArrayDeque<>();
	private final Map<String, DimensionState> dimensions = new HashMap<>();
	private BaseFinderConfig config;

	/** Press this key (default B, can be changed in Options > Controls) to list the bases found so far. */
	private final KeyMapping.Category category =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
	private final KeyMapping showKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.basefinder.show", InputConstants.Type.KEYSYM, InputConstants.KEY_B, category));

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

		LOGGER.info("Base
