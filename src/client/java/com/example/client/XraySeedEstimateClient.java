package com.example.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.core.BlockPos;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class XraySeedEstimateClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger(XraySeedEstimateClient.class);
	private static final ExecutorService ORE_SEARCH_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "xray-seedestimate-seedmap-search");
		thread.setDaemon(true);
		return thread;
	});
	private static final AtomicBoolean ORE_SEARCH_RUNNING = new AtomicBoolean();
	private static final List<OreType> ORE_TYPES = List.of(
			new OreType("diamond", "diamonds", "Diamonds"),
			new OreType("netherite", "netherite", "Netherite"),
			new OreType("gold", "gold", "Gold"),
			new OreType("iron", "iron", "Iron"),
			new OreType("copper", "copper", "Copper"),
			new OreType("redstone", "redstone", "Redstone"),
			new OreType("lapis", "lapislazuli", "Lapis Lazuli")
	);

	private static boolean wireframesEnabled = true;
	private static volatile HighlightState highlightState = new HighlightState(null, List.of());
	private static MinedOreBlacklist minedOreBlacklist;
	private static String blacklistLoadError;

	@Override
	public void onInitializeClient() {
		try {
			minedOreBlacklist = MinedOreBlacklist.load(
					Minecraft.getInstance().gameDirectory.toPath().resolve("config/xray_seedestimate-mined-ores.txt"),
					Minecraft.getInstance().gameDirectory.toPath().resolve("config/xray-temp-mined-ores.txt")
			);
		} catch (IOException exception) {
			blacklistLoadError = exception.getMessage();
			LOGGER.error("Could not load the mined ore blacklist.", exception);
		}

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
				dispatcher.register(
						ClientCommands.literal("xray")
								.then(ClientCommands.literal("on").executes(context -> {
									wireframesEnabled = true;
									context.getSource().sendFeedback(Component.literal("Xray wireframes: ON"));
									return 1;
								}))
								.then(ClientCommands.literal("off").executes(context -> {
									wireframesEnabled = false;
									context.getSource().sendFeedback(Component.literal("Xray wireframes: OFF"));
									return 1;
								}))
								.then(ClientCommands.literal("ore")
										.then(ClientCommands.argument("type", StringArgumentType.word())
												.suggests((context, builder) -> {
													for (OreType oreType : ORE_TYPES) {
														builder.suggest(oreType.commandName());
													}
													return builder.buildFuture();
												})
												.executes(context -> searchOre(
														context.getSource(),
														StringArgumentType.getString(context, "type")
												))
										))
				)
		);

		LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
			Minecraft client = Minecraft.getInstance();
			HighlightState state = highlightState;
			if (!wireframesEnabled
					|| client.level == null
					|| !client.level.dimension().identifier().toString().equals(state.dimension())
					|| state.positions().isEmpty()) {
				return;
			}

			try (Gizmos.TemporaryCollection ignored = client.levelRenderer.collectPerFrameRenderThreadGizmos()) {
				for (BlockPos blockPos : state.positions()) {
					Gizmos.cuboid(blockPos, GizmoStyle.stroke(0xFFFF00FF, 4.0F))
							.setAlwaysOnTop();
				}
			}
		});

		ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
			String dimension = level.dimension().identifier().toString();
			HighlightState currentHighlights = highlightState;
			if (!dimension.equals(currentHighlights.dimension()) || !currentHighlights.positions().contains(pos)) {
				return;
			}

			highlightState = new HighlightState(dimension, currentHighlights.positions().stream()
					.filter(highlight -> !highlight.equals(pos))
					.toList());

			try {
				minedOreBlacklist.add(dimension, pos.getX(), pos.getY(), pos.getZ());
				sendClientMessage(Minecraft.getInstance(),
						"Mined ore position blacklisted: "
								+ pos.getX() + " / " + pos.getY() + " / " + pos.getZ());
			} catch (IOException exception) {
				LOGGER.error("Could not save mined ore position {} in {}.", pos, dimension, exception);
				sendClientMessage(Minecraft.getInstance(),
						"Wireframe removed, but could not persist its blacklist entry: " + exception.getMessage());
			}
		});

	}

	private static int searchOre(FabricClientCommandSource source, String requestedOre) {
		OreType oreType = ORE_TYPES.stream()
				.filter(candidate -> candidate.commandName().equalsIgnoreCase(requestedOre))
				.findFirst()
				.orElse(null);
		if (oreType == null) {
			source.sendFeedback(Component.literal(
					"Unknown ore '" + requestedOre + "'. Choose: "
							+ String.join(", ", ORE_TYPES.stream().map(OreType::commandName).toList())
			));
			return 0;
		}

		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			source.sendFeedback(Component.literal("Join a world before searching for ores."));
			return 0;
		}
		if (blacklistLoadError != null || minedOreBlacklist == null) {
			source.sendFeedback(Component.literal(
					"Ore blacklist could not be loaded: "
							+ (blacklistLoadError == null ? "not initialized" : blacklistLoadError)
			));
			return 0;
		}

		if (!ORE_SEARCH_RUNNING.compareAndSet(false, true)) {
			source.sendFeedback(Component.literal("An ore search is already running."));
			return 0;
		}

		int x = (int) Math.floor(client.player.getX());
		int y = (int) Math.floor(client.player.getY());
		int z = (int) Math.floor(client.player.getZ());
		String dimension = client.level.dimension().identifier().toString();
		source.sendFeedback(Component.literal(
				"Searching SeedMap for " + oreType.displayName() + " near " + x + " / " + y + " / " + z + "..."
		));

		ORE_SEARCH_EXECUTOR.execute(() -> {
			try {
				List<SeedMapOreFinder.OreResult> results = SeedMapOreFinder.search(oreType, x, y, z);
				client.execute(() -> printResults(client, oreType, results, dimension));
			} catch (IOException | IllegalStateException exception) {
				LOGGER.error("SeedMap search failed for {} at {}, {}, {}", oreType.displayName(), x, y, z, exception);
				client.execute(() -> sendClientMessage(
						client,
						"SeedMap search failed: " + exception.getMessage()
								+ " Check the configured Chromium path and latest.log."
				));
			} finally {
				ORE_SEARCH_RUNNING.set(false);
			}
		});

		return 1;
	}

	private static void printResults(
			Minecraft client,
			OreType oreType,
			List<SeedMapOreFinder.OreResult> results,
			String dimension
	) {
		List<SeedMapOreFinder.OreResult> newResults = results.stream()
				.filter(result -> !minedOreBlacklist.contains(
						dimension, result.x(), result.y(), result.z()
				))
				.toList();
		List<BlockPos> positions = newResults.stream()
				.map(result -> new BlockPos(result.x(), result.y(), result.z()))
				.toList();
		highlightState = new HighlightState(dimension, positions);

		if (results.isEmpty()) {
			sendClientMessage(client, "SeedMap found no " + oreType.displayName() + " veins near your search position.");
			return;
		}

		sendClientMessage(client, "SeedMap estimates for " + oreType.displayName()
				+ " (magenta wireframes mark unmined predicted positions; results may differ in-game):");
		for (SeedMapOreFinder.OreResult result : results) {
			boolean blacklisted = minedOreBlacklist.contains(dimension, result.x(), result.y(), result.z());
			sendClientMessage(
					client,
					result.oreCount() + " " + result.oreName() + " at "
							+ result.x() + " / " + result.y() + " / " + result.z()
							+ (blacklisted ? " (mined; blacklisted)" : "")
			);
		}
		if (newResults.isEmpty()) {
			sendClientMessage(client, "All returned positions are already blacklisted; no wireframes were added.");
		}
	}

	private static void sendClientMessage(Minecraft client, String message) {
		client.gui.chatListener().handleSystemMessage(Component.literal(message), false);
	}

	record OreType(String commandName, String siteValue, String displayName) {
	}

	private record HighlightState(String dimension, List<BlockPos> positions) {
	}
}
