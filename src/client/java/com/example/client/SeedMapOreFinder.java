package com.example.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class SeedMapOreFinder {
	private static final String ORE_FINDER_URL = "https://www.seedmap.app/ore-finder/";
	private static final String WORLD_SEED = "-3048587972680030099";
	private static final String CHROMIUM_PATH_PROPERTY = "xray.chromium.path";
	private static final String CHROMIUM_PATH_ENV = "XRAY_CHROMIUM_PATH";
	private static final String CHROMIUM_CONFIG_KEY = "chromiumPath";
	private static final int TARGET_VEINS = 24;
	private static final int MAX_SEARCH_ATTEMPTS = 2;
	private static final Pattern COORDINATES_PATTERN = Pattern.compile(
			"XYZ\\s+(-?\\d+)\\s*/\\s*(-?\\d+)\\s*/\\s*(-?\\d+)",
			Pattern.CASE_INSENSITIVE
	);
	private static final Gson GSON = new Gson();
	private static final Duration PAGE_LOAD_TIMEOUT = Duration.ofSeconds(45);
	private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(120);

	private SeedMapOreFinder() {
	}

	static List<OreResult> search(XraySeedEstimateClient.OreType oreType, int x, int y, int z) throws IOException {
		Path gameDirectory = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();
		Path chromiumPath = configuredChromiumPath(gameDirectory);
		Path profileDirectory = Files.createTempDirectory("xray-seedmap-chromium-");
		Process chromium = null;

		try {
			int debuggingPort = findAvailablePort();
			chromium = new ProcessBuilder(
					chromiumPath.toString(),
					"--headless=new",
					"--no-first-run",
					"--disable-extensions",
					"--remote-debugging-address=127.0.0.1",
					"--remote-debugging-port=" + debuggingPort,
					"--remote-allow-origins=http://127.0.0.1:" + debuggingPort,
					"--user-data-dir=" + profileDirectory,
					ORE_FINDER_URL
			)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.start();

			URI pageWebSocket = waitForPageWebSocket(chromium, debuggingPort);
			try (DevToolsPage page = DevToolsPage.connect(pageWebSocket)) {
				page.send("Runtime.enable", new JsonObject());
				waitForOreFinderForm(page);

				String initialEmptyState = configureSearch(page, oreType, x, y, z);
				for (int attempt = 0; attempt < MAX_SEARCH_ATTEMPTS; attempt++) {
					boolean canceled = waitForSearchResults(page, initialEmptyState);
					List<OreResult> results = readResults(page);
					if (attempt + 1 < MAX_SEARCH_ATTEMPTS && (canceled || results.isEmpty())) {
						initialEmptyState = retrySearch(page);
						continue;
					}
					if (canceled) {
						throw new IOException("SeedMap canceled the ore search after retrying.");
					}
					return results;
				}
				throw new IOException("SeedMap search ended without a result.");
			}
		} finally {
			stopChromium(chromium);
			deleteProfile(profileDirectory);
		}
	}

	private static Path configuredChromiumPath(Path gameDirectory) throws IOException {
		String path = System.getProperty(CHROMIUM_PATH_PROPERTY);
		if (path == null || path.isBlank()) {
			path = System.getenv(CHROMIUM_PATH_ENV);
		}

		if (path == null || path.isBlank()) {
			path = readChromiumPathFromConfig(gameDirectory);
		}

		if (path == null || path.isBlank()) {
			for (String candidate : List.of(
					"/usr/bin/chromium",
					"/usr/bin/chromium-browser",
					"/usr/bin/google-chrome",
					"/usr/bin/google-chrome-stable",
					"/snap/bin/chromium"
			)) {
				Path candidatePath = Path.of(candidate);
				if (Files.isRegularFile(candidatePath) && Files.isExecutable(candidatePath)) {
					return candidatePath;
				}
			}

			throw new IOException(
					"Chromium is not configured. Edit "
							+ gameDirectory.resolve("config/xray_seedestimate.properties")
							+ " and set chromiumPath=/path/to/chromium."
			);
		}

		try {
			Path chromiumPath = Path.of(path).toAbsolutePath().normalize();
			if (!Files.isRegularFile(chromiumPath) || !Files.isExecutable(chromiumPath)) {
				throw new IOException("Configured Chromium executable is missing or not executable: " + chromiumPath);
			}
			return chromiumPath;
		} catch (java.nio.file.InvalidPathException exception) {
			throw new IOException("Configured Chromium path is invalid: " + path, exception);
		}
	}

	private static String readChromiumPathFromConfig(Path gameDirectory) throws IOException {
		Path configPath = gameDirectory.resolve("config/xray_seedestimate.properties");
		Files.createDirectories(configPath.getParent());
		if (!Files.exists(configPath)) {
			Path legacyConfig = gameDirectory.resolve("config/xray-temp.properties");
			if (Files.exists(legacyConfig)) {
				Files.move(legacyConfig, configPath);
			}
		}
		if (!Files.exists(configPath)) {
			try {
				Files.writeString(
						configPath,
						"# Path to your installed Chromium executable.\n"
								+ "# Example: chromiumPath=/usr/bin/chromium\n"
								+ "chromiumPath=\n",
						java.nio.charset.StandardCharsets.UTF_8,
						java.nio.file.StandardOpenOption.CREATE_NEW
				);
			} catch (java.nio.file.FileAlreadyExistsException ignored) {
				// Another game startup created the config at the same time.
			}
		}

		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(configPath, java.nio.charset.StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		return properties.getProperty(CHROMIUM_CONFIG_KEY, "").trim();
	}

	private static int findAvailablePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			return socket.getLocalPort();
		}
	}

	private static URI waitForPageWebSocket(Process chromium, int port) throws IOException {
		URI targetListUri = URI.create("http://127.0.0.1:" + port + "/json/list");
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(2))
				.build();
		long deadline = System.nanoTime() + PAGE_LOAD_TIMEOUT.toNanos();

		while (System.nanoTime() < deadline) {
			if (!chromium.isAlive()) {
				throw new IOException("Chromium exited before the SeedMap page was ready.");
			}

			try {
				HttpResponse<String> response = httpClient.send(
						HttpRequest.newBuilder(targetListUri).timeout(Duration.ofSeconds(2)).GET().build(),
						HttpResponse.BodyHandlers.ofString()
				);
				if (response.statusCode() == 200) {
					JsonArray targets = JsonParser.parseString(response.body()).getAsJsonArray();
					for (JsonElement targetElement : targets) {
						JsonObject target = targetElement.getAsJsonObject();
						if (target.has("type") && "page".equals(target.get("type").getAsString())
								&& target.has("webSocketDebuggerUrl")) {
							return URI.create(target.get("webSocketDebuggerUrl").getAsString());
						}
					}
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while starting Chromium.", exception);
			} catch (JsonSyntaxException | IllegalStateException exception) {
				throw new IOException("Could not read Chromium's DevTools target list.", exception);
			} catch (IOException exception) {
				sleepBriefly();
			}
		}

		throw new IOException("Timed out while starting Chromium for SeedMap.");
	}

	private static void waitForOreFinderForm(DevToolsPage page) throws IOException {
		long deadline = System.nanoTime() + PAGE_LOAD_TIMEOUT.toNanos();
		String expression = """
				JSON.stringify(Boolean(
					document.querySelector('#oreFinderSeedInput')
					&& document.querySelector('#versionEditionSelector')
					&& document.querySelector('#ore-structure-selector')
					&& document.querySelector('#oreSearchControlsContainer')
					&& document.querySelector('#oreResultsContainer')
				))
				""";

		while (System.nanoTime() < deadline) {
			if ("true".equals(page.evaluate(expression))) {
				return;
			}
			sleepBriefly();
		}
		throw new IOException("SeedMap's ore search form did not load in Chromium.");
	}

	private static String configureSearch(DevToolsPage page, XraySeedEstimateClient.OreType oreType, int x, int y, int z)
			throws IOException {
		String expression = """
				(() => {
					const setValue = (selector, value) => {
						const element = document.querySelector(selector);
						if (!element) throw new Error(`SeedMap field not found: ${selector}`);
						element.value = value;
						element.dispatchEvent(new Event('input', { bubbles: true }));
						element.dispatchEvent(new Event('change', { bubbles: true }));
					};

					setValue('#oreFinderSeedInput', %s);
					setValue('#versionEditionSelector', 'java-26.3');
					setValue('#ore-structure-selector', %s);
					setValue('#ore-coord-x', %s);
					setValue('#ore-coord-y', %s);
					setValue('#ore-coord-z', %s);
					setValue('#oreTargetVeinsInput', %s);

					const emptyState = document.querySelector('#oreResultsEmptyState');
					const searchButton = document.querySelector('#oreWatchAdBtn');
					if (!emptyState || !searchButton) throw new Error('SeedMap result controls were not found.');
					const initialText = emptyState.innerText;
					const searchAlreadyRunning = searchButton.innerText.toLowerCase().includes('cancel')
						|| initialText.toLowerCase().includes('searching');
					if (!searchAlreadyRunning) searchButton.click();
					return JSON.stringify(initialText);
				})()
				""".formatted(
				GSON.toJson(WORLD_SEED),
				GSON.toJson(oreType.siteValue()),
				GSON.toJson(Integer.toString(x)),
				GSON.toJson(Integer.toString(y)),
				GSON.toJson(Integer.toString(z)),
				GSON.toJson(Integer.toString(TARGET_VEINS))
		);
		try {
			return JsonParser.parseString(page.evaluate(expression)).getAsString();
		} catch (IllegalStateException | JsonSyntaxException exception) {
			throw new IOException("Could not submit the ore search to SeedMap.", exception);
		}
	}

	private static boolean waitForSearchResults(DevToolsPage page, String initialEmptyState) throws IOException {
		String initialTextJson = GSON.toJson(initialEmptyState);
		String expression = """
				JSON.stringify((() => {
					const empty = document.querySelector('#oreResultsEmptyState');
					const list = document.querySelector('#oreResultsList');
					const cards = list ? [...list.querySelectorAll('.finding-card')] : [];
					const emptyText = empty ? empty.innerText.trim() : '';
					const initialText = %s;
					return {
						status: emptyText,
						resultCount: cards.length,
						searching: emptyText.toLowerCase().includes('searching'),
						changed: emptyText !== initialText || cards.length > 0
					};
				})())
				""".formatted(initialTextJson);

		long deadline = System.nanoTime() + SEARCH_TIMEOUT.toNanos();
		long stableSince = 0;
		String lastStableState = null;
		boolean observedSearching = false;
		while (System.nanoTime() < deadline) {
			JsonObject state = evaluateObject(page, expression, "Could not read SeedMap search results.");
			String status = state.get("status").getAsString();
			int resultCount = state.get("resultCount").getAsInt();
			if (status.toLowerCase().contains("cancel")) {
				return true;
			}
			if (status.toLowerCase().contains("could not")
					|| status.toLowerCase().contains("failed")
					|| status.toLowerCase().contains("invalid")
					|| status.toLowerCase().contains("outside signed")) {
				throw new IOException("SeedMap could not complete the ore search: " + status);
			}
			if (resultCount >= TARGET_VEINS) {
				return false;
			}

			boolean searching = state.get("searching").getAsBoolean();
			observedSearching |= searching;
			if (!searching && (state.get("changed").getAsBoolean() || observedSearching)) {
				String currentState = status + "\n" + resultCount;
				long now = System.nanoTime();
				if (!currentState.equals(lastStableState)) {
					lastStableState = currentState;
					stableSince = now;
				} else if (now - stableSince >= Duration.ofSeconds(2).toNanos()) {
					return false;
				}
			} else {
				lastStableState = null;
				stableSince = 0;
			}
			sleepBriefly();
		}
		throw new IOException("SeedMap did not finish the ore search within two minutes.");
	}

	private static String retrySearch(DevToolsPage page) throws IOException {
		String expression = """
				(() => {
					const empty = document.querySelector('#oreResultsEmptyState');
					const searchButton = document.querySelector('#oreWatchAdBtn');
					if (!empty || !searchButton) throw new Error('SeedMap search controls were not found.');
					const initialText = empty.innerText;
					const searchAlreadyRunning = searchButton.innerText.toLowerCase().includes('cancel')
						|| initialText.toLowerCase().includes('searching');
					if (!searchAlreadyRunning) searchButton.click();
					return JSON.stringify(initialText);
				})()
				""";
		try {
			return JsonParser.parseString(page.evaluate(expression)).getAsString();
		} catch (IllegalStateException | JsonSyntaxException exception) {
			throw new IOException("Could not retry the SeedMap search.", exception);
		}
	}

	private static List<OreResult> readResults(DevToolsPage page) throws IOException {
		String expression = """
				JSON.stringify([...document.querySelectorAll(
					'#oreResultsContainer #oreResultsList .finding-card'
				)].map(card => ({
					count: card.querySelector('.finding-card-count-number')?.innerText.trim() ?? '',
					name: card.querySelector('.finding-card-count-name')?.innerText.trim() ?? '',
					coordinates: card.querySelector('.finding-card-coords')?.innerText.trim() ?? ''
				})))
				""";
		JsonArray cards;
		try {
			cards = JsonParser.parseString(page.evaluate(expression)).getAsJsonArray();
		} catch (IllegalStateException | JsonSyntaxException exception) {
			throw new IOException("SeedMap returned an unreadable result list.", exception);
		}

		List<OreResult> results = new ArrayList<>();
		for (JsonElement cardElement : cards) {
			JsonObject card = cardElement.getAsJsonObject();
			String countText = card.get("count").getAsString();
			String oreName = card.get("name").getAsString();
			String coordinateText = card.get("coordinates").getAsString();
			Matcher matcher = COORDINATES_PATTERN.matcher(coordinateText);
			if (!matcher.find()) {
				throw new IOException("SeedMap returned an unrecognized coordinate: " + coordinateText);
			}

			try {
				results.add(new OreResult(
						Integer.parseInt(countText),
						oreName,
						Integer.parseInt(matcher.group(1)),
						Integer.parseInt(matcher.group(2)),
						Integer.parseInt(matcher.group(3))
				));
			} catch (NumberFormatException exception) {
				throw new IOException("SeedMap returned an invalid ore count: " + countText, exception);
			}
		}
		return List.copyOf(results);
	}

	private static JsonObject evaluateObject(DevToolsPage page, String expression, String errorMessage)
			throws IOException {
		try {
			return JsonParser.parseString(page.evaluate(expression)).getAsJsonObject();
		} catch (IllegalStateException | JsonSyntaxException exception) {
			throw new IOException(errorMessage, exception);
		}
	}

	private static void sleepBriefly() throws IOException {
		try {
			Thread.sleep(250);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for SeedMap.", exception);
		}
	}

	private static void stopChromium(Process chromium) {
		if (chromium == null || !chromium.isAlive()) {
			return;
		}

		chromium.descendants().forEach(ProcessHandle::destroy);
		chromium.destroy();
		try {
			if (!chromium.waitFor(5, TimeUnit.SECONDS)) {
				chromium.descendants().forEach(ProcessHandle::destroyForcibly);
				chromium.destroyForcibly();
				chromium.waitFor(5, TimeUnit.SECONDS);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			chromium.destroyForcibly();
		}
	}

	private static void deleteProfile(Path profileDirectory) throws IOException {
		if (!Files.exists(profileDirectory)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(profileDirectory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	record OreResult(int oreCount, String oreName, int x, int y, int z) {
	}

	private static final class DevToolsPage implements WebSocket.Listener, AutoCloseable {
		private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);

		private final AtomicLong nextCommandId = new AtomicLong();
		private final Map<Long, CompletableFuture<JsonObject>> pendingCommands = new ConcurrentHashMap<>();
		private final StringBuilder partialText = new StringBuilder();
		private final Object textLock = new Object();
		private volatile WebSocket webSocket;

		private DevToolsPage() {
		}

		static DevToolsPage connect(URI uri) throws IOException {
			DevToolsPage listener = new DevToolsPage();
			try {
				listener.webSocket = HttpClient.newHttpClient()
						.newWebSocketBuilder()
						.connectTimeout(Duration.ofSeconds(10))
						.buildAsync(uri, listener)
						.get(10, TimeUnit.SECONDS);
				return listener;
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while connecting to Chromium DevTools.", exception);
			} catch (ExecutionException | TimeoutException exception) {
				throw new IOException("Could not connect to Chromium DevTools.", exception);
			}
		}

		JsonObject send(String method, JsonObject params) throws IOException {
			long commandId = nextCommandId.incrementAndGet();
			CompletableFuture<JsonObject> responseFuture = new CompletableFuture<>();
			pendingCommands.put(commandId, responseFuture);

			JsonObject command = new JsonObject();
			command.addProperty("id", commandId);
			command.addProperty("method", method);
			command.add("params", params);

			try {
				webSocket.sendText(command.toString(), true).get(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
				JsonObject response = responseFuture.get(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
				if (response.has("error")) {
					throw new IOException("Chromium DevTools rejected " + method + ": " + response.get("error"));
				}
				JsonObject result = response.getAsJsonObject("result");
				if (result == null) {
					throw new IOException("Chromium DevTools returned no result for " + method + ".");
				}
				return result;
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while communicating with Chromium.", exception);
			} catch (ExecutionException | TimeoutException exception) {
				throw new IOException("Chromium DevTools timed out during " + method + ".", exception);
			} finally {
				pendingCommands.remove(commandId);
			}
		}

		String evaluate(String expression) throws IOException {
			JsonObject params = new JsonObject();
			params.addProperty("expression", expression);
			params.addProperty("returnByValue", true);
			JsonObject evaluation = send("Runtime.evaluate", params);
			if (evaluation.has("exceptionDetails")) {
				throw new IOException("SeedMap page script failed: " + evaluation.get("exceptionDetails"));
			}

			JsonObject remoteObject = evaluation.getAsJsonObject("result");
			JsonElement value = remoteObject == null ? null : remoteObject.get("value");
			if (value == null || value.isJsonNull()) {
				throw new IOException("SeedMap page script returned no value.");
			}
			return value.getAsString();
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			webSocket.request(1);
		}

		@Override
		public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			String completeMessage = null;
			synchronized (textLock) {
				partialText.append(data);
				if (last) {
					completeMessage = partialText.toString();
					partialText.setLength(0);
				}
			}

			if (completeMessage != null) {
				try {
					JsonObject message = JsonParser.parseString(completeMessage).getAsJsonObject();
					if (message.has("id")) {
						long commandId = message.get("id").getAsLong();
						CompletableFuture<JsonObject> future = pendingCommands.get(commandId);
						if (future != null) {
							future.complete(message);
						}
					}
				} catch (JsonSyntaxException | IllegalStateException exception) {
					failPendingCommands(new IOException("Chromium DevTools sent invalid JSON.", exception));
				}
			}

			webSocket.request(1);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			failPendingCommands(new IOException("Chromium DevTools connection failed.", error));
		}

		@Override
		public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			failPendingCommands(new IOException("Chromium DevTools connection closed: " + reason));
			return CompletableFuture.completedFuture(null);
		}

		private void failPendingCommands(IOException exception) {
			for (CompletableFuture<JsonObject> future : pendingCommands.values()) {
				future.completeExceptionally(exception);
			}
		}

		@Override
		public void close() {
			WebSocket socket = webSocket;
			if (socket != null) {
				socket.abort();
			}
		}
	}
}
