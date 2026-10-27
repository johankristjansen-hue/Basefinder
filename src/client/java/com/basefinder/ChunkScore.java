package com.basefinder;

/**
 * Result of scanning one chunk.
 * positions holds x, y, z triples (world coordinates) of the blocks that counted towards the score.
 */
record ChunkScore(int points, int strong, String summary, int[] positions) {
	static final ChunkScore EMPTY = new ChunkScore(0, 0, "", new int[0]);
}
