package com.basefinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * Draws a glowing orange beam (visible through walls) over every base the finder reports.
 * Based on the "Rendering in the World" example from the official Fabric docs.
 */
public class BaseHighlighter implements ClientModInitializer {
	/** Only the nearest few beams are drawn. */
	private static final int MAX_BEAMS = 3;
	private static final float MIN_Y = -64f;
	private static final float MAX_Y = 320f;
	private static final float HALF_WIDTH = 0.6f;
	/** A new find closer than this many blocks to an old one replaces it. */
	private static final int MERGE_DISTANCE = 64;

	private record Target(String dimension, int x, int z) {
	}

	/** Immutable data copied during the "extraction" phase and used in the "drawing" phase. */
	private record BeamState(float x, float z) {
	}

	private static final List<Target> TARGETS = new ArrayList<>();

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

	private List<BeamState> beams = List.of();

	/** Called by the finder whenever it reports a base. x and z are block coordinates. */
	public static void add(String dimension, int x, int z) {
		TARGETS.removeIf(t -> t.dimension().equals(dimension)
				&& Math.abs(t.x() - x) <= MERGE_DISTANCE
				&& Math.abs(t.z() - z) <= MERGE_DISTANCE);
		TARGETS.add(new Target(dimension, x, z));
	}

	@Override
	public void onInitializeClient() {
		LevelExtractionEvents.END_EXTRACTION.register(this::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(this::render);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			TARGETS.clear();
			beams = List.of();
		});
	}

	/** Extraction phase: work out which beams to draw this frame. */
	private void extract(LevelExtractionContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || TARGETS.isEmpty()) {
			beams = List.of();
			return;
		}

		String dimension = dimensionName(mc.level.dimension().toString());
		double px = mc.player.getX();
		double pz = mc.player.getZ();

		List<Target> here = new ArrayList<>();
		for (Target target : TARGETS) {
			if (target.dimension().equals(dimension)) {
				here.add(target);
			}
		}
		here.sort(Comparator.comparingDouble((Target t) -> Math.hypot(t.x() - px, t.z() - pz)));

		List<BeamState> list = new ArrayList<>();
		for (int i = 0; i < Math.min(MAX_BEAMS, here.size()); i++) {
			Target target = here.get(i);
			list.add(new BeamState(target.x() + 0.5f, target.z() + 0.5f));
		}
		beams = list;
	}

	/** Drawing phase. */
	private void render(LevelRenderContext context) {
		List<BeamState> current = beams;
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
		for (BeamState beam : current) {
			renderFilledBox(matrices.last().pose(), builder,
					beam.x() - HALF_WIDTH, MIN_Y, beam.z() - HALF_WIDTH,
					beam.x() + HALF_WIDTH, MAX_Y, beam.z() + HALF_WIDTH,
					1f, 0.5f, 0f, 0.5f);
		}

		matrices.popPose();

		stagedBuffer.upload();

		StagedVertexBuffer.ExecuteInfo info = stagedBuffer.getExecuteInfo(draw);

		if (info != null) {
			draw(Minecraft.getInstance(), info, renderPipeline);
		}

		stagedBuffer.endFrame();
	}	private void renderFilledBox(Matrix4fc positionMatrix, VertexConsumer buffer, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float red, float green, float blue, float alpha) {
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

	/** "ResourceKey[minecraft:dimension / minecraft:overworld]" -> "overworld" */
	private static String dimensionName(String raw) {
		int slash = raw.lastIndexOf('/');
		String name = slash >= 0 ? raw.substring(slash + 1) : raw;
		name = name.replace("]", "").trim();
		return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
	}
}
