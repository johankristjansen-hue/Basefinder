package com.basefinder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/** Tiny properties-file config: .minecraft/config/basefinder.properties */
final class BaseFinderConfig {
	boolean enabled = true;
	boolean chat = true;
	boolean requireStrong = true;
	int threshold = 50;
	int chunksPerTick = 2;

	static BaseFinderConfig load() {
		BaseFinderConfig config = new BaseFinderConfig();
		Path file = FabricLoader.getInstance().getConfigDir().resolve("basefinder.properties");
		Properties props = new Properties();

		try {
			if (Files.exists(file)) {
				try (InputStream in = Files.newInputStream(file)) {
					props.load(in);
				}
			}

			config.enabled = Boolean.parseBoolean(props.getProperty("enabled", "true"));
			config.chat = Boolean.parseBoolean(props.getProperty("chat", "true"));
			config.requireStrong = Boolean.parseBoolean(props.getProperty("requireStrong", "true"));
			config.threshold = parseInt(props.getProperty("threshold"), 50, 1, 100_000);
			config.chunksPerTick = parseInt(props.getProperty("chunksPerTick"), 2, 1, 32);

			props.setProperty("enabled", Boolean.toString(config.enabled));
			props.setProperty("chat", Boolean.toString(config.chat));
			props.setProperty("requireStrong", Boolean.toString(config.requireStrong));
			props.setProperty("threshold", Integer.toString(config.threshold));
			props.setProperty("chunksPerTick", Integer.toString(config.chunksPerTick));

			Files.createDirectories(file.getParent());
			try (OutputStream out = Files.newOutputStream(file)) {
				props.store(out, "Base Finder - threshold: score a 3x3 chunk area needs to be reported. "
						+ "requireStrong: also need at least one strong block (shulker box, concrete, hopper...) "
						+ "unless the score is 4x the threshold. chunksPerTick: lower = less lag.");
			}
		} catch (IOException | RuntimeException e) {
			BaseFinderClient.LOGGER.warn("Could not read/write basefinder.properties, using defaults", e);
		}
		return config;
	}

	private static int parseInt(String value, int fallback, int min, int max) {
		if (value == null) {
			return fallback;
		}
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
