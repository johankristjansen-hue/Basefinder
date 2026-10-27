package com.basefinder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Draws an orange box (visible through walls) around every block that counted towards a reported base.
 * Based on the "Rendering in the World" example from the official Fabric docs.
 */
public class BaseHighlighter implements ClientModInitializer {
	/** At most this many boxes are drawn at once. */
	private static final int MAX_BOXES = 300;
	/** Only blocks this close (in blocks, sideways) to you are drawn. */
	private static final double SHOW_DISTANCE = 160.0;
	/** Makes each box a tiny bit bigger than the block so it is easy to see. */
	private static final float GROW = 0.02f;

	/** Immutable data copied during the "extraction" phase and used in the "drawing" phase. */
	private record BoxState(int x, int y, int z) {
	}

	/** dimension -> chunk key -> x, y, z triples of the blocks to highlight. */
	private static final Map<String, Map<Long, int[]>> BLOCKS = new HashMap<>();

	private static final RenderPipeline FILLED_THROUGH_WALLS = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
					.withLocation(Identifier.fromNamespaceAndPath(BaseFinderClient.MOD_ID, "pipeline/base_highlight"))
					.withDepthStencilState(Optional.empty())
					.build());

	private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
	private static final StagedVertexBuffer stagedBuffer =
			new StagedVertexBuffer(() -> "Base Finder Highlights", RenderType.SMALL_BUFFER_SIZE);

	private List<BoxState> boxes = List.of();

	/** Called by the finder: remember the blocks of one chunk that belong to a reported base. */
	public static void addChunk(String dimension, int chunkX, int chunkZ, int[] positions) {
		if (positions.length == 0) {
			return;
		}
		BLOCKS.computeIfAbsent(dimension, k -> new HashMap<>()).put(key(chunkX, chunkZ), positions);
	}

	@Override
	public void onInitializeClient() {
		LevelExtractionEvents.END_EXTRACTION.register(this::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(this::render);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			BLOCKS.clear();
			boxes = List.of();
		});
	}

	/** Extraction phase: work out which blocks to draw this frame. */
	private void extract(LevelExtractionContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || BLOCKS.isEmpty()) {
			boxes = List.of();
			return;
		}

		Map<Long, int[]> chunks = BLOCKS.get(dimensionName(mc.level.dimension().toString()));
		if (chunks == null) {
			boxes = List.of();
			return;
		}

		double px = mc.player.getX();
		double pz = mc.player.getZ();
		double maxDistanceSquared = SHOW_DISTANCE * SHOW_DISTANCE;

		List<BoxState> list = new ArrayList<>();
		outer:
		for (int[] positions : chunks.values()) {
			for (int i = 0; i + 2 < positions.length; i += 3) {
				double dx = positions[i] + 0.5 - px;
				double dz = positions[i + 2] + 0.5 - pz;
				if (dx * dx + dz * dz > maxDistanceSquared) {
					continue;
				}
				list.add(new BoxState(positions[i], positions[i + 1], positions[i + 2]));
				if (list.size() >= MAX_BOXES) {
					break outer;
				}
			}
		}
		boxes = list;
	}

	/** Drawing phase. */
	private void render(LevelRenderContext context) {
		List<BoxState> current = boxes;
		if (current.isEmpty()) {
			return;
		}

		RenderPipeline renderPipeline = FILLED_THROUGH_WALLS;
		VertexFormat formatBinding = renderPipeline.getVertexFormatBinding(0);
		if (formatBinding == null) {
			return;
		}

		PrimitiveTopology primitive = renderPipeline.getPrimitiveTopology();
		StagedVertexBuffer.Draw draw = stagedBuffer.appendDraw(formatBinding, primitive,
				primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);

		PoseStack matrices = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;

		matrices.pushPose();
		matrices.translate(-camera.x, -camera.y, -camera.z);

		final VertexConsumer builder = stagedBuffer.getVertexBuilder(draw);
		for (BoxState box : current) {
			renderFilledBox(matrices.last().pose(), builder,
					box.x() - GROW, box.y() - GROW, box.z() - GROW,
					box.x() + 1 + GROW, box.y() + 1 + GROW, box.z() + 1 + GROW,
					1f, 0.5f, 0f, 0.5f);
		}

		matrices.popPose();

		stagedBuffer.upload();

		StagedVertexBuffer.ExecuteInfo info = stagedBuffer.getExecuteInfo(draw);

		if (info != null) {
			draw(Minecraft.getInstance(), info, renderPipeline);
		}

		stagedBuffer.endFrame();
	}
		private void renderFilledBox(Matrix4fc positionMatrix, VertexConsumer buffer, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float red, float green, float blue, float alpha) {
		// Front Face
		buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);

		// Back face
		buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);

		// Left face
		buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);

		// Right face
		buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);

		// Top face
		buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);

		// Bottom face
		buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
	}

	private static void draw(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
		GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
				.writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

		RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
		GpuTextureView colorTexture = mainTarget.getColorTextureView();

		if (colorTexture == null) {
			return;
		}

		try (RenderPass renderPass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> BaseFinderClient.MOD_ID + " highlight rendering", colorTexture, Optional.empty(), mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
			renderPass.setPipeline(pipeline);

			RenderSystem.bindDefaultUniforms(renderPass);
			renderPass.setUniform("DynamicTransforms", dynamicTransforms);

			renderPass.setVertexBuffer(0, info.vertexBuffer().slice());
			renderPass.setIndexBuffer(info.indexBuffer(), info.indexType());

			// The base vertex is the starting index when we copied the data into the vertex buffer divided by vertex size
			renderPass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
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
