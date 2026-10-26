package com.basefinder;

/** Result of scanning one chunk. */
record ChunkScore(int points, int strong, String summary) {
	static final ChunkScore EMPTY = new ChunkScore(0, 0, "");
}
