// headless stdin/stdout JSON IPC server
// Tauri (Rust) spawns this process as a child and exchanges JSON over
// stdin/stdout. No logs are ever printed to stdout (they only go to
// stderr, to keep stdout clean for JSON).

package net.mcreator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mcreator.element.GeneratableElement;
import net.mcreator.element.ModElementType;
import net.mcreator.element.ModElementTypeLoader;
import net.mcreator.element.parts.TextureHolder;
import net.mcreator.generator.Generator;
import net.mcreator.generator.GeneratorConfiguration;
import net.mcreator.generator.setup.WorkspaceGeneratorSetup;
import net.mcreator.gradle.GradleUtils;
import net.mcreator.io.FileIO;
import net.mcreator.io.OutputStreamEventHandler;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.WorkspaceFolderManager;
import net.mcreator.workspace.elements.ModElement;
import net.mcreator.workspace.elements.SoundElement;
import net.mcreator.workspace.resources.CustomTexture;
import net.mcreator.workspace.resources.Model;
import net.mcreator.workspace.resources.TextureType;
import net.mcreator.workspace.settings.WorkspaceSettings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.ProjectConnection;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

public final class IPCServer {

	private static final Logger LOG = LogManager.getLogger("IPCServer");

	// all logs go to stderr so stdout stays clean for JSON.
	// LoggingSystem.init() wraps System.out into Log4j; therefore we use the
	// RAW_STDOUT that Launcher captured before init().
	private static final PrintStream OUT = net.mcreator.Launcher.RAW_STDOUT;

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static volatile ProjectConnection activeGameConnection;

	// test seams package private so lifecycle tests can run without gradle
	static volatile SourcePreparation SOURCE_PREPARATION = GradleSourcePreparation::ensureReady;
	static volatile java.util.function.Function<Workspace, ProjectConnection> GRADLE_CONNECTION = GradleUtils::getGradleProjectConnection;

	@FunctionalInterface interface SourcePreparation {
		void ensureReady(Workspace workspace) throws Exception;
	}

	private IPCServer() {
	}

	/**
	 * Starts the blocking stdin/stdout JSON loop.
	 * Each line is a JSON message. Message format:
	 *   request:  {"cmd":"<command>", "args": {...}}
	 *   response:  {"type":"result","data":{...}} or {"type":"error","message":"..."}
	 * The ready signal is written as soon as the loop starts: {"type":"ready","version":"..."}
	 */
	public static void runLoop() {
		// ready signal
		sendReady();

		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		String line;
		try {
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty())
					continue;
				try {
					RequestEnvelope request = parseRequest(line);
					if (request.error() != null) {
						sendError(request.error());
						continue;
					}
					handle(request.command(), request.args());
				} catch (Exception e) {
					LOG.error("Failed to handle IPC message: {}", line, e);
					sendError("invalid message: " + e.getMessage());
				}
			}
		} catch (IOException e) {
			LOG.error("IPC stdin closed", e);
		}
	}

	static RequestEnvelope parseRequest(String line) {
		JsonElement parsed = JsonParser.parseString(line);
		if (!parsed.isJsonObject()) {
			return RequestEnvelope.invalid("invalid message: request must be a JSON object");
		}
		JsonObject msg = parsed.getAsJsonObject();
		JsonElement cmdEl = msg.get("cmd");
		if (cmdEl == null || !cmdEl.isJsonPrimitive() || !cmdEl.getAsJsonPrimitive().isString()
				|| cmdEl.getAsString().trim().isEmpty()) {
			return RequestEnvelope.invalid("invalid message: cmd must be a non-empty string");
		}
		if (msg.has("args") && !msg.get("args").isJsonNull() && !msg.get("args").isJsonObject()) {
			return RequestEnvelope.invalid("invalid message: args must be a JSON object");
		}
		JsonObject args = msg.has("args") && msg.get("args").isJsonObject()
				? msg.getAsJsonObject("args") : new JsonObject();
		return new RequestEnvelope(cmdEl.getAsString().trim(), args, null);
	}

	record RequestEnvelope(String command, JsonObject args, String error) {
		private static RequestEnvelope invalid(String error) {
			return new RequestEnvelope(null, null, error);
		}
	}

	private static void sendReady() {
		String version = Launcher.version != null ? Launcher.version.toString() : "unknown";
		JsonObject o = new JsonObject();
		o.addProperty("type", "ready");
		o.addProperty("version", version);
		write(o);
	}

	private static void sendResult(JsonObject data) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "result");
		o.add("data", data);
		write(o);
	}

	private static void sendError(String message) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "error");
		o.addProperty("message", message);
		write(o);
	}

	// sends a progress message: {"type":"progress","data":{"message":"...","percent":N}}
	private static void sendProgress(String message, int percent) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "progress");
		JsonObject data = new JsonObject();
		data.addProperty("message", message);
		data.addProperty("percent", Math.max(0, Math.min(100, percent)));
		o.add("data", data);
		write(o);
	}

	// writes a single JSON line to stdout (flushed). Logging output never goes to stdout.
	private static synchronized void write(JsonObject o) {
		OUT.println(GSON.toJson(o));
		OUT.flush();
	}

	private static void handle(String cmd, JsonObject args) {
		switch (cmd) {
			case "ping" -> {
				JsonObject data = new JsonObject();
				data.addProperty("pong", true);
				sendResult(data);
			}
			case "list_projects" -> sendResult(listProjects(args));
			case "list_generators" -> sendResult(listGenerators());
			case "create_project" -> createProject(args);
			case "ensure_target" -> {
				try {
					ensureTarget(args);
				} catch (Exception e) {
					LOG.error("ensure_target failed", e);
					sendError("ensure_target: " + e.getMessage());
				}
			}
			case "mark_targets_dirty" -> {
				try {
					markTargetsDirty(args);
				} catch (Exception e) {
					LOG.error("mark_targets_dirty failed", e);
					sendError("mark_targets_dirty: " + e.getMessage());
				}
			}
			case "compile_project" -> {
				try {
					compileProject(args);
				} catch (Exception e) {
					LOG.error("compile_project failed", e);
					sendError("compile_project: " + e.getMessage());
				}
			}
			case "run_game" -> {
				WorkspaceTarget target = getWorkspaceTarget(args);
				if (target == null) return;
				AtomicBoolean started = new AtomicBoolean();
				try {
					prepareGame(target, started);
				} catch (Exception e) {
					LOG.error("run_game preparation failed", e);
					writeGameError(target.folder(), e.getMessage());
					sendError("run_game: " + e.getMessage());
					return;
				}
				if (!started.get()) {
					sendError("run_game: launch did not start");
					return;
				}
				JsonObject data = new JsonObject();
				data.addProperty("status", "started");
				sendResult(data);
			}
			case "list_textures" -> listTextures(args);
			case "import_texture" -> importTexture(args);
			case "delete_texture" -> deleteTexture(args);
			case "list_sounds" -> listSounds(args);
			case "import_sound" -> importSound(args);
			case "delete_sound" -> deleteSound(args);
			case "list_models" -> listModels(args);
			case "import_model" -> importModel(args);
			case "delete_model" -> deleteModel(args);
			case "read_resource_file" -> readResourceFile(args);
			case "create_element" -> {
				try {
					createElement(args);
				} catch (Exception e) {
					LOG.error("create_element failed", e);
					sendError("create_element: " + e.getMessage());
				}
			}
			case "stop_game" -> stopGame();
			case "update_element" -> {
				try {
					updateElement(args);
				} catch (Exception e) {
					LOG.error("update_element failed", e);
					sendError("update_element: " + e.getMessage());
				}
			}
			case "list_elements" -> listElements(args);
			case "get_element" -> getElement(args);
			case "delete_element" -> {
				try {
					deleteElement(args);
				} catch (Exception e) {
					LOG.error("delete_element failed", e);
					sendError("delete_element: " + e.getMessage());
				}
			}
			default -> sendError("unknown command: " + cmd);
		}
	}

	/**
	 * Returns all generators from Generator.GENERATOR_CACHE as a list.
	 * For each generator: id, name, mcversion, description, flavor, supportedElementTypes
	 */
	private static JsonObject listGenerators() {
		JsonObject data = new JsonObject();
		JsonArray generators = new JsonArray();
		for (Map.Entry<String, GeneratorConfiguration> entry : Generator.GENERATOR_CACHE.entrySet()) {
			JsonObject gen = new JsonObject();
			GeneratorConfiguration cfg = entry.getValue();
			gen.addProperty("id", entry.getKey());
			gen.addProperty("name", cfg.getGeneratorName());
			gen.addProperty("mcversion", cfg.getGeneratorMinecraftVersion());
			gen.addProperty("description", cfg.toString());
			// loader family used to group versions in the picker
			gen.addProperty("flavor", cfg.getGeneratorFlavor().name());
			// registry names this generator can build, so the ui can flag unsupported elements
			JsonArray supported = new JsonArray();
			for (ModElementType<?> type : ModElementTypeLoader.getAllModElementTypes()) {
				if (cfg.getDefinitionsProvider().getModElementDefinition(type) != null)
					supported.add(type.getRegistryName());
			}
			gen.add("supportedElementTypes", supported);
			generators.add(gen);
		}
		data.add("generators", generators);
		data.addProperty("count", generators.size());
		return data;
	}

	/**
	 * Scans all .modstudio_ai / .mcreator files under the per-user Minecraft data
	 * directory (%APPDATA%/.modStudioAI/minecraft) and returns project previews
	 * (modName, modid, version, description, generator, element count...).
	 */
	private static JsonObject listProjects(JsonObject args) {
		File requestedRoot = optionalFolder(args);
		File root = requestedRoot != null ? requestedRoot : WorkspaceFolderManager.getSuggestedWorkspaceFoldersRoot();
		JsonObject data = new JsonObject();
		JsonArray projects = new JsonArray();

		JsonObject directProject = root.isDirectory() ? readProjectFromFolder(root) : null;
		if (directProject != null)
			projects.add(directProject);
		File[] children = root.listFiles(File::isDirectory);
		if (children != null) {
			Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
			for (File dir : children) {
				if (dir.getName().equals("backend")) // managed runtime, never a workspace
					continue;
				JsonObject proj = readProjectFromFolder(dir);
				if (proj != null)
					projects.add(proj);
			}
		}
		data.add("root", GSON.toJsonTree(root.getAbsolutePath()));
		data.add("projects", projects);
		data.addProperty("count", projects.size());
		return data;
	}

	private static void createProject(JsonObject args) {
		Workspace workspace = null;
		File wsFolder = null;
		boolean transactionOwnedFolder = false;
		try {
			// Required fields
			String modid = optStr(args, "modid", "").trim().toLowerCase();
			if (modid.isEmpty()) { sendError("modid is required"); return; }
			if (!modid.matches("[a-z0-9_]+")) { sendError("modid must be lowercase alphanumeric + underscore"); return; }

			String modName = optStr(args, "name", modid);
			String version = optStr(args, "version", "1.0.0");
			String description = optStr(args, "description", "");
			String author = optStr(args, "author", "ModStudio AI");
			String license = optStr(args, "license", "MIT");
			String generator = optStr(args, "generator", "neoforge-1.21.1");
			String pkg = optStr(args, "mod_elements_package", "net.mcreator." + modid);
			String providedFolder = args.has("folder") ? optStr(args, "folder", "") : "";

			sendProgress("Validating inputs", 5);

			// Determine workspace folder
			File root = WorkspaceFolderManager.getSuggestedWorkspaceFoldersRoot();
			File candidateFolder = !providedFolder.isEmpty()
					? new File(providedFolder).getAbsoluteFile() : new File(root, modid);
			if (!pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
				sendError("mod_elements_package must be a valid Java package");
				return;
			}
			if (Generator.GENERATOR_CACHE.get(generator) == null) {
				sendError("Generator not found: " + generator
						+ ". Available: " + String.join(", ", Generator.GENERATOR_CACHE.keySet()));
				return;
			}
			if (candidateFolder.exists()) {
				sendError("workspace folder already exists: " + candidateFolder.getAbsolutePath());
				return;
			}
			root.mkdirs();
			if (!candidateFolder.mkdirs()) {
				sendError("could not create workspace folder: " + candidateFolder.getAbsolutePath());
				return;
			}
			transactionOwnedFolder = true;
			wsFolder = candidateFolder.getCanonicalFile();
			File wsFile = new File(wsFolder, modid + ".modstudio_ai");

			sendProgress("Creating workspace folder: " + wsFolder.getName(), 15);

			// Build WorkspaceSettings
			WorkspaceSettings settings = new WorkspaceSettings(modid);
			settings.setModName(modName);
			settings.setVersion(version);
			settings.setDescription(description);
			settings.setAuthor(author);
			settings.setLicense(license);
			settings.setModElementsPackage(pkg);
			settings.setCurrentGenerator(generator);

			sendProgress("Workspace settings prepared", 25);

			// Create workspace
			workspace = Workspace.createWorkspace(wsFile, settings);

			sendProgress("Workspace created, setting up base files...", 50);

			// Setup workspace base files (gradle, properties etc.)
			net.mcreator.generator.setup.WorkspaceGeneratorSetup.setupWorkspaceBase(workspace);

			sendProgress("Base files setup complete, generating mod base...", 70);
			GenerationCoordinator.generateBaseStrict(workspace);

			sendProgress("Preparing generator sources...", 75);
			SOURCE_PREPARATION.ensureReady(workspace);

			new File(wsFolder, "build/classes/java/main").mkdirs();
			new File(wsFolder, "build/resources/main").mkdirs();

			if (WorkspaceGeneratorSetup.shouldSetupBeRan(workspace.getGenerator())) {
				WorkspaceGeneratorSetup.completeSetup(workspace.getGenerator());
			}

			sendProgress("Finalizing project...", 85);

			// Save and close
			workspace.getFileManager().saveWorkspaceDirectlyAndWaitStrict();
			verifySavedWorkspace(wsFile, modid, workspace.getModElements(),
					GenerationCoordinator.trackedBasePaths(workspace), null);
			workspace.closeStrict();
			workspace = null;

			// multi target projects record their full target set in a manifest
			writeTargetsManifestIfMulti(wsFolder, args, generator, modid, pkg);

			sendProgress("Project created successfully!", 100);

			// Build result in same format as list_projects
			JsonObject result = new JsonObject();
			result.addProperty("folder", wsFolder.getAbsolutePath());
			result.addProperty("file", wsFile.getName());
			result.addProperty("file_path", wsFile.getAbsolutePath());
			result.addProperty("modid", modid);
			result.addProperty("name", modName);
			result.addProperty("version", version);
			result.addProperty("description", description);
			result.addProperty("author", author);
			result.addProperty("license", license);
			result.addProperty("generator", generator);
			result.addProperty("mod_elements_package", pkg);
			result.addProperty("element_count", 0);
			result.addProperty("mcreator_version",
					Launcher.version != null ? Launcher.version.versionlong : 0L);

			sendResult(result);

		} catch (Exception e) {
			if (workspace != null) {
				try {
					workspace.closeWithoutSaveStrict();
				} catch (Exception closeError) {
					e.addSuppressed(closeError);
				}
			}
			if (transactionOwnedFolder && wsFolder != null) {
				try {
					deleteDirectory(wsFolder.toPath());
				} catch (Exception cleanupError) {
					e.addSuppressed(cleanupError);
				}
			}
			LOG.error("Failed to create project", e);
			sendError("Failed to create project: " + e.getMessage());
		}
	}

	// writes modstudio.targets.json when the project targets more than one version
	private static void writeTargetsManifestIfMulti(File wsFolder, JsonObject args,
			String primaryGenerator, String modid, String pkg) {
		if (!args.has("targets") || !args.get("targets").isJsonArray()) return;
		JsonArray requested = args.getAsJsonArray("targets");
		if (requested.size() < 2) return;

		String primary = optStr(args, "primary_generator", primaryGenerator);
		// primary must be part of the target set
		JsonArray targets = new JsonArray();
		boolean hasPrimary = false;
		for (JsonElement el : requested) {
			String id = el.getAsString().trim();
			if (id.isEmpty()) continue;
			targets.add(id);
			if (id.equals(primary)) hasPrimary = true;
		}
		if (!hasPrimary) targets.add(primary);

		JsonObject manifest = new JsonObject();
		manifest.addProperty("primaryGenerator", primary);
		manifest.add("targets", targets);
		manifest.addProperty("package", pkg);
		manifest.addProperty("modid", modid);
		try {
			File manifestFile = new File(wsFolder, "modstudio.targets.json");
			Files.writeString(manifestFile.toPath(), GSON.toJson(manifest));
		} catch (IOException e) {
			LOG.error("failed to write targets manifest", e);
		}
	}

	// marks every materialised sibling dirty so the next play refreshes its shared elements
	private static void markTargetsDirty(JsonObject args) throws Exception {
		File primaryFolder = optionalFolder(args);
		if (primaryFolder == null) {
			String requestedModid = optStr(args, "modid", "").trim();
			if (!requestedModid.isEmpty()) primaryFolder = findWorkspaceFolder(requestedModid);
		}
		if (primaryFolder == null || !primaryFolder.isDirectory()) {
			sendError("primary workspace folder not found"); return;
		}
		File targetsRoot = new File(primaryFolder.getCanonicalFile(), "targets");
		int marked = 0;
		File[] children = targetsRoot.listFiles(File::isDirectory);
		if (children != null) {
			for (File child : children) {
				Files.writeString(new File(child, ".modstudio_dirty").toPath(), "");
				marked++;
			}
		}
		JsonObject data = new JsonObject();
		data.addProperty("marked", marked);
		sendResult(data);
	}

	// materialises or refreshes a sibling workspace bound to the given generator
	private static void ensureTarget(JsonObject args) throws Exception {
		String requestedGen = optStr(args, "generator", "").trim();
		if (requestedGen.isEmpty()) { sendError("generator is required"); return; }
		if (Generator.GENERATOR_CACHE.get(requestedGen) == null) {
			sendError("Generator not found: " + requestedGen); return;
		}

		File primaryFolder = optionalFolder(args);
		if (primaryFolder == null) {
			String requestedModid = optStr(args, "modid", "").trim();
			if (!requestedModid.isEmpty()) primaryFolder = findWorkspaceFolder(requestedModid);
		}
		if (primaryFolder == null || !primaryFolder.isDirectory()) {
			sendError("primary workspace folder not found"); return;
		}
		primaryFolder = primaryFolder.getCanonicalFile();

		File primaryFile = findWorkspaceFile(primaryFolder);
		if (primaryFile == null) { sendError("primary workspace marker not found"); return; }
		String modid = workspaceModid(primaryFile).trim();
		if (modid.isEmpty()) { sendError("primary workspace modid is missing"); return; }

		String primaryGen = readCurrentGenerator(primaryFile);

		// the requested version is the primary workspace itself, nothing to build
		if (requestedGen.equals(primaryGen)) {
			sendTargetResult(primaryFolder, primaryFile.getName(), modid, requestedGen, true, false);
			return;
		}

		File targetsRoot = new File(primaryFolder, "targets");
		File targetFolder = new File(targetsRoot, requestedGen);
		File targetFile = new File(targetFolder, modid + ".modstudio_ai");
		File dirtyMarker = new File(targetFolder, ".modstudio_dirty");
		boolean force = optBool(args, "force", false);

		// already materialised and clean, reuse the warm cache
		if (targetFile.isFile() && !dirtyMarker.exists() && !force) {
			sendTargetResult(targetFolder, targetFile.getName(), modid, requestedGen, false, false);
			return;
		}

		sendProgress("Preparing " + requestedGen + " target", 10);

		// refresh from scratch so stale generated code never leaks across loaders
		deleteDirectory(targetFolder.toPath());
		targetFolder.mkdirs();

		// copy the source of truth, descriptor plus element and asset trees
		Files.copy(primaryFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		copyIfExists(new File(primaryFolder, "elements"), new File(targetFolder, "elements"));
		copyIfExists(new File(primaryFolder, "models"), new File(targetFolder, "models"));
		copyIfExists(new File(primaryFolder, "src/main/resources/assets"),
				new File(targetFolder, "src/main/resources/assets"));
		copyIfExists(new File(primaryFolder, "src/main/resources/data"),
				new File(targetFolder, "src/main/resources/data"));

		// retarget the copied descriptor to the requested generator before it loads
		retargetDescriptor(targetFile, requestedGen);

		sendProgress("Setting up " + requestedGen + " workspace", 40);

		Workspace workspace = null;
		try {
			workspace = Workspace.readFromFS(targetFile, null);
			net.mcreator.generator.setup.WorkspaceGeneratorSetup.setupWorkspaceBase(workspace);
			GenerationCoordinator.generateBaseStrict(workspace);
			SOURCE_PREPARATION.ensureReady(workspace);
			new File(targetFolder, "build/classes/java/main").mkdirs();
			new File(targetFolder, "build/resources/main").mkdirs();
			if (WorkspaceGeneratorSetup.shouldSetupBeRan(workspace.getGenerator()))
				WorkspaceGeneratorSetup.completeSetup(workspace.getGenerator());
			workspace.getFileManager().saveWorkspaceDirectlyAndWaitStrict();
			workspace.closeStrict();
			workspace = null;
		} catch (Exception e) {
			if (workspace != null) {
				try { workspace.closeWithoutSaveStrict(); } catch (Exception ce) { e.addSuppressed(ce); }
			}
			try { deleteDirectory(targetFolder.toPath()); } catch (Exception ce) { e.addSuppressed(ce); }
			throw e;
		}

		Files.deleteIfExists(dirtyMarker.toPath());
		sendProgress("Target ready", 100);
		sendTargetResult(targetFolder, targetFile.getName(), modid, requestedGen, false, true);
	}

	private static void sendTargetResult(File folder, String file, String modid, String generator,
			boolean primary, boolean regenerated) {
		JsonObject data = new JsonObject();
		data.addProperty("folder", folder.getAbsolutePath());
		data.addProperty("file", file);
		data.addProperty("modid", modid);
		data.addProperty("generator", generator);
		data.addProperty("primary", primary);
		data.addProperty("materialized", true);
		data.addProperty("regenerated", regenerated);
		sendResult(data);
	}

	private static String readCurrentGenerator(File workspaceFile) {
		try {
			JsonObject root = JsonParser.parseString(Files.readString(workspaceFile.toPath())).getAsJsonObject();
			if (root.has("workspaceSettings") && root.get("workspaceSettings").isJsonObject())
				return optStr(root.getAsJsonObject("workspaceSettings"), "currentGenerator", "");
		} catch (Exception ignored) {
		}
		return "";
	}

	// rewrites the descriptor so the loaded workspace binds to the target generator
	private static void retargetDescriptor(File workspaceFile, String generator) throws IOException {
		JsonObject root = JsonParser.parseString(Files.readString(workspaceFile.toPath())).getAsJsonObject();
		JsonObject settings = root.has("workspaceSettings") && root.get("workspaceSettings").isJsonObject()
				? root.getAsJsonObject("workspaceSettings") : new JsonObject();
		settings.addProperty("currentGenerator", generator);
		root.add("workspaceSettings", settings);
		Files.writeString(workspaceFile.toPath(), GSON.toJson(root));
	}

	// recursively copies a directory tree, used to seed a sibling from the primary
	private static void copyIfExists(File src, File dst) throws IOException {
		if (!src.isDirectory()) return;
		Path srcPath = src.toPath();
		Path dstPath = dst.toPath();
		try (var paths = Files.walk(srcPath)) {
			for (Path path : paths.toList()) {
				Path rel = srcPath.relativize(path);
				Path out = dstPath.resolve(rel);
				if (Files.isDirectory(path))
					Files.createDirectories(out);
				else {
					Files.createDirectories(out.getParent());
					Files.copy(path, out, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	private static void validateElementInvariants(GeneratableElement element, Workspace workspace) throws IOException {
		if (element instanceof net.mcreator.element.types.Dimension dimension) {
			if (!dimension.enablePortal && dimension.enableIgniter)
				throw new IOException("dimension igniter requires an enabled portal");
			if (dimension.enablePortal) {
				if (dimension.portalFrame == null || dimension.portalFrame.isEmpty())
					throw new IOException("dimension portal frame is required");
				if (!dimension.portalFrame.isValidReference())
					throw new IOException("dimension portal frame does not exist");
				if (dimension.portalParticles == null)
					dimension.portalParticles = new net.mcreator.element.parts.ParticleEntry(workspace, "PORTAL");
				if (!dimension.portalParticles.isValidReference())
					throw new IOException("dimension portal particle does not exist");
				if (dimension.portalSound == null)
					dimension.portalSound = new net.mcreator.element.parts.Sound(workspace, "block.portal.ambient");
				if (!dimension.portalSound.isValidReference())
					throw new IOException("dimension portal sound does not exist");
				requireTexture(workspace, dimension.portalTexture, TextureType.BLOCK, "portal texture");
			}
			if (dimension.enableIgniter) {
				if (dimension.igniterName == null || dimension.igniterName.isBlank())
					throw new IOException("dimension igniter name is required");
				requireTexture(workspace, dimension.texture, TextureType.ITEM, "igniter texture");
			}
		} else if (element instanceof net.mcreator.element.types.LivingEntity entity) {
			if (entity.mobModelName == null || entity.mobModelName.isBlank()) entity.mobModelName = "Biped";
			if (entity.mobModelTexture == null || entity.mobModelTexture.isBlank())
				throw new IOException("living entity texture is required");
			String texture = entity.mobModelTexture.trim();
			if (texture.endsWith(".png")) texture = texture.substring(0, texture.length() - 4);
			if (!workspace.getFolderManager().getTextureFile(texture, TextureType.ENTITY).isFile())
				throw new IOException("living entity texture does not exist: " + entity.mobModelTexture);
		}
	}

	private static void requireTexture(Workspace workspace, TextureHolder texture, TextureType type, String label)
			throws IOException {
		if (texture == null || texture.isEmpty())
			throw new IOException(label + " is required");
		String name = texture.name();
		if (name.endsWith(".png")) name = name.substring(0, name.length() - 4);
		if (!workspace.getFolderManager().getTextureFile(name, type).isFile())
			throw new IOException(label + " does not exist: " + texture.getRawTextureName());
	}

	private static void verifySavedWorkspace(File workspaceFile, String modid) throws IOException {
		verifySavedWorkspace(workspaceFile, modid, null, null, null);
	}

	private static void verifySavedWorkspace(File workspaceFile, String modid,
			@Nullable Collection<ModElement> expectedElements, @Nullable Set<String> expectedBaseFiles,
			@Nullable String expectedElementName) throws IOException {
		if (!workspaceFile.isFile())
			throw new IOException("workspace save did not produce a workspace file");
		JsonObject root;
		try {
			root = JsonParser.parseString(Files.readString(workspaceFile.toPath())).getAsJsonObject();
		} catch (RuntimeException e) {
			throw new IOException("saved workspace is unreadable", e);
		}
		JsonObject settings = root.getAsJsonObject("workspaceSettings");
		if (settings == null || !modid.equals(optStr(settings, "modid", "")))
			throw new IOException("saved workspace is incoherent");

		if (expectedElements != null) {
			Set<String> expected = new java.util.TreeSet<>();
			for (ModElement element : expectedElements)
				expected.add(element.getName() + ":" + element.getTypeString());
			Set<String> saved = new java.util.TreeSet<>();
			if (root.has("mod_elements") && root.get("mod_elements").isJsonArray()) {
				for (JsonElement entry : root.getAsJsonArray("mod_elements")) {
					JsonObject object = entry.isJsonObject() ? entry.getAsJsonObject() : null;
					if (object == null)
						throw new IOException("saved workspace has an invalid mod element entry");
					saved.add(optStr(object, "name", "") + ":" + optStr(object, "type", ""));
				}
			}
			if (!saved.equals(expected))
				throw new IOException("saved workspace element list does not match expected elements");
		}

		if (expectedBaseFiles != null) {
			Set<String> saved = new java.util.TreeSet<>();
			JsonElement filesMetadata = root.get("metadata");
			if (filesMetadata != null && filesMetadata.isJsonObject()
					&& filesMetadata.getAsJsonObject().has("files")
					&& filesMetadata.getAsJsonObject().get("files").isJsonArray()) {
				for (JsonElement entry : filesMetadata.getAsJsonObject().getAsJsonArray("files"))
					saved.add(entry.getAsString());
			}
			if (!saved.equals(expectedBaseFiles))
				throw new IOException("saved workspace base file metadata does not match generated outputs");
		}

		if (expectedElementName != null) {
			boolean found = false;
			if (root.has("mod_elements") && root.get("mod_elements").isJsonArray()) {
				for (JsonElement entry : root.getAsJsonArray("mod_elements")) {
					if (entry.isJsonObject()
							&& expectedElementName.equals(optStr(entry.getAsJsonObject(), "name", ""))) {
						found = true;
						break;
					}
				}
			}
			if (!found)
				throw new IOException("saved workspace is missing the created or updated element");
		}
	}

	private static void deleteDirectory(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
	}

	/**
	 * Finds the newest .modstudio_ai / .mcreator file in the given folder and
	 * reads it with a light Gson parse. Does not load the full Workspace object.
	 */
	private static JsonObject readProjectFromFolder(File folder) {
		File[] folderFiles = folder.listFiles(File::isFile);
		if (folderFiles == null)
			return null;
		File wsFile = Arrays.stream(folderFiles)
				.filter(f -> {
					String n = f.getName();
					return n.endsWith(".modstudio_ai") || n.endsWith(".mcreator");
				})
				.max(Comparator.comparingLong(File::lastModified))
				.orElse(null);
		if (wsFile == null)
			return null;

		JsonObject proj = new JsonObject();
		proj.addProperty("folder", folder.getAbsolutePath());
		proj.addProperty("file", wsFile.getName());
		proj.addProperty("file_path", wsFile.getAbsolutePath());
		proj.addProperty("last_modified", wsFile.lastModified());

		try {
			String content = new String(Files.readAllBytes(wsFile.toPath()), StandardCharsets.UTF_8);
			JsonObject root = JsonParser.parseString(content).getAsJsonObject();

			// workspaceSettings
			if (root.has("workspaceSettings") && root.get("workspaceSettings").isJsonObject()) {
				JsonObject s = root.getAsJsonObject("workspaceSettings");
				proj.addProperty("modid", optStr(s, "modid", ""));
				proj.addProperty("name", optStr(s, "modName", optStr(s, "modid", "")));
				proj.addProperty("version", optStr(s, "version", ""));
				proj.addProperty("description", optStr(s, "description", ""));
				proj.addProperty("author", optStr(s, "author", ""));
				proj.addProperty("license", optStr(s, "license", ""));
				proj.addProperty("generator", optStr(s, "currentGenerator", ""));
				proj.addProperty("mod_elements_package", optStr(s, "modElementsPackage", ""));
				proj.addProperty("server_side_only", optBool(s, "serverSideOnly", false));
			}
			// mod_elements (count only)
			int elementCount = 0;
			if (root.has("mod_elements") && root.get("mod_elements").isJsonArray()) {
				elementCount = root.getAsJsonArray("mod_elements").size();
			}
			proj.addProperty("element_count", elementCount);

			// foldersRoot (name; defaults to "~" if empty)
			if (root.has("foldersRoot") && root.get("foldersRoot").isJsonObject()) {
				JsonObject fo = root.getAsJsonObject("foldersRoot");
				proj.addProperty("root_folder_name", optStr(fo, "name", "~"));
			} else {
				proj.addProperty("root_folder_name", "~");
			}

			// mcreator version (created with)
			proj.addProperty("mcreator_version", root.has("mcreatorVersion")
					? root.get("mcreatorVersion").getAsLong() : 0L);

			// multi target manifest, only present for multi version projects
			File manifest = new File(folder, "modstudio.targets.json");
			if (manifest.isFile()) {
				try {
					JsonObject m = JsonParser.parseString(
							new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8))
							.getAsJsonObject();
					if (m.has("targets") && m.get("targets").isJsonArray())
						proj.add("targets", m.getAsJsonArray("targets"));
					proj.addProperty("primary_generator",
							optStr(m, "primaryGenerator", optStr(proj, "generator", "")));
				} catch (Exception me) {
					LOG.error("failed to read targets manifest {}", manifest.getAbsolutePath(), me);
				}
			}
		} catch (Exception e) {
			LOG.error("Failed to parse workspace file {}", wsFile.getAbsolutePath(), e);
			proj.addProperty("parse_error", e.getMessage());
		}

		return proj;
	}

	private static void compileProject(JsonObject args) throws Exception {
		String modid = optStr(args, "modid", "").trim();
		if (modid.isEmpty()) throw new IllegalArgumentException("modid is required");
		File wsFolder = getWorkspaceFolder(args);
		if (wsFolder == null) return;
		File wsFile = findWorkspaceFile(wsFolder);
		if (wsFile == null) throw new FileNotFoundException("workspace file not found");
		Workspace workspace = null;
		ProjectConnection connection = null;
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		AtomicBoolean aborted = new AtomicBoolean();
		try {
			workspace = Workspace.readFromFS(wsFile, null);

			// regenerate the whole workspace before building so cross references like
			// dimension portal blocks resolve their imports against the mod registry
			// classes, matching what the run client path does before launching
			SOURCE_PREPARATION.ensureReady(workspace);

			List<GeneratableElement> elements = new ArrayList<>();
			for (ModElement modElement : workspace.getModElements()) {
				GeneratableElement element = modElement.getGeneratableElement();
				if (element == null)
					throw new IOException("could not load element: " + modElement.getName());
				elements.add(element);
			}

			connection = GradleUtils.getGradleProjectConnection(workspace);
			Workspace activeWorkspace = workspace;
			ProjectConnection activeConnection = connection;
			GenerationCoordinator.regenerateAndLaunchTransactional(wsFolder.toPath(), elements,
					new GenerationCoordinator.Actions() {
						private final Map<GeneratableElement, java.util.Set<String>> previousElements = new LinkedHashMap<>();
						private java.util.Set<String> previousBase;

						@Override public boolean generateElement(GeneratableElement element) throws Exception {
							validateElementInvariants(element, activeWorkspace);
							previousElements.put(element,
									GenerationCoordinator.trackedElementPaths(activeWorkspace, element));
							return GenerationCoordinator.generateElementWithStrictWrites(activeWorkspace.getGenerator(),
									element);
						}

						@Override public void verifyElement(GeneratableElement element) throws Exception {
							GenerationCoordinator.verifyElementStrict(activeWorkspace, element,
									previousElements.get(element));
						}

						@Override public boolean generateBase() throws Exception {
							previousBase = GenerationCoordinator.trackedBasePaths(activeWorkspace);
							return GenerationCoordinator.generateBaseWithStrictWrites(activeWorkspace.getGenerator());
						}

						@Override public void verifyBase() throws Exception {
							GenerationCoordinator.verifyBaseStrict(activeWorkspace, previousBase);
						}

						@Override public void save() throws Exception {
							activeWorkspace.getFileManager().saveWorkspaceDirectlyAndWaitStrict();
						}

						@Override public void abort() throws Exception {
							activeWorkspace.closeWithoutSaveStrict();
							aborted.set(true);
						}

						@Override public void launch() throws Exception {
							// build the launcher inside the transaction so a compile failure rolls back
							BuildLauncher launcher = activeConnection.newBuild().forTasks("compileJava");
							launcher.setStandardOutput(output);
							launcher.setStandardError(errors);
							launcher.run();
						}
					});

			JsonObject data = new JsonObject();
			data.addProperty("status", "compiled");
			data.addProperty("task", "compileJava");
			data.addProperty("output", boundedBuildOutput(output, errors));
			sendResult(data);
		} catch (Exception e) {
			String diagnostics = boundedBuildOutput(output, errors);
			throw new IOException(diagnostics.isBlank() ? e.getMessage() : diagnostics, e);
		} finally {
			if (connection != null) connection.close();
			if (workspace != null && !aborted.get()) workspace.close();
		}
	}

	private static String boundedBuildOutput(ByteArrayOutputStream output, ByteArrayOutputStream errors) {
		String combined = output.toString(StandardCharsets.UTF_8) + errors.toString(StandardCharsets.UTF_8);
		int limit = 16000;
		return combined.length() <= limit ? combined : combined.substring(combined.length() - limit);
	}

	/**
	 * Starts the Minecraft client with Gradle, writing output to the game-output.log file.
	 * Runs on a background thread (started with a new Thread inside the handler).
	 */
	static void prepareGame(WorkspaceTarget target, AtomicBoolean started) throws Exception {
		runGame(target, started);
	}

	static void runGame(WorkspaceTarget target, AtomicBoolean started) throws Exception {
		String modid = target.modid();
		File wsFolder = target.folder();
		File wsFile = target.file();

		writeGameLogDirect(wsFolder, "Loading workspace: " + wsFile.getName());

		Workspace workspace = null;
		ProjectConnection connection = null;
		PrintWriter logWriter = null;
		AtomicBoolean aborted = new AtomicBoolean();
		try {
			workspace = Workspace.readFromFS(wsFile, null);

			String task = workspace.getGeneratorConfiguration().getGradleTaskFor("run_client");
			if (task == null || task.isEmpty())
				throw new IOException("no run client task for generator");

			File logFile = new File(wsFolder, "game-output.log");
			logWriter = new PrintWriter(new FileWriter(logFile));
			PrintWriter lw = logWriter;
			lw.println("Task: " + task);
			lw.flush();

			lw.println("Preparing generator sources...");
			lw.flush();
			SOURCE_PREPARATION.ensureReady(workspace);
			lw.println("Generator sources ready.");
			lw.flush();

			lw.println("Regenerating mod elements with resolved imports...");
			lw.flush();
			List<GeneratableElement> elements = new ArrayList<>();
			for (ModElement modElement : workspace.getModElements()) {
				GeneratableElement element = modElement.getGeneratableElement();
				if (element == null)
					throw new IOException("could not load element: " + modElement.getName());
				elements.add(element);
			}

			new File(wsFolder, "build/classes/java/main").mkdirs();
			new File(wsFolder, "build/resources/main").mkdirs();
			LOG.info("run_game: connecting Gradle for {} task: {}", modid, task);
			connection = GRADLE_CONNECTION.apply(workspace);
			if (connection == null)
				throw new IOException("could not connect to Gradle for run client");
			activeGameConnection = connection;
			Workspace activeWorkspace = workspace;
			ProjectConnection activeConnection = connection;
			int regenCount = GenerationCoordinator.regenerateAndLaunchTransactional(wsFolder.toPath(), elements,
					new GenerationCoordinator.Actions() {
						private final Map<GeneratableElement, java.util.Set<String>> previousElements = new LinkedHashMap<>();
						private java.util.Set<String> previousBase;

						@Override public boolean generateElement(GeneratableElement element) throws Exception {
							validateElementInvariants(element, activeWorkspace);
							previousElements.put(element,
									GenerationCoordinator.trackedElementPaths(activeWorkspace, element));
							return GenerationCoordinator.generateElementWithStrictWrites(activeWorkspace.getGenerator(),
									element);
						}

						@Override public void verifyElement(GeneratableElement element) throws Exception {
							GenerationCoordinator.verifyElementStrict(activeWorkspace, element,
									previousElements.get(element));
						}

						@Override public boolean generateBase() throws Exception {
							previousBase = GenerationCoordinator.trackedBasePaths(activeWorkspace);
							return GenerationCoordinator.generateBaseWithStrictWrites(activeWorkspace.getGenerator());
						}

						@Override public void verifyBase() throws Exception {
							GenerationCoordinator.verifyBaseStrict(activeWorkspace, previousBase);
						}

						@Override public void save() throws Exception {
							activeWorkspace.getFileManager().saveWorkspaceDirectlyAndWaitStrict();
							verifySavedWorkspace(wsFile, modid, activeWorkspace.getModElements(),
									GenerationCoordinator.trackedBasePaths(activeWorkspace), null);
						}

						@Override public void abort() throws Exception {
							activeWorkspace.closeWithoutSaveStrict();
							aborted.set(true);
						}

						@Override public void launch() throws Exception {
							// build the launcher before the thread so a broken launch fails the transaction
							// a run task may be a space separated list like build runClient
							// so split it into separate gradle task names to avoid one bad task
							BuildLauncher launcher = GradleUtils.getGradleTaskLauncher(
									activeWorkspace.getGeneratorConfiguration(), activeConnection,
									task.trim().split("\\s+"));
							launcher.setStandardOutput(new OutputStreamEventHandler(line -> {
								lw.println("[stdout] " + line);
								lw.flush();
							}));
							launcher.setStandardError(new OutputStreamEventHandler(line -> {
								lw.println("[stderr] " + line);
								lw.flush();
							}));
							Thread gameThread = new Thread(() -> {
								try {
									launcher.run();
									lw.println("EXIT_CODE: 0");
								} catch (Exception e) {
									LOG.error("run_game error for " + modid, e);
									lw.println("[stderr] ERROR: " + e.getMessage());
									lw.println("EXIT_CODE: 1");
								} finally {
									lw.flush();
									lw.close();
									activeGameConnection = null;
									try {
										activeConnection.close();
									} catch (Exception ignored) {
									}
									activeWorkspace.close();
								}
							}, "modstudio-game-" + modid);
							gameThread.start();
							started.set(true);
						}
					});
			lw.println("Regenerated " + regenCount + " mod elements.");
			lw.flush();
			workspace = null;
			connection = null;

		} catch (Exception e) {
			LOG.error("run_game preparation error for " + modid, e);
			writeGameLogDirect(wsFolder, "[stderr] ERROR: " + e.getMessage());
			writeGameLogDirect(wsFolder, "EXIT_CODE: 1");
			throw e;
		} finally {
			if (!started.get()) {
				if (logWriter != null) logWriter.close();
				activeGameConnection = null;
				try {
					if (connection != null) connection.close();
				} catch (Exception ignored) {
				}
				if (workspace != null && !aborted.get()) workspace.closeWithoutSaveStrict();
			}
		}
	}

	private static void stopGame() {
		ProjectConnection connection = activeGameConnection;
		if (connection != null) {
			try {
				connection.close();
				sendResult(new JsonObject());
				return;
			} catch (Exception e) {
				sendError("stop_game: " + e.getMessage());
				return;
			}
		}
		sendResult(new JsonObject());
	}

	/**
	 * Writes a line directly to the game-output.log file (even when the workspace is not open).
	 */
	private static void writeGameLogDirect(File wsFolder, String line) {
		try {
			File logFile = new File(wsFolder, "game-output.log");
			PrintWriter w = new PrintWriter(new FileWriter(logFile, true));
			w.println(line);
			w.flush();
			w.close();
		} catch (Exception ignored) {
		}
	}

	/**
	 * Writes an error to game-output.log.
	 */
	private static void writeGameError(File wsFolder, String error) {
		writeGameLogDirect(wsFolder, "[stderr] ERROR: " + error);
		writeGameLogDirect(wsFolder, "EXIT_CODE: 1");
	}

	// ============= RESOURCE MANAGEMENT COMMANDS =============

	private static void listTextures(JsonObject args) {
		String modid = optStr(args, "modid", "").trim();
		if (modid.isEmpty()) { sendError("modid is required"); return; }

		File wsFolder = getWorkspaceFolder(args);
		if (wsFolder == null) return;

		try {
			Workspace workspace = Workspace.readFromFS(Objects.requireNonNull(findWorkspaceFile(wsFolder), "workspace file not found"), null);
			TextureType[] types;
			String typeFilter = optStr(args, "type", "").trim();
			if (!typeFilter.isEmpty()) {
				types = new TextureType[]{TextureType.valueOf(typeFilter.toUpperCase())};
			} else {
				types = TextureType.values();
			}

			JsonArray result = new JsonArray();
			for (TextureType tt : types) {
				List<File> files = workspace.getFolderManager().getTexturesList(tt);
				for (File f : files) {
					JsonObject entry = new JsonObject();
					entry.addProperty("name", f.getName().replace(".png", ""));
					entry.addProperty("type", tt.getID());
					entry.addProperty("path", f.getAbsolutePath());
					entry.addProperty("size", f.length());
					result.add(entry);
				}
			}

			workspace.close();
			JsonObject data = new JsonObject();
			data.add("textures", result);
			data.addProperty("count", result.size());
			sendResult(data);
		} catch (Exception e) {
			LOG.error("list_textures failed", e);
			sendError("list_textures: " + e.getMessage());
		}
	}

	private static void importTexture(JsonObject args) {
		String modid = optStr(args, "modid", "").trim();
		String type = optStr(args, "type", "block").trim();
		String name = optStr(args, "name", "").trim();
		String sourcePath = optStr(args, "source_path", "").trim();
		String dataBase64 = optStr(args, "data_base64", "").trim();

		if (modid.isEmpty() || name.isEmpty()) { sendError("modid and name are required"); return; }
		if (sourcePath.isEmpty() && dataBase64.isEmpty()) { sendError("source_path or data_base64 required"); return; }
		if (!isSafeResourceName(name)) { sendError("invalid texture name"); return; }

		File wsFolder = getWorkspaceFolder(args);
		if (wsFolder == null) return;

		try {
			Workspace workspace = Workspace.readFromFS(Objects.requireNonNull(findWorkspaceFile(wsFolder), "workspace file not found"), null);
			TextureType tt = TextureType.valueOf(type.toUpperCase());
			File target = workspace.getFolderManager().getTextureFile(name, tt);

			if (sourcePath.isEmpty() && !dataBase64.isEmpty()) {
				byte[] bytes = Base64.getDecoder().decode(dataBase64);
				FileIO.writeBytesToFile(bytes, target);
			} else {
				File source = new File(sourcePath);
				if (!source.isFile()) { sendError("source file not found: " + sourcePath); workspace.close(); return; }
				FileIO.copyFile(source, target);
			}

			workspace.close();

			JsonObject data = new JsonObject();
			data.addProperty("name", name);
			data.addProperty("type", tt.getID());
			data.addProperty("path", target.getAbsolutePath());
			sendResult(data);
		} catch (Exception e) {
			LOG.error("import_texture failed", e);
			sendError("import_texture: " + e.getMessage());
		}
	}

	private static void deleteTexture(JsonObject args) {
		String modid = optStr(args, "modid", "").trim();
		String type = optStr(args, "type", "block").trim();
		String name = optStr(args, "name", "").trim();
		if (modid.isEmpty() || name.isEmpty()) { sendError("modid and name are required"); return; }
		if (!isSafeResourceName(name)) { sendError("invalid texture name"); return; }

		File wsFolder = getWorkspaceFolder(args);
		if (wsFolder == null) return;

		try {
			Workspace workspace = Workspace.readFromFS(Objects.requireNonNull(findWorkspaceFile(wsFolder), "workspace file not found"), null);
			TextureType tt = TextureType.valueOf(type.toUpperCase());
			File target = workspace.getFolderManager().getTextureFile(name, tt);
			if (target.isFile() && target.delete()) {
				sendResult(new JsonObject());
			} else {
				sendError("texture not found or could not be deleted");
			}
			workspace.close();
		} catch (Exception e) {
			LOG.error("delete_texture failed", e);
			sendError("delete_texture: " + e.getMessage());
		}
	}

	private static void listSounds(JsonObject args) {
		String modid = optStr(args, "modid", "").trim();
		if (modid.isEmpty()) { sendError("modid is required"); return; }

		File wsFolder = getWorkspaceFolder(args);
		if (wsFolder == null) return;

		try {
			Workspace workspace = Workspace.readFromFS(Objects.requireNonNull(findWorkspaceFile(wsFolder), "workspace file not found"), null);
			Collection<SoundElement> sounds = workspace.getSoundElements();
			JsonArray result = new JsonArray();
			for (SoundElement s : sounds) {
				JsonObject entry = new JsonObject();
				entry.addProperty("name", s.getName());
				entry.addProperty("category", s.getCategory());
				entry.addProperty("subtitle", s.getSubtitle());
				JsonArray files = new JsonArray();
				if (s.getFiles() != null) {
					for (String f : s.getFiles()) files.add(f);
				}
				entry.add("files", files);
				result.add(entry);
			}
			workspace.close();

			// also list raw .ogg files in sounds dir that may not have metadata
			File soundsDir = wsFolder.toPath().resolve("src/main/resources/assets").resolve(modid).resolve("sounds").toFile();
			JsonArray rawOgg = new JsonArray();
			if (soundsDir.isDirectory()) {
				File[] oggFiles = soundsDir.listFiles((d, name) -> name.endsWith(".ogg"));
				if (oggFiles != null) {
					for (File f : oggFiles) {
						rawOgg.add(f.getName());
					}
				}
			}

			JsonObject data = new JsonObject();
			data.add("sounds", result);
			data.add("raw_ogg_files", rawOgg);
			data.addProperty("count", result.size());
			sendResult(data);
		} catch (Exception e) {
			LOG.error("list_sounds failed", e);
			sendError("list_sounds: " + e.getMessage());
		}
	}

	private static void importSound(JsonObject args) {
		String modid = optStr(args, "modid", "").trim();
		String name = optStr(args, "name", "").trim();
		String category = optStr(args, "category", "neutral").trim();
		String subtitle = optStr(args, "subtitle", null);
		String sourcePath = optStr(args, "source_path", "").trim();
		String dataBase64 = optStr(args, "data_base64", "").trim();

		if (modid.isEmpty() || name.isEmpty()) { sendError("modid and name are required"); return; }
		if (sourcePath.isEmpty() && dataBase64.isEmpty()) { sendError("source_path or data_base64 required"); return; }
		if (!isSafeResourceName(name)) { sendError("invalid sound name"); return; }

		File wsFolder = getWorkspaceFolder(args);
		if (wsFolder == null) return;

		try {
			Workspace workspace = Workspace.readFromFS(Objects.requireNonNull(findWorkspaceFile(wsFolder), "workspace file not found"), null);
			File soundsDir = workspace.getFolderManager().getSoundsDir();

			// Copy .ogg file
			String oggName = name.contains("/") ? name.substring(name.lastIndexOf("/") + 1) : name;
			File target = new File(soundsDir, oggName + ".ogg");

			if (sourcePath.isEmpty() && !dataBase64.isEmpty()) {
				byte[] bytes = Base64.getDecoder().decode(dataBase64);
				FileIO.writeBytesToFile(bytes, target);
			} else {
				File source = new File(sourcePath);
				if (!source.isFile()) { sendError("source file not found"); workspace.close(); return; }
				FileIO.copyFile(source, target);
			}

			// Add SoundElement metadata
			SoundElement se = new SoundElement(name, List.of(oggName), category, subtitle);
			workspace.addSoundElement(se);
			workspace.getFileManager().saveWorkspaceDirectlyAndWaitStrict();
			workspace.close();

			JsonObject data = new JsonObject();
			data.addProperty("name", name);
			data.addProperty("ogg_file", oggName + ".ogg");
			sendResult(data);
		} catch (Exception e) {
			LOG.error("import_sound failed", e);
			sendError("import_sound: " + e.getMessage());
		}
	}

	private static void deleteSound(JsonObject args) {
		String modid = optStr(args, "modid", "").trim();
		String name = optStr(args, "name", "").trim();
		if (modid.isEmpty() || name.isEmpty()) { sendError("modid and name are required"); return; }
		if (!isSafeResourceName(name)) { sendError("invalid sound name"); return; }

		File wsFolder = getWorkspaceFolder(args);
		if (wsFolder == null) return;

		try {
			Workspace workspace = Workspace.readFromFS(Objects.requireNonNull(findWorkspaceFile(wsFolder), "workspace file not found"), null);
			Collection<SoundElement> sounds = workspace.getSoundElements();
			for (SoundElement s : sounds) {
				if (s.getName().equals(name)) {
					if (s.getFiles() != null) {
						for (String soundFile : s.getFiles()) {
							File file = resolveWorkspaceResourceFile(wsFolder, soundFile, "sounds");
							if (file != null && file.isFile()) file.delete();
						}
					}
					workspace.removeSoundElement(s);
					workspace.getFileManager().saveWorkspaceDirectlyAndWaitStrict();
					workspace.close();
					sendResult(new JsonObject());
					return;
				}
			}
			workspace.close();
			sendError("sound not found: " + name);
		} catch (Exception e) {
			LOG.error("delete_sound failed", e);
			sendError("delete_sound: " + e.getMessage());
		}
	}

	private static void listModels(JsonObject args) {
		String modid = optStr(args, "modid", "").trim();
		if (modid.isEmpty()) { sendError("modid is required"); return; }

		File wsFolder = getWorkspaceFolder(args);
		if (wsFolder == null) return;

		try {
			Workspace workspace = Workspace.readFromFS(Objects.requireNonNull(findWorkspaceFile(wsFolder), "workspace file not found"), null);
			List<Model> allModels = Model.getModels(workspace);
			List<Model> javaModels = Model.getJavaModels(workspace);

			JsonArray result = new JsonArray();

			for (Model m : allModels) {
				JsonObject entry = new JsonObject();
				entry.addProperty("name", m.getReadableName());
				entry.addProperty("type", m.getType().name());
				entry.addProperty("path", m.getFile().getAbsolutePath());
				entry.addProperty("size", m.getFile().length());
				result.add(entry);
			}
			for (Model m : javaModels) {
				JsonObject entry = new JsonObject();
				entry.addProperty("name", m.getReadableName());
				entry.addProperty("type", "JAVA");
				entry.addProperty("path", m.getFile().getAbsolutePath());
				entry.addProperty("size", m.getFile().length());
				result.add(entry);
			}

			workspace.close();
			JsonObject data = new JsonObject();
			data.add("models", result);
			data.addProperty("count", result.size());
			sendResult(data);
		} catch (Exception e) {
			LOG.error("list_models failed", e);
			sendError("list_models: " + e.getMessage());
		}
	}

	private static void importModel(JsonObject args) {
		String modid = optStr(args, "modid", "").trim();
		String name = optStr(args, "name", "").trim();
		String sourcePath = optStr(args, "source_path", "").trim();
		String dataBase64 = optStr(args, "data_base64", "").trim();

		if (modid.isEmpty() || name.isEmpty()) { sendError("modid and name are required"); return; }
		if (sourcePath.isEmpty() && dataBase64.isEmpty()) { sendError("source_path or data_base64 required"); return; }
		if (!isSafeResourceName(name)) { sendError("invalid model name"); return; }

		File wsFolder = getWorkspaceFolder(args);
		if (wsFolder == null) return;

		try {
			Workspace workspace = Workspace.readFromFS(Objects.requireNonNull(findWorkspaceFile(wsFolder), "workspace file not found"), null);
			File modelsDir = workspace.getFolderManager().getModelsDir();
			File target = new File(modelsDir, name);

			if (sourcePath.isEmpty() && !dataBase64.isEmpty()) {
				byte[] bytes = Base64.getDecoder().decode(dataBase64);
				FileIO.writeBytesToFile(bytes, target);
			} else {
				File source = new File(sourcePath);
				if (!source.isFile()) { sendError("source file not found"); workspace.close(); return; }
				FileIO.copyFile(source, target);
			}

			workspace.close();
			JsonObject data = new JsonObject();
			data.addProperty("name", name);
			data.addProperty("path", target.getAbsolutePath());
			sendResult(data);
		} catch (Exception e) {
			LOG.error("import_model failed", e);
			sendError("import_model: " + e.getMessage());
		}
	}

	private static void deleteModel(JsonObject args) {
		String modid = optStr(args, "modid", "").trim();
		String name = optStr(args, "name", "").trim();
		if (modid.isEmpty() || name.isEmpty()) { sendError("modid and name are required"); return; }
		if (!isSafeResourceName(name)) { sendError("invalid model name"); return; }

		File wsFolder = getWorkspaceFolder(args);
		if (wsFolder == null) return;

		try {
			Workspace workspace = Workspace.readFromFS(Objects.requireNonNull(findWorkspaceFile(wsFolder), "workspace file not found"), null);
			File modelsDir = workspace.getFolderManager().getModelsDir();
			File target = new File(modelsDir, name);
			if (target.isFile() && target.delete()) {
				// Also delete sidecar files if any (.mtl, .textures)
				File mtl = new File(modelsDir, name.replaceFirst("\\.[^.]+$", "") + ".mtl");
				if (mtl.isFile()) mtl.delete();
				File textures = new File(modelsDir, name + ".textures");
				if (textures.isFile()) textures.delete();
				sendResult(new JsonObject());
			} else {
				sendError("model not found or could not be deleted");
			}
			workspace.close();
		} catch (Exception e) {
			LOG.error("delete_model failed", e);
			sendError("delete_model: " + e.getMessage());
		}
	}

	/**
	 * Reads a resource file (texture PNG, model, sound OGG) from disk and returns
	 * its contents as base64 so the frontend can display a preview.
	 */
	private static void readResourceFile(JsonObject args) {
		String path = optStr(args, "path", "").trim();
		if (path.isEmpty()) { sendError("path is required"); return; }

		try {
			File file = resolveResourceFile(args, path);
			if (!file.isFile() || !file.exists()) {
				sendError("file not found: " + path);
				return;
			}

			byte[] bytes;
			try (FileInputStream fis = new FileInputStream(file)) {
				bytes = fis.readAllBytes();
			}

			String mime;
			String lower = file.getName().toLowerCase();
			if (lower.endsWith(".png")) mime = "image/png";
			else if (lower.endsWith(".ogg")) mime = "audio/ogg";
			else if (lower.endsWith(".json")) mime = "application/json";
			else if (lower.endsWith(".obj")) mime = "text/plain";
			else if (lower.endsWith(".java")) mime = "text/plain";
			else if (lower.endsWith(".mtl")) mime = "text/plain";
			else mime = "application/octet-stream";

			String b64 = Base64.getEncoder().encodeToString(bytes);

			JsonObject data = new JsonObject();
			data.addProperty("mime", mime);
			data.addProperty("data", b64);
			data.addProperty("size", file.length());
			data.addProperty("name", file.getName());
			sendResult(data);
		} catch (Exception e) {
			LOG.error("read_resource_file failed", e);
			sendError("read_resource_file: " + e.getMessage());
		}
	}

	// ============= ELEMENT MANAGEMENT COMMANDS =============

	/**
	 * Creates a new mod element in the workspace. Currently supports the "item" type
	 * with basic field mapping. Extensible to other types.
	 */
	static void createElement(JsonObject args) throws Exception {
		Workspace workspace = null;
		WorkspaceJournal journal = null;
		boolean committed = false;
		Exception failure = null;
		try {
		String modid = optStr(args, "modid", "").trim();
		String type = optStr(args, "type", "").trim();
		String name = optStr(args, "name", "").trim();

		if (modid.isEmpty() || type.isEmpty() || name.isEmpty()) {
			sendError("modid, type, and name are required");
			return;
		}
		if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
			sendError("name must be a valid Java identifier");
			return;
		}

		// ensure pascal case for class name generation
		name = toPascalCase(name);

		File wsFolder = getWorkspaceFolder(args);
		if (wsFolder == null) return;

		File wsFile = findWorkspaceFile(wsFolder);
		if (wsFile == null) { sendError("workspace file not found"); return; }

		workspace = Workspace.readFromFS(wsFile, null);
		journal = WorkspaceJournal.capture(wsFolder.toPath());
		SOURCE_PREPARATION.ensureReady(workspace);
			if (workspace.getModElementByName(name) != null) {
				workspace.closeWithoutSaveStrict();
				workspace = null;
				sendError("element already exists: " + name);
				return;
			}

		// Find ModElementType by registry name
		ModElementType<?> elementType = ModElementTypeLoader.getModElementType(type);

		// Create ModElement (lightweight descriptor)
		ModElement modElement = new ModElement(workspace, name, elementType);

		// Create GeneratableElement (the actual data model)
		GeneratableElement element = (GeneratableElement) elementType.getModElementStorageClass()
				.getConstructor(ModElement.class).newInstance(modElement);

		// Set required defaults (some fields are not initialized by the constructor)
		if (element instanceof net.mcreator.element.types.Item item) {
			setItemDefaults(item, workspace);
		} else if (element instanceof net.mcreator.element.types.Block block) {
			setBlockDefaults(block, workspace);
		} else if (element instanceof net.mcreator.element.types.Armor armor) {
			setArmorDefaults(armor, workspace);
		} else if (element instanceof net.mcreator.element.types.Biome biome) {
			setBiomeDefaults(biome, workspace);
		} else if (element instanceof net.mcreator.element.types.Tool tool) {
			setToolDefaults(tool, workspace);
		} else if (element instanceof net.mcreator.element.types.Fluid fluid) {
			setFluidDefaults(fluid, workspace);
		} else if (element instanceof net.mcreator.element.types.Command command) {
			setCommandDefaults(command, workspace);
		} else if (element instanceof net.mcreator.element.types.Function function) {
			setFunctionDefaults(function, workspace);
		} else if (element instanceof net.mcreator.element.types.CustomElement customElement) {
			setCustomElementDefaults(customElement);
		} else if (element instanceof net.mcreator.element.types.KeyBinding keyBinding) {
			setKeyBindingDefaults(keyBinding, workspace);
		} else if (element instanceof net.mcreator.element.types.Tab tab) {
			setTabDefaults(tab, workspace);
		} else if (element instanceof net.mcreator.element.types.Painting painting) {
			setPaintingDefaults(painting, workspace);
		} else if (element instanceof net.mcreator.element.types.Plant plant) {
			setPlantDefaults(plant, workspace);
		} else if (element instanceof net.mcreator.element.types.Dimension dimension) {
			setDimensionDefaults(dimension, workspace);
		} else if (element instanceof net.mcreator.element.types.Enchantment enchantment) {
			setEnchantmentDefaults(enchantment, workspace);
		} else if (element instanceof net.mcreator.element.types.Particle particle) {
			setParticleDefaults(particle, workspace);
		} else if (element instanceof net.mcreator.element.types.PotionEffect potionEffect) {
			setPotionEffectDefaults(potionEffect, workspace);
		} else if (element instanceof net.mcreator.element.types.Potion potion) {
			setPotionDefaults(potion, workspace);
		} else if (element instanceof net.mcreator.element.types.LivingEntity livingEntity) {
			setLivingEntityDefaults(livingEntity, workspace);
		} else if (element instanceof net.mcreator.element.types.Recipe recipe) {
			setRecipeDefaults(recipe, workspace);
		} else if (element instanceof net.mcreator.element.types.VillagerTrade villagerTrade) {
			setVillagerTradeDefaults(villagerTrade, workspace);
		} else if (element instanceof net.mcreator.element.types.Achievement achievement) {
			setAchievementDefaults(achievement, workspace);
		} else if (element instanceof net.mcreator.element.types.LootTable lootTable) {
			setLootTableDefaults(lootTable, workspace);
		}

		// Apply fields from JSON args
		if (args.has("fields") && args.get("fields").isJsonObject()) {
			JsonObject fields = args.getAsJsonObject("fields");
			storeCustomProcedureSelections(modElement, fields);

			// Use specific type handling for known element types
			if (element instanceof net.mcreator.element.types.Item item) {
				applyItemFields(item, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Block block) {
				applyBlockFields(block, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Armor armor) {
				applyArmorFields(armor, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Biome biome) {
				applyBiomeFields(biome, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Tool tool) {
				applyToolFields(tool, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Fluid fluid) {
				applyFluidFields(fluid, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Command command) {
				applyCommandFields(command, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Function function) {
				applyFunctionFields(function, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.CustomElement customElement) {
				applyCustomElementFields(customElement, fields);
			} else if (element instanceof net.mcreator.element.types.KeyBinding keyBinding) {
				applyKeyBindingFields(keyBinding, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Tab tab) {
				applyTabFields(tab, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Painting painting) {
				applyPaintingFields(painting, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Plant plant) {
				applyPlantFields(plant, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Dimension dimension) {
				applyDimensionFields(dimension, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Enchantment enchantment) {
				applyEnchantmentFields(enchantment, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Particle particle) {
				applyParticleFields(particle, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.PotionEffect potionEffect) {
				applyPotionEffectFields(potionEffect, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Potion potion) {
				applyPotionFields(potion, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.LivingEntity livingEntity) {
				applyLivingEntityFields(livingEntity, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Recipe recipe) {
				applyRecipeFields(recipe, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.VillagerTrade villagerTrade) {
				applyVillagerTradeFields(villagerTrade, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Achievement achievement) {
				applyAchievementFields(achievement, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.LootTable lootTable) {
				applyLootTableFields(lootTable, workspace, fields);
			}
			applyProcedureFieldsReflectively(element, fields);
		}

		validateElementInvariants(element, workspace);

		sendProgress("Adding element to workspace...", 50);
		workspace.addModElement(element.getModElement());

		sendProgress("Generating code...", 70);
		GenerationCoordinator.generateElementStrict(workspace, element);

		sendProgress("Saving element picture...", 78);
		workspace.getModElementManager().storeModElementPictureStrict(element);

		sendProgress("Saving element data...", 82);
		workspace.getModElementManager().storeModElementStrict(element);

		// persist element before base regen so global registries see it on disk
		sendProgress("Updating registries...", 88);
		GenerationCoordinator.generateBaseStrict(workspace);

		sendProgress("Saving workspace...", 95);
		workspace.getFileManager().saveWorkspaceDirectlyAndWaitStrict();
		verifySavedWorkspace(wsFile, modid, workspace.getModElements(),
				GenerationCoordinator.trackedBasePaths(workspace), name);

		JsonObject result = new JsonObject();
		result.addProperty("name", name);
		result.addProperty("type", type);
		result.addProperty("registry_name", modElement.getRegistryName());
		result.addProperty("modid", modid);

		workspace.closeStrict();
		workspace = null;
		committed = true;
		sendResult(result);
		} catch (Exception e) {
			failure = e;
			throw e;
		} finally {
			Exception rollbackError = null;
			if (workspace != null) {
				try {
					if (committed)
						workspace.close();
					else
						workspace.closeWithoutSaveStrict();
				} catch (Exception e) {
					rollbackError = e;
				}
			}
			if (!committed && journal != null) {
				try {
					journal.restore();
				} catch (Exception e) {
					if (rollbackError == null) rollbackError = e;
					else rollbackError.addSuppressed(e);
				}
			}
			if (rollbackError != null) {
				if (failure != null) failure.addSuppressed(rollbackError);
				else throw rollbackError;
			}
		}
	}

	/**
	 * Sets required default values for Item fields that are not initialized by the constructor.
	 */
	private static void setItemDefaults(net.mcreator.element.types.Item item, Workspace workspace) {
		if (item.name == null) item.name = "";
		if (item.customModelName == null) item.customModelName = "Normal";
		if (item.rarity == null) item.rarity = "COMMON";
		if (item.creativeTabs == null || item.creativeTabs.isEmpty())
			item.creativeTabs = new java.util.ArrayList<>(java.util.List.of(
					new net.mcreator.element.parts.TabEntry(workspace, "misc")));
		if (item.repairItems == null) item.repairItems = new java.util.ArrayList<>();
		if (item.customProperties == null) item.customProperties = new java.util.LinkedHashMap<>();
		if (item.states == null) item.states = new java.util.ArrayList<>();
		if (item.animations == null) item.animations = new java.util.ArrayList<>();
		if (item.providedBannerPatterns == null) item.providedBannerPatterns = new java.util.ArrayList<>();
		if (item.attributeModifiers == null) item.attributeModifiers = new java.util.ArrayList<>();
		item.renderType = 0; // BUILTIN model type
	}

	/**
	 * Populates an Item GeneratableElement fields from a JSON fields object.
	 */
	private static void applyItemFields(net.mcreator.element.types.Item item, Workspace workspace,
										 JsonObject fields) {
		if (fields.has("name"))
			item.name = fields.get("name").getAsString();
		if (fields.has("rarity"))
			item.rarity = fields.get("rarity").getAsString();
		if (fields.has("customModelName"))
			item.customModelName = fields.get("customModelName").getAsString();
		if (fields.has("stackSize"))
			item.stackSize = fields.get("stackSize").getAsInt();
		if (fields.has("enchantability"))
			item.enchantability = fields.get("enchantability").getAsInt();
		if (fields.has("useDuration"))
			item.useDuration = fields.get("useDuration").getAsInt();
		if (fields.has("damageCount"))
			item.damageCount = fields.get("damageCount").getAsInt();
		if (fields.has("toolType"))
			item.toolType = fields.get("toolType").getAsDouble();
		if (fields.has("destroyAnyBlock"))
			item.destroyAnyBlock = fields.get("destroyAnyBlock").getAsBoolean();
		if (fields.has("immuneToFire"))
			item.immuneToFire = fields.get("immuneToFire").getAsBoolean();
		if (fields.has("isPiglinCurrency"))
			item.isPiglinCurrency = fields.get("isPiglinCurrency").getAsBoolean();
		if (fields.has("stayInGridWhenCrafting"))
			item.stayInGridWhenCrafting = fields.get("stayInGridWhenCrafting").getAsBoolean();
		if (fields.has("damageOnCrafting"))
			item.damageOnCrafting = fields.get("damageOnCrafting").getAsBoolean();
		if (fields.has("enableMeleeDamage"))
			item.enableMeleeDamage = fields.get("enableMeleeDamage").getAsBoolean();
		if (fields.has("damageVsEntity"))
			item.damageVsEntity = fields.get("damageVsEntity").getAsDouble();
		if (fields.has("attackSpeed"))
			item.attackSpeed = fields.get("attackSpeed").getAsDouble();

		// Texture
		if (fields.has("texture") && !fields.get("texture").getAsString().isEmpty()) {
			item.texture = new TextureHolder(workspace, fields.get("texture").getAsString());
		}
		// GUI texture
		if (fields.has("guiTexture") && !fields.get("guiTexture").getAsString().isEmpty()) {
			item.guiTexture = new TextureHolder(workspace, fields.get("guiTexture").getAsString());
		}
		// Recipe remainder
		if (fields.has("recipeRemainder") && !fields.get("recipeRemainder").getAsString().isEmpty()) {
			String converted = convertItemValue(workspace, fields.get("recipeRemainder").getAsString());
			item.recipeRemainder = new net.mcreator.element.parts.MItemBlock(workspace, converted);
		}

		// Food properties
		if (fields.has("isFood"))
			item.isFood = fields.get("isFood").getAsBoolean();
		if (fields.has("nutritionalValue"))
			item.nutritionalValue = fields.get("nutritionalValue").getAsInt();
		if (fields.has("saturation"))
			item.saturation = fields.get("saturation").getAsDouble();
		if (fields.has("isMeat"))
			item.isMeat = fields.get("isMeat").getAsBoolean();
		if (fields.has("isAlwaysEdible"))
			item.isAlwaysEdible = fields.get("isAlwaysEdible").getAsBoolean();
		if (fields.has("eatResultItem") && !fields.get("eatResultItem").getAsString().isEmpty()) {
			String converted = convertItemValue(workspace, fields.get("eatResultItem").getAsString());
			item.eatResultItem = new net.mcreator.element.parts.MItemBlock(workspace, converted);
		}

		// Music disc
		if (fields.has("isMusicDisc"))
			item.isMusicDisc = fields.get("isMusicDisc").getAsBoolean();
		// auto-detect music disc from music field
		if (fields.has("music") && !fields.get("music").getAsString().isEmpty()) {
			item.isMusicDisc = true;
			try { item.musicDiscMusic = new net.mcreator.element.parts.Sound(workspace, fields.get("music").getAsString()); }
			catch (Exception e) { LOG.warn("applyItemFields: could not set music disc sound", e); }
		}
		if (fields.has("musicDiscMusic") && !fields.get("musicDiscMusic").getAsString().isEmpty()) {
			try { item.musicDiscMusic = new net.mcreator.element.parts.Sound(workspace, fields.get("musicDiscMusic").getAsString()); }
			catch (Exception e) { LOG.warn("applyItemFields: could not set music disc sound", e); }
		}
		if (fields.has("description"))
			item.musicDiscDescription = fields.get("description").getAsString();
		if (fields.has("musicDiscDescription"))
			item.musicDiscDescription = fields.get("musicDiscDescription").getAsString();
		if (fields.has("lengthInTicks"))
			item.musicDiscLengthInTicks = fields.get("lengthInTicks").getAsInt();
		if (fields.has("musicDiscLengthInTicks"))
			item.musicDiscLengthInTicks = fields.get("musicDiscLengthInTicks").getAsInt();
		if (fields.has("analogOutput"))
			item.musicDiscAnalogOutput = fields.get("analogOutput").getAsInt();
		if (fields.has("musicDiscAnalogOutput"))
			item.musicDiscAnalogOutput = fields.get("musicDiscAnalogOutput").getAsInt();
		// isMusicDisc=true requires a valid sound; if none was provided (e.g.
		// the frontend skipped it) fall back to a vanilla sound so element
		// generation does not crash (musicdisc.json.ftl reads data.musicDiscMusic)
		if (item.isMusicDisc && item.musicDiscMusic == null) {
			try {
				item.musicDiscMusic = new net.mcreator.element.parts.Sound(workspace, "block.note_block.banjo");
			} catch (Exception e) {
				LOG.warn("applyItemFields: could not set default music disc sound", e);
			}
		}
		if (item.isMusicDisc && item.musicDiscDescription == null) item.musicDiscDescription = "";

		// Creative tab (for music disc and other item subtypes)
		if (fields.has("creativeTab") && !fields.get("creativeTab").getAsString().isEmpty()) {
			item.creativeTabs.clear();
			item.creativeTabs.add(new net.mcreator.element.parts.TabEntry(workspace, fields.get("creativeTab").getAsString()));
		}

		// Animation
		if (fields.has("animation") && !fields.get("animation").getAsString().isEmpty()) {
			try { item.animation = new net.mcreator.element.parts.ItemUseAnimation(workspace, fields.get("animation").getAsString()); }
			catch (Exception e) { LOG.warn("applyItemFields: could not set animation", e); }
		}
		// animation is required by item.java.ftl (hasNonDefaultAnimation()); configs
		// without an animation input (e.g. music-disc) leave it null otherwise
		if (item.animation == null) {
			try {
				item.animation = new net.mcreator.element.parts.ItemUseAnimation(workspace, item.isFood ? "eat" : "none");
			} catch (Exception e) {
				LOG.warn("applyItemFields: could not set default animation", e);
			}
		}

		// ranged-item frontend config: projectile properties are persist-only
		// (MCreator keeps these on the Projectile element, not on the item)
		if (fields.has("damage"))
			item.damage = fields.get("damage").getAsDouble();
		if (fields.has("knockback"))
			item.knockback = fields.get("knockback").getAsDouble();
		if (fields.has("projectileTexture") && !fields.get("projectileTexture").getAsString().isEmpty())
			item.projectileTexture = fields.get("projectileTexture").getAsString();
		// customModel (Normal/Bow/Crossbow) maps to the item use animation
		if (fields.has("customModel") && !fields.get("customModel").getAsString().isEmpty()) {
			String cm = fields.get("customModel").getAsString();
			String anim = "none";
			if (cm.equalsIgnoreCase("Bow")) anim = "bow";
			else if (cm.equalsIgnoreCase("Crossbow")) anim = "crossbow";
			try {
				item.animation = new net.mcreator.element.parts.ItemUseAnimation(workspace, anim);
			} catch (Exception e) {
				LOG.warn("applyItemFields: could not set animation from customModel", e);
			}
		}
	}

	private static void setArmorDefaults(net.mcreator.element.types.Armor armor, Workspace workspace) {
		if (armor.rarity == null) armor.rarity = "COMMON";
		if (armor.creativeTabs == null) armor.creativeTabs = new java.util.ArrayList<>();
		if (armor.creativeTabs.isEmpty()) {
			armor.creativeTabs.add(new net.mcreator.element.parts.TabEntry(workspace, "COMBAT"));
		}
		if (armor.repairItems == null) armor.repairItems = new java.util.ArrayList<>();
		if (armor.attributeModifiers == null) armor.attributeModifiers = new java.util.ArrayList<>();
		if (armor.helmetModelName == null) armor.helmetModelName = "Default";
		if (armor.bodyModelName == null) armor.bodyModelName = "Default";
		if (armor.leggingsModelName == null) armor.leggingsModelName = "Default";
		if (armor.bootsModelName == null) armor.bootsModelName = "Default";

		// armorTextureFile must not be null or the ftl template crashes
		if (armor.armorTextureFile == null || armor.armorTextureFile.isEmpty()) {
			armor.armorTextureFile = armor.getModElement().getRegistryName();
		}

		if (armor.helmetName == null) armor.helmetName = "";
		if (armor.bodyName == null) armor.bodyName = "";
		if (armor.leggingsName == null) armor.leggingsName = "";
		if (armor.bootsName == null) armor.bootsName = "";

		if (armor.equipSound == null) {
			try {
				armor.equipSound = new net.mcreator.element.parts.Sound(workspace, "item.armor.equip_iron");
			} catch (Exception e) {
				LOG.warn("setArmorDefaults: could not set default equip sound", e);
			}
		}

		if (armor.helmetSpecialInformation == null)
			armor.helmetSpecialInformation = new net.mcreator.element.parts.procedure.StringListProcedure(null, java.util.List.of());
		if (armor.bodySpecialInformation == null)
			armor.bodySpecialInformation = new net.mcreator.element.parts.procedure.StringListProcedure(null, java.util.List.of());
		if (armor.leggingsSpecialInformation == null)
			armor.leggingsSpecialInformation = new net.mcreator.element.parts.procedure.StringListProcedure(null, java.util.List.of());
		if (armor.bootsSpecialInformation == null)
			armor.bootsSpecialInformation = new net.mcreator.element.parts.procedure.StringListProcedure(null, java.util.List.of());

		if (armor.helmetGlowCondition == null)
			armor.helmetGlowCondition = new net.mcreator.element.parts.procedure.LogicProcedure(null, false);
		if (armor.bodyGlowCondition == null)
			armor.bodyGlowCondition = new net.mcreator.element.parts.procedure.LogicProcedure(null, false);
		if (armor.leggingsGlowCondition == null)
			armor.leggingsGlowCondition = new net.mcreator.element.parts.procedure.LogicProcedure(null, false);
		if (armor.bootsGlowCondition == null)
			armor.bootsGlowCondition = new net.mcreator.element.parts.procedure.LogicProcedure(null, false);

		if (armor.helmetPiglinNeutral == null)
			armor.helmetPiglinNeutral = new net.mcreator.element.parts.procedure.LogicProcedure(null, false);
		if (armor.bodyPiglinNeutral == null)
			armor.bodyPiglinNeutral = new net.mcreator.element.parts.procedure.LogicProcedure(null, false);
		if (armor.leggingsPiglinNeutral == null)
			armor.leggingsPiglinNeutral = new net.mcreator.element.parts.procedure.LogicProcedure(null, false);
		if (armor.bootsPiglinNeutral == null)
			armor.bootsPiglinNeutral = new net.mcreator.element.parts.procedure.LogicProcedure(null, false);
	}

	private static void applyArmorFields(net.mcreator.element.types.Armor armor, Workspace workspace, JsonObject fields) {
		// helmet
		if (fields.has("enable_helmet")) armor.enableHelmet = fields.get("enable_helmet").getAsBoolean();
		if (fields.has("texture_helmet") && !fields.get("texture_helmet").getAsString().isEmpty())
			armor.textureHelmet = new TextureHolder(workspace, fields.get("texture_helmet").getAsString());
		if (fields.has("helmet_name")) armor.helmetName = fields.get("helmet_name").getAsString();
		if (fields.has("helmet_immune_to_fire")) armor.helmetImmuneToFire = fields.get("helmet_immune_to_fire").getAsBoolean();
		if (fields.has("helmet_glow_effect"))
			armor.helmetGlowCondition = new net.mcreator.element.parts.procedure.LogicProcedure(null, fields.get("helmet_glow_effect").getAsBoolean());
		if (fields.has("helmet_piglin_neutral"))
			armor.helmetPiglinNeutral = new net.mcreator.element.parts.procedure.LogicProcedure(null, fields.get("helmet_piglin_neutral").getAsBoolean());
		if (fields.has("helmet_special_info") && !fields.get("helmet_special_info").getAsString().isEmpty())
			armor.helmetSpecialInformation = new net.mcreator.element.parts.procedure.StringListProcedure(null, java.util.List.of(fields.get("helmet_special_info").getAsString()));
		if (fields.has("helmet_item_render_type"))
			armor.helmetItemRenderType = fields.get("helmet_item_render_type").getAsInt();
		if (fields.has("helmet_custom_model_name"))
			armor.helmetItemCustomModelName = fields.get("helmet_custom_model_name").getAsString();

		// chestplate (frontend uses "chestplate", java field is "body")
		if (fields.has("enable_chestplate")) armor.enableBody = fields.get("enable_chestplate").getAsBoolean();
		else if (fields.has("enable_body")) armor.enableBody = fields.get("enable_body").getAsBoolean();
		if (fields.has("texture_chestplate") && !fields.get("texture_chestplate").getAsString().isEmpty())
			armor.textureBody = new TextureHolder(workspace, fields.get("texture_chestplate").getAsString());
		else if (fields.has("texture_body") && !fields.get("texture_body").getAsString().isEmpty())
			armor.textureBody = new TextureHolder(workspace, fields.get("texture_body").getAsString());
		if (fields.has("chestplate_name")) armor.bodyName = fields.get("chestplate_name").getAsString();
		else if (fields.has("body_name")) armor.bodyName = fields.get("body_name").getAsString();
		if (fields.has("chestplate_immune_to_fire")) armor.bodyImmuneToFire = fields.get("chestplate_immune_to_fire").getAsBoolean();
		else if (fields.has("body_immune_to_fire")) armor.bodyImmuneToFire = fields.get("body_immune_to_fire").getAsBoolean();
		if (fields.has("chestplate_glow_effect"))
			armor.bodyGlowCondition = new net.mcreator.element.parts.procedure.LogicProcedure(null, fields.get("chestplate_glow_effect").getAsBoolean());
		else if (fields.has("body_glow_effect"))
			armor.bodyGlowCondition = new net.mcreator.element.parts.procedure.LogicProcedure(null, fields.get("body_glow_effect").getAsBoolean());
		if (fields.has("chestplate_piglin_neutral"))
			armor.bodyPiglinNeutral = new net.mcreator.element.parts.procedure.LogicProcedure(null, fields.get("chestplate_piglin_neutral").getAsBoolean());
		else if (fields.has("body_piglin_neutral"))
			armor.bodyPiglinNeutral = new net.mcreator.element.parts.procedure.LogicProcedure(null, fields.get("body_piglin_neutral").getAsBoolean());
		if (fields.has("chestplate_special_info") && !fields.get("chestplate_special_info").getAsString().isEmpty())
			armor.bodySpecialInformation = new net.mcreator.element.parts.procedure.StringListProcedure(null, java.util.List.of(fields.get("chestplate_special_info").getAsString()));
		else if (fields.has("body_special_info") && !fields.get("body_special_info").getAsString().isEmpty())
			armor.bodySpecialInformation = new net.mcreator.element.parts.procedure.StringListProcedure(null, java.util.List.of(fields.get("body_special_info").getAsString()));
		if (fields.has("chestplate_item_render_type"))
			armor.bodyItemRenderType = fields.get("chestplate_item_render_type").getAsInt();
		else if (fields.has("body_item_render_type"))
			armor.bodyItemRenderType = fields.get("body_item_render_type").getAsInt();
		if (fields.has("chestplate_custom_model_name"))
			armor.bodyItemCustomModelName = fields.get("chestplate_custom_model_name").getAsString();
		else if (fields.has("body_custom_model_name"))
			armor.bodyItemCustomModelName = fields.get("body_custom_model_name").getAsString();

		// leggings
		if (fields.has("enable_leggings")) armor.enableLeggings = fields.get("enable_leggings").getAsBoolean();
		if (fields.has("texture_leggings") && !fields.get("texture_leggings").getAsString().isEmpty())
			armor.textureLeggings = new TextureHolder(workspace, fields.get("texture_leggings").getAsString());
		if (fields.has("leggings_name")) armor.leggingsName = fields.get("leggings_name").getAsString();
		if (fields.has("leggings_immune_to_fire")) armor.leggingsImmuneToFire = fields.get("leggings_immune_to_fire").getAsBoolean();
		if (fields.has("leggings_glow_effect"))
			armor.leggingsGlowCondition = new net.mcreator.element.parts.procedure.LogicProcedure(null, fields.get("leggings_glow_effect").getAsBoolean());
		if (fields.has("leggings_piglin_neutral"))
			armor.leggingsPiglinNeutral = new net.mcreator.element.parts.procedure.LogicProcedure(null, fields.get("leggings_piglin_neutral").getAsBoolean());
		if (fields.has("leggings_special_info") && !fields.get("leggings_special_info").getAsString().isEmpty())
			armor.leggingsSpecialInformation = new net.mcreator.element.parts.procedure.StringListProcedure(null, java.util.List.of(fields.get("leggings_special_info").getAsString()));
		if (fields.has("leggings_item_render_type"))
			armor.leggingsItemRenderType = fields.get("leggings_item_render_type").getAsInt();
		if (fields.has("leggings_custom_model_name"))
			armor.leggingsItemCustomModelName = fields.get("leggings_custom_model_name").getAsString();

		// boots
		if (fields.has("enable_boots")) armor.enableBoots = fields.get("enable_boots").getAsBoolean();
		if (fields.has("texture_boots") && !fields.get("texture_boots").getAsString().isEmpty())
			armor.textureBoots = new TextureHolder(workspace, fields.get("texture_boots").getAsString());
		if (fields.has("boots_name")) armor.bootsName = fields.get("boots_name").getAsString();
		if (fields.has("boots_immune_to_fire")) armor.bootsImmuneToFire = fields.get("boots_immune_to_fire").getAsBoolean();
		if (fields.has("boots_glow_effect"))
			armor.bootsGlowCondition = new net.mcreator.element.parts.procedure.LogicProcedure(null, fields.get("boots_glow_effect").getAsBoolean());
		if (fields.has("boots_piglin_neutral"))
			armor.bootsPiglinNeutral = new net.mcreator.element.parts.procedure.LogicProcedure(null, fields.get("boots_piglin_neutral").getAsBoolean());
		if (fields.has("boots_special_info") && !fields.get("boots_special_info").getAsString().isEmpty())
			armor.bootsSpecialInformation = new net.mcreator.element.parts.procedure.StringListProcedure(null, java.util.List.of(fields.get("boots_special_info").getAsString()));
		if (fields.has("boots_item_render_type"))
			armor.bootsItemRenderType = fields.get("boots_item_render_type").getAsInt();
		if (fields.has("boots_custom_model_name"))
			armor.bootsItemCustomModelName = fields.get("boots_custom_model_name").getAsString();

		// armor layer texture
		if (fields.has("armor_layer_texture")) armor.armorTextureFile = fields.get("armor_layer_texture").getAsString();
		applyArmorLayerTextures(armor, workspace, fields);

		// properties
		if (fields.has("rarity")) armor.rarity = fields.get("rarity").getAsString();
		if (fields.has("creative_tabs") && !fields.get("creative_tabs").getAsString().isEmpty()) {
			armor.creativeTabs.clear();
			armor.creativeTabs.add(new net.mcreator.element.parts.TabEntry(workspace, fields.get("creative_tabs").getAsString()));
		}
		if (fields.has("equip_sound") && !fields.get("equip_sound").getAsString().isEmpty()) {
			try {
				armor.equipSound = new net.mcreator.element.parts.Sound(workspace, fields.get("equip_sound").getAsString());
			} catch (Exception e) {
				LOG.warn("applyArmorFields: could not set equip sound", e);
			}
		}
		if (fields.has("max_damage")) armor.maxDamage = fields.get("max_damage").getAsInt();
		if (fields.has("damage_value_helmet")) armor.damageValueHelmet = fields.get("damage_value_helmet").getAsInt();
		if (fields.has("damage_value_chestplate")) armor.damageValueBody = fields.get("damage_value_chestplate").getAsInt();
		else if (fields.has("damage_value_body")) armor.damageValueBody = fields.get("damage_value_body").getAsInt();
		if (fields.has("damage_value_leggings")) armor.damageValueLeggings = fields.get("damage_value_leggings").getAsInt();
		if (fields.has("damage_value_boots")) armor.damageValueBoots = fields.get("damage_value_boots").getAsInt();
		if (fields.has("enchantability")) armor.enchantability = fields.get("enchantability").getAsInt();
		if (fields.has("toughness")) armor.toughness = fields.get("toughness").getAsDouble();
		if (fields.has("knockback_resistance")) armor.knockbackResistance = fields.get("knockback_resistance").getAsDouble();

		// repair items
		if (fields.has("repair_items")) {
			JsonElement riEl = fields.get("repair_items");
			java.util.List<net.mcreator.element.parts.MItemBlock> repairList = new java.util.ArrayList<>();
			if (riEl.isJsonArray()) {
				for (JsonElement e : riEl.getAsJsonArray()) {
					String s = e.isJsonPrimitive() ? e.getAsString() : "";
					if (!s.isEmpty())
						repairList.add(new net.mcreator.element.parts.MItemBlock(workspace, convertItemValue(workspace, s)));
				}
			} else if (riEl.isJsonPrimitive() && !riEl.getAsString().isEmpty()) {
				repairList.add(new net.mcreator.element.parts.MItemBlock(workspace, convertItemValue(workspace, riEl.getAsString())));
			}
			if (!repairList.isEmpty()) armor.repairItems = repairList;
		}

		// triggers
		if (fields.has("onHelmetTick") && !fields.get("onHelmetTick").getAsString().isEmpty())
			armor.onHelmetTick = new net.mcreator.element.parts.procedure.Procedure(fields.get("onHelmetTick").getAsString());
		if (fields.has("onChestplateTick") && !fields.get("onChestplateTick").getAsString().isEmpty())
			armor.onBodyTick = new net.mcreator.element.parts.procedure.Procedure(fields.get("onChestplateTick").getAsString());
		else if (fields.has("onBodyTick") && !fields.get("onBodyTick").getAsString().isEmpty())
			armor.onBodyTick = new net.mcreator.element.parts.procedure.Procedure(fields.get("onBodyTick").getAsString());
		if (fields.has("onLeggingsTick") && !fields.get("onLeggingsTick").getAsString().isEmpty())
			armor.onLeggingsTick = new net.mcreator.element.parts.procedure.Procedure(fields.get("onLeggingsTick").getAsString());
		if (fields.has("onBootsTick") && !fields.get("onBootsTick").getAsString().isEmpty())
			armor.onBootsTick = new net.mcreator.element.parts.procedure.Procedure(fields.get("onBootsTick").getAsString());
	}

	// Combines the two frontend armor layer slots into the single armorTextureFile
	// used by generators. The layer_1 texture provides the base name; the layer_2
	// texture is kept as <base>_layer_2, copying it if the user picked a different file.
	private static void applyArmorLayerTextures(net.mcreator.element.types.Armor armor, Workspace workspace, JsonObject fields) {
		String l1 = optStr(fields, "armor_layer_texture_1", "");
		String l2 = optStr(fields, "armor_layer_texture_2", "");
		if (l1.isEmpty()) return;
		String base = l1.endsWith("_layer_1") ? l1.substring(0, l1.length() - "_layer_1".length()) : l1;
		armor.armorTextureFile = base;
		if (l2.isEmpty()) return;
		String expected = base + "_layer_2";
		if (l2.equals(expected)) return;
		try {
			File src = workspace.getFolderManager().getTextureFile(l2, net.mcreator.workspace.resources.TextureType.ARMOR);
			File dst = workspace.getFolderManager().getTextureFile(expected, net.mcreator.workspace.resources.TextureType.ARMOR);
			if (src.exists() && !dst.exists()) {
				net.mcreator.io.FileIO.copyFile(src, dst);
			}
		} catch (Exception e) {
			LOG.error("Failed to merge armor layer 2 texture", e);
		}
	}

	private static void setBlockDefaults(net.mcreator.element.types.Block block, Workspace workspace) {
		if (block.name == null) block.name = "";
		if (block.customModelName == null) block.customModelName = "Normal";
		if (block.renderType == 0) block.renderType = 10;
		if (block.blockSetType == null) block.blockSetType = "OAK";
		if (block.colorOnMap == null) block.colorOnMap = new net.mcreator.element.parts.MapColor(workspace, "DEFAULT");
		if (block.noteBlockInstrument == null) block.noteBlockInstrument = new net.mcreator.element.parts.NoteBlockInstrument(workspace, "harp");
		if (block.offsetType == null) block.offsetType = "NONE";
		if (block.aiPathNodeType == null) block.aiPathNodeType = new net.mcreator.element.parts.AIPathNodeType(workspace, "DEFAULT");
		if (block.rarity == null) block.rarity = "COMMON";
		if (block.destroyTool == null) block.destroyTool = "Not specified";
		if (block.vanillaToolTier == null) block.vanillaToolTier = "NONE";
		if (block.soundOnStep == null) block.soundOnStep = new net.mcreator.element.parts.StepSound(workspace, "GROUND");
		if (block.transparencyType == null) block.transparencyType = "SOLID";
		if (block.tintType == null) block.tintType = "No tint";
		if (block.reactionToPushing == null) block.reactionToPushing = "NORMAL";
		if (block.generationShape == null) block.generationShape = "UNIFORM";
		if (block.luminance == null) block.luminance = new net.mcreator.element.parts.procedure.NumberProcedure(null, 0);
		if (block.emittedRedstonePower == null) block.emittedRedstonePower = new net.mcreator.element.parts.procedure.NumberProcedure(null, 0);
		if (block.creativeTabs == null || block.creativeTabs.isEmpty()) {
			block.creativeTabs = new java.util.ArrayList<>(java.util.List.of(
					new net.mcreator.element.parts.TabEntry(workspace, "BUILDING_BLOCKS")));
		}
		if (block.states == null) block.states = new java.util.ArrayList<>();
		if (block.animations == null) block.animations = new java.util.ArrayList<>();
		if (block.boundingBoxes == null || block.boundingBoxes.isEmpty())
			block.boundingBoxes = new java.util.ArrayList<>(java.util.List.of(
					new net.mcreator.element.types.interfaces.IBlockWithBoundingBox.BoxEntry()));
		if (block.restrictionBiomes == null) block.restrictionBiomes = new java.util.ArrayList<>();
		if (block.blocksToReplace == null) block.blocksToReplace = new java.util.ArrayList<>();
		if (block.fluidRestrictions == null) block.fluidRestrictions = new java.util.ArrayList<>();
		if (block.vibrationalEvents == null) block.vibrationalEvents = new java.util.ArrayList<>();
		if (block.inventoryInSlotIDs == null) block.inventoryInSlotIDs = new java.util.ArrayList<>();
		if (block.inventoryOutSlotIDs == null) block.inventoryOutSlotIDs = new java.util.ArrayList<>();
		if (block.customProperties == null) block.customProperties = new java.util.ArrayList<>();
	}

	private static void applyBlockFields(net.mcreator.element.types.Block block, Workspace workspace, JsonObject fields) {
		// visual section
		if (fields.has("name")) block.name = fields.get("name").getAsString();
		if (fields.has("hardness")) block.hardness = fields.get("hardness").getAsDouble();
		if (fields.has("resistance")) block.resistance = fields.get("resistance").getAsDouble();
		if (fields.has("hasGravity")) block.hasGravity = fields.get("hasGravity").getAsBoolean();
		if (fields.has("isWaterloggable")) block.isWaterloggable = fields.get("isWaterloggable").getAsBoolean();

		if (fields.has("renderType")) {
			int rt = fields.get("renderType").getAsInt();
			block.renderType = (rt == 0) ? 10 : rt;
		} else if (block.renderType == 0) {
			block.renderType = 10;
		}

		if (fields.has("texture") && !fields.get("texture").getAsString().isEmpty()) {
			TextureHolder th = new TextureHolder(workspace, fields.get("texture").getAsString());
			block.texture = th;
			block.textureTop = th;
			block.textureTop = th;
			block.textureLeft = th;
			block.textureFront = th;
			block.textureRight = th;
			block.textureBack = th;
		}
		applyBlockTextureFace(block, workspace, fields, "textureTop", "texture_top");
		applyBlockTextureFace(block, workspace, fields, "textureBottom", "texture_bottom");
		applyBlockTextureFace(block, workspace, fields, "textureLeft", "texture_left");
		applyBlockTextureFace(block, workspace, fields, "textureRight", "texture_right");
		applyBlockTextureFace(block, workspace, fields, "textureFront", "texture_front");
		applyBlockTextureFace(block, workspace, fields, "textureBack", "texture_back");

		if (fields.has("itemTexture") && !fields.get("itemTexture").getAsString().isEmpty()) {
			block.itemTexture = new TextureHolder(workspace, fields.get("itemTexture").getAsString());
		} else if (block.texture != null) {
			block.itemTexture = block.texture;
		}
		if (fields.has("particleTexture") && !fields.get("particleTexture").getAsString().isEmpty()) {
			block.particleTexture = new TextureHolder(workspace, fields.get("particleTexture").getAsString());
		}

		if (fields.has("customModelName") && !fields.get("customModelName").getAsString().isEmpty()) {
			block.customModelName = fields.get("customModelName").getAsString();
		} else if (block.customModelName == null || block.customModelName.isEmpty()) {
			block.customModelName = "Normal";
		}

		if (fields.has("tintType") && !fields.get("tintType").getAsString().isEmpty())
			block.tintType = fields.get("tintType").getAsString();
		if (fields.has("isItemTinted")) block.isItemTinted = fields.get("isItemTinted").getAsBoolean();
		if (fields.has("hasTransparency")) block.hasTransparency = fields.get("hasTransparency").getAsBoolean();
		if (fields.has("transparencyType") && !fields.get("transparencyType").getAsString().isEmpty())
			block.transparencyType = fields.get("transparencyType").getAsString();
		if (fields.has("connectedSides")) block.connectedSides = fields.get("connectedSides").getAsBoolean();
		if (fields.has("emissiveRendering")) block.emissiveRendering = fields.get("emissiveRendering").getAsBoolean();
		if (fields.has("displayFluidOverlay")) block.displayFluidOverlay = fields.get("displayFluidOverlay").getAsBoolean();

		if (fields.has("rotationMode")) {
			String rm = fields.get("rotationMode").getAsString();
			block.rotationMode = switch (rm) {
				case "player_y_axis" -> 1;
				case "player_all_axis" -> 2;
				case "block_y_axis" -> 3;
				case "block_all_axis" -> 4;
				case "log" -> 5;
				default -> 0;
			};
		}
		if (fields.has("enablePitch")) block.enablePitch = fields.get("enablePitch").getAsBoolean();

		// block base section
		if (fields.has("blockBase")) {
			String bb = fields.get("blockBase").getAsString();
			block.blockBase = bb.isEmpty() ? null : bb;
		}
		if (fields.has("blockSetType") && !fields.get("blockSetType").getAsString().isEmpty())
			block.blockSetType = fields.get("blockSetType").getAsString();

		// strippingResult - convert registry name to MCreator blocksitems key
		if (fields.has("strippingResult")) {
			String sr = optStr(fields, "strippingResult", "");
			if (!sr.isEmpty()) {
				String converted = convertItemValue(workspace, sr);
				block.strippingResult = new net.mcreator.element.parts.MItemBlock(workspace, converted);
			}
		}

		// properties section
		if (fields.has("colorOnMap") && !fields.get("colorOnMap").getAsString().isEmpty())
			block.colorOnMap = new net.mcreator.element.parts.MapColor(workspace, fields.get("colorOnMap").getAsString());
		if (fields.has("noteBlockInstrument") && !fields.get("noteBlockInstrument").getAsString().isEmpty())
			block.noteBlockInstrument = new net.mcreator.element.parts.NoteBlockInstrument(workspace, fields.get("noteBlockInstrument").getAsString());
		if (fields.has("offsetType") && !fields.get("offsetType").getAsString().isEmpty())
			block.offsetType = fields.get("offsetType").getAsString();
		if (fields.has("aiPathNodeType") && !fields.get("aiPathNodeType").getAsString().isEmpty())
			block.aiPathNodeType = new net.mcreator.element.parts.AIPathNodeType(workspace, fields.get("aiPathNodeType").getAsString());

		if (fields.has("beaconColorModifier")) {
			String hex = fields.get("beaconColorModifier").getAsString();
			if (!hex.isEmpty()) {
				block.beaconColorModifier = java.awt.Color.decode(hex);
			}
		}

		// block item section
		if (fields.has("hasBlockItem")) block.hasBlockItem = fields.get("hasBlockItem").getAsBoolean();
		if (fields.has("maxStackSize")) block.maxStackSize = fields.get("maxStackSize").getAsInt();
		if (fields.has("rarity") && !fields.get("rarity").getAsString().isEmpty())
			block.rarity = fields.get("rarity").getAsString();
		if (fields.has("immuneToFire")) block.immuneToFire = fields.get("immuneToFire").getAsBoolean();

		// harvesting & drops section
		if (fields.has("destroyTool") && !fields.get("destroyTool").getAsString().isEmpty()) {
			block.destroyTool = fields.get("destroyTool").getAsString();
		} else if (block.destroyTool == null || block.destroyTool.isEmpty()) {
			block.destroyTool = "Not specified";
		}
		if (fields.has("vanillaToolTier") && !fields.get("vanillaToolTier").getAsString().isEmpty())
			block.vanillaToolTier = fields.get("vanillaToolTier").getAsString();
		if (fields.has("requiresCorrectTool")) block.requiresCorrectTool = fields.get("requiresCorrectTool").getAsBoolean();
		if (fields.has("unbreakable")) block.unbreakable = fields.get("unbreakable").getAsBoolean();
		if (fields.has("customDrop")) {
			String cd = optStr(fields, "customDrop", "");
			if (!cd.isEmpty()) {
				String converted = convertItemValue(workspace, cd);
				block.customDrop = new net.mcreator.element.parts.MItemBlock(workspace, converted);
			}
		}
		if (fields.has("dropAmount")) block.dropAmount = fields.get("dropAmount").getAsInt();
		if (fields.has("xpAmountMin")) block.xpAmountMin = fields.get("xpAmountMin").getAsInt();
		if (fields.has("xpAmountMax")) block.xpAmountMax = fields.get("xpAmountMax").getAsInt();
		if (fields.has("useLootTableForDrops")) block.useLootTableForDrops = fields.get("useLootTableForDrops").getAsBoolean();

		// sound section
		if (fields.has("soundOnStep") && !fields.get("soundOnStep").getAsString().isEmpty())
			block.soundOnStep = new net.mcreator.element.parts.StepSound(workspace, fields.get("soundOnStep").getAsString());
		if (fields.has("isCustomSoundType")) block.isCustomSoundType = fields.get("isCustomSoundType").getAsBoolean();
		if (fields.has("breakSound") && !fields.get("breakSound").getAsString().isEmpty())
			block.breakSound = new net.mcreator.element.parts.Sound(workspace, fields.get("breakSound").getAsString());
		if (fields.has("fallSound") && !fields.get("fallSound").getAsString().isEmpty())
			block.fallSound = new net.mcreator.element.parts.Sound(workspace, fields.get("fallSound").getAsString());
		if (fields.has("hitSound") && !fields.get("hitSound").getAsString().isEmpty())
			block.hitSound = new net.mcreator.element.parts.Sound(workspace, fields.get("hitSound").getAsString());
		if (fields.has("placeSound") && !fields.get("placeSound").getAsString().isEmpty())
			block.placeSound = new net.mcreator.element.parts.Sound(workspace, fields.get("placeSound").getAsString());
		if (fields.has("stepSound") && !fields.get("stepSound").getAsString().isEmpty())
			block.stepSound = new net.mcreator.element.parts.Sound(workspace, fields.get("stepSound").getAsString());
		// custom sound type requires all five sounds non-null, otherwise the
		// code templates crash on ${data.breakSound} etc.
		if (block.isCustomSoundType) {
			if (block.breakSound == null) block.breakSound = new net.mcreator.element.parts.Sound(workspace, "block.stone.break");
			if (block.fallSound == null) block.fallSound = new net.mcreator.element.parts.Sound(workspace, "block.stone.fall");
			if (block.hitSound == null) block.hitSound = new net.mcreator.element.parts.Sound(workspace, "block.stone.hit");
			if (block.placeSound == null) block.placeSound = new net.mcreator.element.parts.Sound(workspace, "block.stone.place");
			if (block.stepSound == null) block.stepSound = new net.mcreator.element.parts.Sound(workspace, "block.stone.step");
		}

		// advanced section
		if (fields.has("enchantPowerBonus")) block.enchantPowerBonus = fields.get("enchantPowerBonus").getAsDouble();
		if (fields.has("plantsGrowOn")) block.plantsGrowOn = fields.get("plantsGrowOn").getAsBoolean();
		if (fields.has("canRedstoneConnect")) block.canRedstoneConnect = fields.get("canRedstoneConnect").getAsBoolean();
		if (fields.has("hasCustomOpacity")) block.hasCustomOpacity = fields.get("hasCustomOpacity").getAsBoolean();
		if (fields.has("lightOpacity")) block.lightOpacity = fields.get("lightOpacity").getAsInt();
		if (fields.has("tickRate")) block.tickRate = fields.get("tickRate").getAsInt();
		if (fields.has("tickRandomly")) block.tickRandomly = fields.get("tickRandomly").getAsBoolean();
		if (fields.has("isReplaceable")) block.isReplaceable = fields.get("isReplaceable").getAsBoolean();
		if (fields.has("canProvidePower")) block.canProvidePower = fields.get("canProvidePower").getAsBoolean();
		if (fields.has("luminance")) {
			JsonElement e = fields.get("luminance");
			double v = (e.isJsonObject()) ? (e.getAsJsonObject().has("fixedValue") ? e.getAsJsonObject().get("fixedValue").getAsDouble() : 0) : e.getAsDouble();
			block.luminance = new net.mcreator.element.parts.procedure.NumberProcedure(null, v);
		}
		if (fields.has("emittedRedstonePower")) {
			JsonElement e = fields.get("emittedRedstonePower");
			double v = (e.isJsonObject()) ? (e.getAsJsonObject().has("fixedValue") ? e.getAsJsonObject().get("fixedValue").getAsDouble() : 0) : e.getAsDouble();
			block.emittedRedstonePower = new net.mcreator.element.parts.procedure.NumberProcedure(null, v);
		}

		// flammability & physics section
		if (fields.has("ignitedByLava")) block.ignitedByLava = fields.get("ignitedByLava").getAsBoolean();
		if (fields.has("flammability")) block.flammability = fields.get("flammability").getAsInt();
		if (fields.has("fireSpreadSpeed")) block.fireSpreadSpeed = fields.get("fireSpreadSpeed").getAsInt();
		if (fields.has("isLadder")) block.isLadder = fields.get("isLadder").getAsBoolean();
		if (fields.has("slipperiness")) block.slipperiness = fields.get("slipperiness").getAsDouble();
		if (fields.has("speedFactor")) block.speedFactor = fields.get("speedFactor").getAsDouble();
		if (fields.has("jumpFactor")) block.jumpFactor = fields.get("jumpFactor").getAsDouble();
		if (fields.has("reactionToPushing") && !fields.get("reactionToPushing").getAsString().isEmpty())
			block.reactionToPushing = fields.get("reactionToPushing").getAsString();
		if (fields.has("isNotColidable")) block.isNotColidable = fields.get("isNotColidable").getAsBoolean();

		// bone meal & vibration section
		if (fields.has("isBonemealable")) block.isBonemealable = fields.get("isBonemealable").getAsBoolean();
		if (fields.has("sensitiveToVibration")) block.sensitiveToVibration = fields.get("sensitiveToVibration").getAsBoolean();
		if (fields.has("vibrationSensitivityRadius")) {
			JsonElement vsr = fields.get("vibrationSensitivityRadius");
			double v = (vsr.isJsonObject()) ? (vsr.getAsJsonObject().has("fixedValue") ? vsr.getAsJsonObject().get("fixedValue").getAsDouble() : 8) : vsr.getAsDouble();
			block.vibrationSensitivityRadius = new net.mcreator.element.parts.procedure.NumberProcedure(null, v);
		} else if (block.sensitiveToVibration && block.vibrationSensitivityRadius == null) {
			block.vibrationSensitivityRadius = new net.mcreator.element.parts.procedure.NumberProcedure(null, 11);
		}
		if (fields.has("vibrationalEvents")) {
			JsonElement ve = fields.get("vibrationalEvents");
			if (ve.isJsonArray()) {
				java.util.List<net.mcreator.element.parts.GameEventEntry> veList = new java.util.ArrayList<>();
				for (JsonElement e : ve.getAsJsonArray()) {
					String s = e.isJsonPrimitive() ? e.getAsString() : "";
					if (!s.isEmpty()) veList.add(new net.mcreator.element.parts.GameEventEntry(workspace, s));
				}
				block.vibrationalEvents = veList;
			}
		}

		// inventory & energy & fluid section
		if (fields.has("hasInventory")) block.hasInventory = fields.get("hasInventory").getAsBoolean();
		if (fields.has("inventorySize")) block.inventorySize = fields.get("inventorySize").getAsInt();
		if (fields.has("inventoryStackSize")) block.inventoryStackSize = fields.get("inventoryStackSize").getAsInt();
		if (fields.has("inventoryDropWhenDestroyed")) block.inventoryDropWhenDestroyed = fields.get("inventoryDropWhenDestroyed").getAsBoolean();
		if (fields.has("inventoryComparatorPower")) block.inventoryComparatorPower = fields.get("inventoryComparatorPower").getAsBoolean();
		if (fields.has("hasEnergyStorage")) block.hasEnergyStorage = fields.get("hasEnergyStorage").getAsBoolean();
		if (fields.has("energyInitial")) block.energyInitial = fields.get("energyInitial").getAsInt();
		if (fields.has("energyCapacity")) block.energyCapacity = fields.get("energyCapacity").getAsInt();
		if (fields.has("energyMaxReceive")) block.energyMaxReceive = fields.get("energyMaxReceive").getAsInt();
		if (fields.has("energyMaxExtract")) block.energyMaxExtract = fields.get("energyMaxExtract").getAsInt();
		if (fields.has("isFluidTank")) block.isFluidTank = fields.get("isFluidTank").getAsBoolean();
		if (fields.has("fluidCapacity")) block.fluidCapacity = fields.get("fluidCapacity").getAsInt();

		// generation section
		if (fields.has("generateFeature")) block.generateFeature = fields.get("generateFeature").getAsBoolean();
		if (fields.has("generationShape") && !fields.get("generationShape").getAsString().isEmpty())
			block.generationShape = fields.get("generationShape").getAsString();
		if (fields.has("frequencyPerChunks")) block.frequencyPerChunks = fields.get("frequencyPerChunks").getAsInt();
		if (fields.has("frequencyOnChunk")) block.frequencyOnChunk = fields.get("frequencyOnChunk").getAsInt();
		if (fields.has("minGenerateHeight")) block.minGenerateHeight = fields.get("minGenerateHeight").getAsInt();
		if (fields.has("maxGenerateHeight")) block.maxGenerateHeight = fields.get("maxGenerateHeight").getAsInt();

		// bounding box section
		if (fields.has("disableOffset")) block.disableOffset = fields.get("disableOffset").getAsBoolean();
		if (fields.has("boundingBoxes")) {
			JsonElement bbElem = fields.get("boundingBoxes");
			if (bbElem.isJsonArray()) {
				java.util.List<net.mcreator.element.types.interfaces.IBlockWithBoundingBox.BoxEntry> boxList =
						new java.util.ArrayList<>();
				for (JsonElement be : bbElem.getAsJsonArray()) {
					if (be.isJsonObject()) {
						JsonObject bo = be.getAsJsonObject();
						net.mcreator.element.types.interfaces.IBlockWithBoundingBox.BoxEntry box =
								new net.mcreator.element.types.interfaces.IBlockWithBoundingBox.BoxEntry();
						box.mx = bo.has("mx") ? bo.get("mx").getAsDouble() : 0;
						box.my = bo.has("my") ? bo.get("my").getAsDouble() : 0;
						box.mz = bo.has("mz") ? bo.get("mz").getAsDouble() : 0;
						box.Mx = bo.has("Mx") ? bo.get("Mx").getAsDouble() : 16;
						box.My = bo.has("My") ? bo.get("My").getAsDouble() : 16;
						box.Mz = bo.has("Mz") ? bo.get("Mz").getAsDouble() : 16;
						box.subtract = bo.has("subtract") && bo.get("subtract").getAsBoolean();
						boxList.add(box);
					}
				}
				if (!boxList.isEmpty()) block.boundingBoxes = boxList;
			} else if (bbElem.isJsonPrimitive()) {
				try {
					JsonElement parsed = JsonParser.parseString(bbElem.getAsString());
					if (parsed.isJsonArray()) {
						java.util.List<net.mcreator.element.types.interfaces.IBlockWithBoundingBox.BoxEntry> boxList =
								new java.util.ArrayList<>();
						for (JsonElement be : parsed.getAsJsonArray()) {
							if (be.isJsonObject()) {
								JsonObject bo = be.getAsJsonObject();
								net.mcreator.element.types.interfaces.IBlockWithBoundingBox.BoxEntry box =
										new net.mcreator.element.types.interfaces.IBlockWithBoundingBox.BoxEntry();
								box.mx = bo.has("mx") ? bo.get("mx").getAsDouble() : 0;
								box.my = bo.has("my") ? bo.get("my").getAsDouble() : 0;
								box.mz = bo.has("mz") ? bo.get("mz").getAsDouble() : 0;
								box.Mx = bo.has("Mx") ? bo.get("Mx").getAsDouble() : 16;
								box.My = bo.has("My") ? bo.get("My").getAsDouble() : 16;
								box.Mz = bo.has("Mz") ? bo.get("Mz").getAsDouble() : 16;
								box.subtract = bo.has("subtract") && bo.get("subtract").getAsBoolean();
								boxList.add(box);
							}
						}
						if (!boxList.isEmpty()) block.boundingBoxes = boxList;
					}
				} catch (Exception ex) {
					LOG.error("Failed to parse boundingBoxes string", ex);
				}
			}
		}

		// triggers - map frontend field names to backend field names
		if (fields.has("onBlockAdded")) {
			String v = fields.get("onBlockAdded").getAsString();
			if (!v.isEmpty()) block.onBlockAdded = new net.mcreator.element.parts.procedure.Procedure(v);
		}
		if (fields.has("onTickUpdate")) {
			String v = fields.get("onTickUpdate").getAsString();
			if (!v.isEmpty()) block.onTickUpdate = new net.mcreator.element.parts.procedure.Procedure(v);
		}
		if (fields.has("onRandomUpdate")) {
			String v = fields.get("onRandomUpdate").getAsString();
			if (!v.isEmpty()) block.onRandomUpdateEvent = new net.mcreator.element.parts.procedure.Procedure(v);
		}
		if (fields.has("onDestroyedByPlayer")) {
			String v = fields.get("onDestroyedByPlayer").getAsString();
			if (!v.isEmpty()) block.onDestroyedByPlayer = new net.mcreator.element.parts.procedure.Procedure(v);
		}
		if (fields.has("onDestroyedByExplosion")) {
			String v = fields.get("onDestroyedByExplosion").getAsString();
			if (!v.isEmpty()) block.onDestroyedByExplosion = new net.mcreator.element.parts.procedure.Procedure(v);
		}
		if (fields.has("onEntityCollides")) {
			String v = fields.get("onEntityCollides").getAsString();
			if (!v.isEmpty()) block.onEntityCollides = new net.mcreator.element.parts.procedure.Procedure(v);
		}
		if (fields.has("onEntityWalksOn")) {
			String v = fields.get("onEntityWalksOn").getAsString();
			if (!v.isEmpty()) block.onEntityWalksOn = new net.mcreator.element.parts.procedure.Procedure(v);
		}
		if (fields.has("onBlockRightClicked")) {
			String v = fields.get("onBlockRightClicked").getAsString();
			if (!v.isEmpty()) block.onRightClicked = new net.mcreator.element.parts.procedure.Procedure(v);
		}
		if (fields.has("onNeighborBlockChanges")) {
			String v = fields.get("onNeighborBlockChanges").getAsString();
			if (!v.isEmpty()) block.onNeighbourBlockChanges = new net.mcreator.element.parts.procedure.Procedure(v);
		}
		if (fields.has("onHitByProjectile")) {
			String v = fields.get("onHitByProjectile").getAsString();
			if (!v.isEmpty()) block.onHitByProjectile = new net.mcreator.element.parts.procedure.Procedure(v);
		}
		if (fields.has("onPlacedBy")) {
			String v = fields.get("onPlacedBy").getAsString();
			if (!v.isEmpty()) block.onBlockPlayedBy = new net.mcreator.element.parts.procedure.Procedure(v);
		}
		if (fields.has("onRedstoneOn")) {
			String v = fields.get("onRedstoneOn").getAsString();
			if (!v.isEmpty()) block.onRedstoneOn = new net.mcreator.element.parts.procedure.Procedure(v);
		}
		if (fields.has("onRedstoneOff")) {
			String v = fields.get("onRedstoneOff").getAsString();
			if (!v.isEmpty()) block.onRedstoneOff = new net.mcreator.element.parts.procedure.Procedure(v);
		}
		if (fields.has("onStartToDestroy")) {
			String v = fields.get("onStartToDestroy").getAsString();
			if (!v.isEmpty()) block.onStartToDestroy = new net.mcreator.element.parts.procedure.Procedure(v);
		}
		if (fields.has("onEntityFallsOn")) {
			String v = fields.get("onEntityFallsOn").getAsString();
			if (!v.isEmpty()) block.onEntityFallsOn = new net.mcreator.element.parts.procedure.Procedure(v);
		}
	}

	private static void applyBlockTextureFace(net.mcreator.element.types.Block block, Workspace workspace,
			JsonObject fields, String fieldName, String configName) {
		String value = optStr(fields, configName, optStr(fields, fieldName, ""));
		if (!value.isEmpty()) {
			TextureHolder texture = new TextureHolder(workspace, value);
			switch (fieldName) {
				case "textureTop" -> block.textureTop = texture;
				case "textureBottom" -> block.textureTop = texture;
				case "textureLeft" -> block.textureLeft = texture;
				case "textureRight" -> block.textureRight = texture;
				case "textureFront" -> block.textureFront = texture;
				case "textureBack" -> block.textureBack = texture;
			}
		}
	}

	private static void setBiomeDefaults(net.mcreator.element.types.Biome biome, Workspace workspace) {
		if (biome.name == null) biome.name = "";
		if (biome.vanillaTreeType == null) biome.vanillaTreeType = "Default";
		if (biome.villageType == null) biome.villageType = "none";
		if (biome.defaultFeatures == null) biome.defaultFeatures = new java.util.ArrayList<>();
		if (biome.groundBlock == null) {
			biome.groundBlock = new net.mcreator.element.parts.MItemBlock(workspace, "Blocks.GRASS");
		}
		if (biome.undergroundBlock == null) {
			biome.undergroundBlock = new net.mcreator.element.parts.MItemBlock(workspace, "Blocks.DIRT#0");
		}
	}

	private static void applyBiomeFields(net.mcreator.element.types.Biome biome, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) biome.name = fields.get("name").getAsString();

		// ground / underground / underwater blocks
		if (fields.has("ground_block") && !fields.get("ground_block").getAsString().isEmpty()) {
			String converted = convertItemValue(workspace, fields.get("ground_block").getAsString());
			biome.groundBlock = new net.mcreator.element.parts.MItemBlock(workspace, converted);
		}
		if (fields.has("underground_block") && !fields.get("underground_block").getAsString().isEmpty()) {
			String converted = convertItemValue(workspace, fields.get("underground_block").getAsString());
			biome.undergroundBlock = new net.mcreator.element.parts.MItemBlock(workspace, converted);
		}
		if (fields.has("underwater_block") && !fields.get("underwater_block").getAsString().isEmpty()) {
			String converted = convertItemValue(workspace, fields.get("underwater_block").getAsString());
			biome.underwaterBlock = new net.mcreator.element.parts.MItemBlock(workspace, converted);
		}

		// colors (nullable, empty means vanilla behavior)
		applyColor(fields, "air_color", c -> biome.airColor = c);
		applyColor(fields, "fog_color", c -> biome.fogColor = c);
		applyColor(fields, "grass_color", c -> biome.grassColor = c);
		applyColor(fields, "foliage_color", c -> biome.foliageColor = c);
		applyColor(fields, "water_color", c -> biome.waterColor = c);
		applyColor(fields, "water_fog_color", c -> biome.waterFogColor = c);

		// climate parameters
		applyClimatePoint(fields, "gen_temperature", "gen_temperature_min", "gen_temperature_max", biome.genTemperature, null);
		applyClimatePoint(fields, "gen_humidity", "gen_humidity_min", "gen_humidity_max", biome.genHumidity, null);
		applyClimatePoint(fields, "gen_continentalness", "gen_continentalness_min", "gen_continentalness_max", biome.genContinentalness, null);
		applyClimatePoint(fields, "gen_erosion", "gen_erosion_min", "gen_erosion_max", biome.genErosion, null);
		applyClimatePoint(fields, "gen_weirdness", "gen_weirdness_min", "gen_weirdness_max", biome.genWeirdness, null);
		applyClimatePoint(fields, "gen_depth", "gen_depth_min", "gen_depth_max", biome.genDepth, biome);

		// raining / temperature
		if (fields.has("raining_possibility")) biome.rainingPossibility = fields.get("raining_possibility").getAsDouble();
		if (fields.has("temperature")) biome.temperature = fields.get("temperature").getAsDouble();

		// generation flags
		if (fields.has("spawn_biome")) biome.spawnBiome = fields.get("spawn_biome").getAsBoolean();
		if (fields.has("spawn_in_caves")) biome.spawnInCaves = fields.get("spawn_in_caves").getAsBoolean();
		if (fields.has("spawn_biome_nether")) biome.spawnBiomeNether = fields.get("spawn_biome_nether").getAsBoolean();

		// trees
		if (fields.has("trees_per_chunk")) biome.treesPerChunk = fields.get("trees_per_chunk").getAsInt();
		if (fields.has("vanilla_tree_type") && !fields.get("vanilla_tree_type").getAsString().isEmpty())
			biome.vanillaTreeType = fields.get("vanilla_tree_type").getAsString();
		if (fields.has("tree_type")) {
			String tt = fields.get("tree_type").getAsString();
			biome.treeType = tt.equals("1") || tt.equals("true") ? 1 : 0;
		}
		if (fields.has("min_height")) biome.minHeight = fields.get("min_height").getAsInt();
		applyMItemBlock(fields, "tree_stem", workspace, b -> biome.treeStem = b);
		applyMItemBlock(fields, "tree_branch", workspace, b -> biome.treeBranch = b);
		applyMItemBlock(fields, "tree_vines", workspace, b -> biome.treeVines = b);
		applyMItemBlock(fields, "tree_fruits", workspace, b -> biome.treeFruits = b);

		// particles
		if (fields.has("spawn_particles")) {
			String sp = fields.get("spawn_particles").getAsString();
			biome.spawnParticles = sp.equals("1") || sp.equals("true");
		}
		if (fields.has("particle_to_spawn") && !fields.get("particle_to_spawn").getAsString().isEmpty()) {
			biome.particleToSpawn = new net.mcreator.element.parts.ParticleEntry(workspace, fields.get("particle_to_spawn").getAsString());
		}
		if (fields.has("particles_probability")) biome.particlesProbability = fields.get("particles_probability").getAsDouble();

		// structures
		applyBool(fields, "spawn_stronghold", v -> biome.spawnStronghold = v);
		applyBool(fields, "spawn_mineshaft", v -> biome.spawnMineshaft = v);
		applyBool(fields, "spawn_mineshaft_mesa", v -> biome.spawnMineshaftMesa = v);
		applyBool(fields, "spawn_pillager_outpost", v -> biome.spawnPillagerOutpost = v);
		applyBool(fields, "spawn_woodland_mansion", v -> biome.spawnWoodlandMansion = v);
		applyBool(fields, "spawn_jungle_temple", v -> biome.spawnJungleTemple = v);
		applyBool(fields, "spawn_desert_pyramid", v -> biome.spawnDesertPyramid = v);
		applyBool(fields, "spawn_swamp_hut", v -> biome.spawnSwampHut = v);
		applyBool(fields, "spawn_igloo", v -> biome.spawnIgloo = v);
		applyBool(fields, "spawn_ocean_monument", v -> biome.spawnOceanMonument = v);
		applyBool(fields, "spawn_shipwreck", v -> biome.spawnShipwreck = v);
		applyBool(fields, "spawn_shipwreck_beached", v -> biome.spawnShipwreckBeached = v);
		applyBool(fields, "spawn_buried_treasure", v -> biome.spawnBuriedTreasure = v);
		applyBool(fields, "spawn_nether_bridge", v -> biome.spawnNetherBridge = v);
		applyBool(fields, "spawn_nether_fossil", v -> biome.spawnNetherFossil = v);
		applyBool(fields, "spawn_bastion_remnant", v -> biome.spawnBastionRemnant = v);
		applyBool(fields, "spawn_end_city", v -> biome.spawnEndCity = v);
		if (fields.has("village_type") && !fields.get("village_type").getAsString().isEmpty())
			biome.villageType = fields.get("village_type").getAsString();
		if (fields.has("ocean_ruin_type") && !fields.get("ocean_ruin_type").getAsString().isEmpty())
			biome.oceanRuinType = fields.get("ocean_ruin_type").getAsString();
		if (fields.has("spawn_ruined_portal") && !fields.get("spawn_ruined_portal").getAsString().isEmpty())
			biome.spawnRuinedPortal = fields.get("spawn_ruined_portal").getAsString();

		// sounds (only set when provided)
		applySound(fields, "ambient_sound", workspace, s -> biome.ambientSound = s);
		applySound(fields, "mood_sound", workspace, s -> biome.moodSound = s);
		applySound(fields, "additions_sound", workspace, s -> biome.additionsSound = s);
		applySound(fields, "music", workspace, s -> biome.music = s);
		if (fields.has("mood_sound_delay")) biome.moodSoundDelay = fields.get("mood_sound_delay").getAsInt();

		// features
		if (fields.has("default_features") && fields.get("default_features").isJsonArray()) {
			java.util.List<String> df = new java.util.ArrayList<>();
			for (JsonElement e : fields.getAsJsonArray("default_features"))
				df.add(e.getAsString());
			biome.defaultFeatures = df;
		}
	}

	private static void applyColor(JsonObject fields, String key, java.util.function.Consumer<java.awt.Color> setter) {
		if (fields.has(key)) {
			String hex = fields.get(key).getAsString();
			if (hex != null && !hex.isEmpty()) {
				try { setter.accept(java.awt.Color.decode(hex)); }
				catch (NumberFormatException e) { LOG.warn("applyBiomeFields: bad color for " + key + ": " + hex); }
			}
		}
	}

	private static void applyClimatePoint(JsonObject fields, String pointKey, String minKey, String maxKey,
			net.mcreator.element.types.Biome.ClimatePoint point,
			net.mcreator.element.types.Biome biome) {
		if (fields.has(minKey)) {
			point.min = fields.get(minKey).getAsDouble();
			if (biome != null && pointKey.equals("gen_depth")) biome.genDepthMin = point.min;
		}
		if (fields.has(maxKey)) {
			point.max = fields.get(maxKey).getAsDouble();
			if (biome != null && pointKey.equals("gen_depth")) biome.genDepthMax = point.max;
		}
	}

	private static void applyMItemBlock(JsonObject fields, String key, Workspace workspace,
			java.util.function.Consumer<net.mcreator.element.parts.MItemBlock> setter) {
		if (fields.has(key) && !fields.get(key).getAsString().isEmpty()) {
			String converted = convertItemValue(workspace, fields.get(key).getAsString());
			setter.accept(new net.mcreator.element.parts.MItemBlock(workspace, converted));
		}
	}

	private static void applyBool(JsonObject fields, String key, java.util.function.Consumer<Boolean> setter) {
		if (fields.has(key)) setter.accept(fields.get(key).getAsBoolean());
	}

	private static void applySound(JsonObject fields, String key, Workspace workspace,
			java.util.function.Consumer<net.mcreator.element.parts.Sound> setter) {
		if (fields.has(key) && !fields.get(key).getAsString().isEmpty()) {
			try { setter.accept(new net.mcreator.element.parts.Sound(workspace, fields.get(key).getAsString())); }
			catch (Exception e) { LOG.warn("applyBiomeFields: could not set sound " + key, e); }
		}
	}

	// ============= TOOL =============
	private static void setToolDefaults(net.mcreator.element.types.Tool tool, Workspace workspace) {
		if (tool.name == null) tool.name = "";
		if (tool.customModelName == null) tool.customModelName = "Normal";
		if (tool.blockingModelName == null) tool.blockingModelName = "Normal blocking";
		if (tool.rarity == null) tool.rarity = "COMMON";
		if (tool.toolType == null) tool.toolType = "Pickaxe";
		if (tool.blockDropsTier == null) tool.blockDropsTier = "WOOD";
		if (tool.creativeTabs == null || tool.creativeTabs.isEmpty())
			tool.creativeTabs = new java.util.ArrayList<>(java.util.List.of(
					new net.mcreator.element.parts.TabEntry(workspace, "TOOLS")));
		if (tool.repairItems == null) tool.repairItems = new java.util.ArrayList<>();
		if (tool.blocksAffected == null) tool.blocksAffected = new java.util.ArrayList<>();
		if (tool.attributeModifiers == null) tool.attributeModifiers = new java.util.ArrayList<>();
		if (tool.specialInformation == null)
			tool.specialInformation = new net.mcreator.element.parts.procedure.StringListProcedure(null, java.util.List.of());
		if (tool.glowCondition == null)
			tool.glowCondition = new net.mcreator.element.parts.procedure.LogicProcedure(null, false);
		tool.renderType = 0;
	}

	private static void applyToolFields(net.mcreator.element.types.Tool tool, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) tool.name = fields.get("name").getAsString();
		if (fields.has("texture") && !fields.get("texture").getAsString().isEmpty())
			tool.texture = new TextureHolder(workspace, fields.get("texture").getAsString());
		if (fields.has("toolType") && !fields.get("toolType").getAsString().isEmpty())
			tool.toolType = fields.get("toolType").getAsString();
		if (fields.has("creativeTab") && !fields.get("creativeTab").getAsString().isEmpty()) {
			tool.creativeTabs.clear();
			tool.creativeTabs.add(new net.mcreator.element.parts.TabEntry(workspace, fields.get("creativeTab").getAsString()));
		}
		if (fields.has("harvestLevel")) {
			int hl = fields.get("harvestLevel").getAsInt();
			tool.blockDropsTier = switch (hl) {
				case 1 -> "STONE";
				case 2 -> "IRON";
				case 3 -> "DIAMOND";
				case 4 -> "NETHERITE";
				default -> "WOOD";
			};
		}
		if (fields.has("efficiency")) tool.efficiency = fields.get("efficiency").getAsDouble();
		if (fields.has("enchantability")) tool.enchantability = fields.get("enchantability").getAsInt();
		if (fields.has("attackSpeed")) tool.attackSpeed = fields.get("attackSpeed").getAsDouble();
		if (fields.has("damage")) tool.damageVsEntity = fields.get("damage").getAsDouble();
		if (fields.has("durability")) tool.usageCount = fields.get("durability").getAsInt();
		if (fields.has("repairItems")) {
			String ri = optStr(fields, "repairItems", "");
			if (!ri.isEmpty()) {
				String converted = convertItemValue(workspace, ri);
				tool.repairItems = new java.util.ArrayList<>(java.util.List.of(
						new net.mcreator.element.parts.MItemBlock(workspace, converted)));
			}
		}
		// triggers
		if (fields.has("onRightClickedInAir") && !fields.get("onRightClickedInAir").getAsString().isEmpty())
			tool.onRightClickedInAir = new net.mcreator.element.parts.procedure.Procedure(fields.get("onRightClickedInAir").getAsString());
		if (fields.has("onRightClickedOnBlock") && !fields.get("onRightClickedOnBlock").getAsString().isEmpty())
			tool.onRightClickedOnBlock = new net.mcreator.element.parts.procedure.Procedure(fields.get("onRightClickedOnBlock").getAsString());
		if (fields.has("onEntityHitWith") && !fields.get("onEntityHitWith").getAsString().isEmpty())
			tool.onEntityHitWith = new net.mcreator.element.parts.procedure.Procedure(fields.get("onEntityHitWith").getAsString());
		if (fields.has("onBlockDestroyedWith") && !fields.get("onBlockDestroyedWith").getAsString().isEmpty())
			tool.onBlockDestroyedWithTool = new net.mcreator.element.parts.procedure.Procedure(fields.get("onBlockDestroyedWith").getAsString());
		if (fields.has("onToolInInventoryTick") && !fields.get("onToolInInventoryTick").getAsString().isEmpty())
			tool.onItemInInventoryTick = new net.mcreator.element.parts.procedure.Procedure(fields.get("onToolInInventoryTick").getAsString());
	}

	private static JsonObject toolToFieldsJSON(net.mcreator.element.types.Tool tool) {
		JsonObject obj = generatableElementToFieldsJSON(tool);
		// frontend config ids for backend fields
		if (obj.has("creativeTabs") && obj.get("creativeTabs").isJsonArray()) {
			JsonArray tabs = obj.getAsJsonArray("creativeTabs");
			if (!tabs.isEmpty() && tabs.get(0).isJsonObject()) {
				JsonObject first = tabs.get(0).getAsJsonObject();
				if (first.has("value")) obj.addProperty("creativeTab", first.get("value").getAsString());
			}
		}
		if (obj.has("damageVsEntity")) obj.add("damage", obj.get("damageVsEntity").deepCopy());
		if (obj.has("usageCount")) obj.add("durability", obj.get("usageCount").deepCopy());
		if (tool.blockDropsTier != null) {
			int hl = switch (tool.blockDropsTier) {
				case "STONE" -> 1;
				case "IRON" -> 2;
				case "DIAMOND" -> 3;
				case "NETHERITE" -> 4;
				case "GOLD" -> 2;
				default -> 0;
			};
			obj.addProperty("harvestLevel", hl);
		}
		return obj;
	}

	// ============= FLUID =============
	private static void setFluidDefaults(net.mcreator.element.types.Fluid fluid, Workspace workspace) {
		if (fluid.name == null) fluid.name = "";
		if (fluid.bucketName == null) fluid.bucketName = "";
		if (fluid.tintType == null) fluid.tintType = "No tint";
		if (fluid.type == null) fluid.type = "WATER";
		if (fluid.rarity == null) fluid.rarity = "COMMON";
		if (fluid.creativeTabs == null) fluid.creativeTabs = new java.util.ArrayList<>();
		if (fluid.colorOnMap == null) fluid.colorOnMap = new net.mcreator.element.parts.MapColor(workspace, "DEFAULT");
		if (fluid.specialInformation == null)
			fluid.specialInformation = new net.mcreator.element.parts.procedure.StringListProcedure(null, java.util.List.of());
		if (fluid.fogStartDistance == null) fluid.fogStartDistance = new net.mcreator.element.parts.procedure.NumberProcedure(null, 0);
		if (fluid.fogEndDistance == null) fluid.fogEndDistance = new net.mcreator.element.parts.procedure.NumberProcedure(null, 1);
		if (fluid.spawnParticles && fluid.dripParticle == null)
			fluid.dripParticle = new net.mcreator.element.parts.ParticleEntry(workspace, "DRIP_WATER");
	}

	private static void applyFluidFields(net.mcreator.element.types.Fluid fluid, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) fluid.name = fields.get("name").getAsString();
		if (fields.has("textureStill") && !fields.get("textureStill").getAsString().isEmpty())
			fluid.textureStill = new TextureHolder(workspace, fields.get("textureStill").getAsString());
		if (fields.has("textureFlowing") && !fields.get("textureFlowing").getAsString().isEmpty())
			fluid.textureFlowing = new TextureHolder(workspace, fields.get("textureFlowing").getAsString());
		if (fields.has("textureRenderOverlay") && !fields.get("textureRenderOverlay").getAsString().isEmpty())
			fluid.textureRenderOverlay = new TextureHolder(workspace, fields.get("textureRenderOverlay").getAsString());
		if (fields.has("hasFog")) fluid.hasFog = fields.get("hasFog").getAsBoolean();
		if (fields.has("fogColor")) {
			String hex = fields.get("fogColor").getAsString();
			if (!hex.isEmpty()) fluid.fogColor = java.awt.Color.decode(hex);
		}
		if (fields.has("tintType") && !fields.get("tintType").getAsString().isEmpty())
			fluid.tintType = fields.get("tintType").getAsString();
		if (fields.has("luminosity")) fluid.luminosity = fields.get("luminosity").getAsInt();
		if (fields.has("emissiveRendering")) fluid.emissiveRendering = fields.get("emissiveRendering").getAsBoolean();
		if (fields.has("density")) fluid.density = fields.get("density").getAsInt();
		if (fields.has("viscosity")) fluid.viscosity = fields.get("viscosity").getAsInt();
		if (fields.has("temperature")) fluid.temperature = fields.get("temperature").getAsInt();
		if (fields.has("type") && !fields.get("type").getAsString().isEmpty())
			fluid.type = fields.get("type").getAsString();
		if (fields.has("canMultiply")) fluid.canMultiply = fields.get("canMultiply").getAsBoolean();
		if (fields.has("flowRate")) fluid.flowRate = fields.get("flowRate").getAsInt();
		if (fields.has("levelDecrease")) fluid.levelDecrease = fields.get("levelDecrease").getAsInt();
		if (fields.has("slopeFindDistance")) fluid.slopeFindDistance = fields.get("slopeFindDistance").getAsInt();
		if (fields.has("spawnParticles")) fluid.spawnParticles = fields.get("spawnParticles").getAsBoolean();
		if (fluid.spawnParticles && fluid.dripParticle == null)
			fluid.dripParticle = new net.mcreator.element.parts.ParticleEntry(workspace, "DRIP_WATER");
		if (fields.has("flowStrength")) fluid.flowStrength = fields.get("flowStrength").getAsDouble();
		if (fields.has("bucketName")) fluid.bucketName = fields.get("bucketName").getAsString();
		if (fields.has("generateBucket")) fluid.generateBucket = fields.get("generateBucket").getAsBoolean();
		if (fields.has("textureBucket") && !fields.get("textureBucket").getAsString().isEmpty())
			fluid.textureBucket = new TextureHolder(workspace, fields.get("textureBucket").getAsString());
		if (fields.has("emptySound") && !fields.get("emptySound").getAsString().isEmpty()) {
			try { fluid.emptySound = new net.mcreator.element.parts.Sound(workspace, fields.get("emptySound").getAsString()); }
			catch (Exception e) { LOG.warn("applyFluidFields: could not set empty sound", e); }
		}
		if (fields.has("rarity") && !fields.get("rarity").getAsString().isEmpty())
			fluid.rarity = fields.get("rarity").getAsString();
		if (fields.has("resistance")) fluid.resistance = fields.get("resistance").getAsDouble();
		if (fields.has("luminance")) fluid.luminance = fields.get("luminance").getAsInt();
		if (fields.has("lightOpacity")) fluid.lightOpacity = fields.get("lightOpacity").getAsInt();
		if (fields.has("tickRate")) fluid.tickRate = fields.get("tickRate").getAsInt();
		if (fields.has("ignitedByLava")) fluid.ignitedByLava = fields.get("ignitedByLava").getAsBoolean();
		if (fields.has("flammability")) fluid.flammability = fields.get("flammability").getAsInt();
		if (fields.has("fireSpreadSpeed")) fluid.fireSpreadSpeed = fields.get("fireSpreadSpeed").getAsInt();
		if (fields.has("colorOnMap") && !fields.get("colorOnMap").getAsString().isEmpty())
			fluid.colorOnMap = new net.mcreator.element.parts.MapColor(workspace, fields.get("colorOnMap").getAsString());
		if (fields.has("onEntityCollides") && !fields.get("onEntityCollides").getAsString().isEmpty())
			fluid.onEntityCollides = new net.mcreator.element.parts.procedure.Procedure(fields.get("onEntityCollides").getAsString());
	}

	// ============= COMMAND =============
	private static void setCommandDefaults(net.mcreator.element.types.Command command, Workspace workspace) {
		if (command.commandName == null) command.commandName = "";
		if (command.type == null) command.type = "STANDARD";
		if (command.permissionLevel == null) command.permissionLevel = "0";
		if (command.argsxml == null) command.argsxml = "<xml xmlns=\"https://developers.google.com/blockly/xml\"><block type=\"args_start\" deletable=\"false\" x=\"40\" y=\"40\"><next><block type=\"call_procedure\"><field name=\"procedure\"></field></block></next></block></xml>";
	}

	private static void applyCommandFields(net.mcreator.element.types.Command command, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) command.commandName = fields.get("name").getAsString();
		if (fields.has("permissionLevel") && !fields.get("permissionLevel").getAsString().isEmpty())
			command.permissionLevel = fields.get("permissionLevel").getAsString();
		if (fields.has("onCommandExecuted")) {
			String procedure = fields.get("onCommandExecuted").getAsString();
			command.argsxml = "<xml xmlns=\"https://developers.google.com/blockly/xml\"><block type=\"args_start\" deletable=\"false\" x=\"40\" y=\"40\"><next><block type=\"call_procedure\"><field name=\"procedure\">"
					+ escapeXml(procedure) + "</field></block></next></block></xml>";
		}
	}

	private static String escapeXml(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("\"", "&quot;").replace("'", "&apos;");
	}

	// ============= FUNCTION =============
	private static void setFunctionDefaults(net.mcreator.element.types.Function function, Workspace workspace) {
		if (function.name == null) function.name = "";
		if (function.namespace == null) function.namespace = "mod";
		if (function.code == null) function.code = "";
	}

	private static void applyFunctionFields(net.mcreator.element.types.Function function, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) function.name = fields.get("name").getAsString();
		if (fields.has("namespace") && !fields.get("namespace").getAsString().isEmpty())
			function.namespace = fields.get("namespace").getAsString();
		if (fields.has("code")) function.code = fields.get("code").getAsString();
	}

	private static void setCustomElementDefaults(net.mcreator.element.types.CustomElement element) {
		if (element.code == null) element.code = "";
		if (element.returnType == null) element.returnType = inferCustomElementReturnType(element.code);
	}

	private static void applyCustomElementFields(net.mcreator.element.types.CustomElement element, JsonObject fields) {
		if (fields.has("code")) {
			element.code = fields.get("code").getAsString();
			element.returnType = inferCustomElementReturnType(element.code);
		}
	}

	private static String inferCustomElementReturnType(String code) {
		Matcher matcher = Pattern.compile("\\bpublic\\s+static\\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\\s*<[^>{}]+>)?(?:\\[\\])?)\\s+execute\\s*\\(").matcher(code);
		return matcher.find() ? matcher.group(1).trim() : "void";
	}

	// ============= KEYBINDING =============
	private static void setKeyBindingDefaults(net.mcreator.element.types.KeyBinding keyBinding, Workspace workspace) {
		if (keyBinding.keyBindingName == null) keyBinding.keyBindingName = "";
		if (keyBinding.keyBindingCategoryKey == null) keyBinding.keyBindingCategoryKey = "misc";
		if (keyBinding.triggerKey == null)
			keyBinding.triggerKey = new net.mcreator.element.parts.KeyButton(workspace, "NONE");
	}

	private static void applyKeyBindingFields(net.mcreator.element.types.KeyBinding keyBinding, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) keyBinding.keyBindingName = fields.get("name").getAsString();
		if (fields.has("category") && !fields.get("category").getAsString().isEmpty())
			keyBinding.keyBindingCategoryKey = fields.get("category").getAsString();
		if (fields.has("defaultKey") && !fields.get("defaultKey").getAsString().isEmpty()) {
			try { keyBinding.triggerKey = new net.mcreator.element.parts.KeyButton(workspace, fields.get("defaultKey").getAsString()); }
			catch (Exception e) { LOG.warn("applyKeyBindingFields: could not set key", e); }
		}
		if (fields.has("onKeyPressed") && !fields.get("onKeyPressed").getAsString().isEmpty())
			keyBinding.onKeyPressed = new net.mcreator.element.parts.procedure.Procedure(fields.get("onKeyPressed").getAsString());
		if (fields.has("onKeyReleased") && !fields.get("onKeyReleased").getAsString().isEmpty())
			keyBinding.onKeyReleased = new net.mcreator.element.parts.procedure.Procedure(fields.get("onKeyReleased").getAsString());
	}

	private static JsonObject keyBindingToFieldsJSON(net.mcreator.element.types.KeyBinding keyBinding) {
		JsonObject obj = new JsonObject();
		obj.addProperty("name", keyBinding.keyBindingName != null ? keyBinding.keyBindingName : "");
		obj.addProperty("category",
				keyBinding.keyBindingCategoryKey != null ? keyBinding.keyBindingCategoryKey : "misc");
		if (keyBinding.triggerKey != null)
			obj.addProperty("defaultKey", keyBinding.triggerKey.getUnmappedValue());
		if (keyBinding.onKeyPressed != null && keyBinding.onKeyPressed.getName() != null)
			obj.addProperty("onKeyPressed", keyBinding.onKeyPressed.getName());
		if (keyBinding.onKeyReleased != null && keyBinding.onKeyReleased.getName() != null)
			obj.addProperty("onKeyReleased", keyBinding.onKeyReleased.getName());
		return obj;
	}

	// ============= TAB (Creative Tab) =============
	private static void setTabDefaults(net.mcreator.element.types.Tab tab, Workspace workspace) {		if (tab.name == null) tab.name = "";
		if (tab.icon == null)
			tab.icon = new net.mcreator.element.parts.MItemBlock(workspace, "Items.STICK");
	}

	private static void applyTabFields(net.mcreator.element.types.Tab tab, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) tab.name = fields.get("name").getAsString();
		if (fields.has("icon")) {
			String icon = optStr(fields, "icon", "");
			if (!icon.isEmpty()) {
				String converted = convertItemValue(workspace, icon);
				tab.icon = new net.mcreator.element.parts.MItemBlock(workspace, converted);
			}
		}
		if (fields.has("showSearch")) tab.showSearch = fields.get("showSearch").getAsBoolean();
	}

	// ============= PAINTING =============
	private static void setPaintingDefaults(net.mcreator.element.types.Painting painting, Workspace workspace) {
		if (painting.name == null) painting.name = "";
		if (painting.title == null) painting.title = "";
		if (painting.author == null) painting.author = "";
	}

	private static void applyPaintingFields(net.mcreator.element.types.Painting painting, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) painting.name = fields.get("name").getAsString();
		if (fields.has("title")) painting.title = fields.get("title").getAsString();
		if (fields.has("author")) painting.author = fields.get("author").getAsString();
		if (fields.has("texture") && !fields.get("texture").getAsString().isEmpty())
			painting.texture = new TextureHolder(workspace, fields.get("texture").getAsString());
		// frontend config uses block units (1-4), backend stores pixels (16-64)
		if (fields.has("width")) painting.width = fields.get("width").getAsInt() * 16;
		if (fields.has("height")) painting.height = fields.get("height").getAsInt() * 16;
	}

	private static JsonObject paintingToFieldsJSON(net.mcreator.element.types.Painting painting) {
		JsonObject obj = generatableElementToFieldsJSON(painting);
		// convert back from pixels (16-64) to block units (1-4)
		if (obj.has("width")) obj.addProperty("width", obj.get("width").getAsInt() / 16);
		if (obj.has("height")) obj.addProperty("height", obj.get("height").getAsInt() / 16);
		return obj;
	}

	// ============= PLANT =============
	private static void setPlantDefaults(net.mcreator.element.types.Plant plant, Workspace workspace) {
		if (plant.name == null) plant.name = "";
		if (plant.customModelName == null) plant.customModelName = "Cross model";
		if (plant.plantType == null) plant.plantType = "normal";
		if (plant.tintType == null) plant.tintType = "No tint";
		if (plant.rarity == null) plant.rarity = "COMMON";
		if (plant.offsetType == null) plant.offsetType = "XZ";
		if (plant.creativeTabs == null || plant.creativeTabs.isEmpty())
			plant.creativeTabs = new java.util.ArrayList<>(java.util.List.of(
					new net.mcreator.element.parts.TabEntry(workspace, "NATURAL_BLOCKS")));
		if (plant.specialInformation == null)
			plant.specialInformation = new net.mcreator.element.parts.procedure.StringListProcedure(null, java.util.List.of());
		if (plant.soundOnStep == null)
			plant.soundOnStep = new net.mcreator.element.parts.StepSound(workspace, "PLANT");
		if (plant.colorOnMap == null)
			plant.colorOnMap = new net.mcreator.element.parts.MapColor(workspace, "FOLIAGE");
		if (plant.aiPathNodeType == null)
			plant.aiPathNodeType = new net.mcreator.element.parts.AIPathNodeType(workspace, "DEFAULT");
		if (plant.growapableSpawnType == null)
			plant.growapableSpawnType = new net.mcreator.element.parts.GrowapableSpawnType(workspace, "Plains");
		if (plant.boundingBoxes == null || plant.boundingBoxes.isEmpty())
			plant.boundingBoxes = new java.util.ArrayList<>(java.util.List.of(
					new net.mcreator.element.types.interfaces.IBlockWithBoundingBox.BoxEntry()));
		if (plant.canBePlacedOn == null) plant.canBePlacedOn = new java.util.ArrayList<>();
		if (plant.restrictionBiomes == null) plant.restrictionBiomes = new java.util.ArrayList<>();
		if (plant.generationType == null) plant.generationType = "Flower";
	}

	private static void applyPlantFields(net.mcreator.element.types.Plant plant, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) plant.name = fields.get("name").getAsString();
		if (fields.has("texture") && !fields.get("texture").getAsString().isEmpty()) {
			TextureHolder th = new TextureHolder(workspace, fields.get("texture").getAsString());
			plant.texture = th;
			plant.textureBottom = th;
		}
		if (fields.has("plantType") && !fields.get("plantType").getAsString().isEmpty())
			plant.plantType = fields.get("plantType").getAsString();
		if (fields.has("hasCollision")) plant.isSolid = fields.get("hasCollision").getAsBoolean();
		if (fields.has("flammability")) plant.flammability = fields.get("flammability").getAsInt();
		if (fields.has("generateInWorld")) plant.generateFeature = fields.get("generateInWorld").getAsBoolean();
		if (fields.has("spawnFrequency")) plant.frequencyOnChunks = fields.get("spawnFrequency").getAsInt();
		if (fields.has("onPlantRightClicked") && !fields.get("onPlantRightClicked").getAsString().isEmpty())
			plant.onRightClicked = new net.mcreator.element.parts.procedure.Procedure(fields.get("onPlantRightClicked").getAsString());
		if (fields.has("onPlantDestroyed") && !fields.get("onPlantDestroyed").getAsString().isEmpty())
			plant.onDestroyedByPlayer = new net.mcreator.element.parts.procedure.Procedure(fields.get("onPlantDestroyed").getAsString());
	}

	// ============= DIMENSION =============
	private static void setDimensionDefaults(net.mcreator.element.types.Dimension dimension, Workspace workspace) {
		if (dimension.worldGenType == null) dimension.worldGenType = "Normal world gen";
		if (dimension.defaultEffects == null) dimension.defaultEffects = "overworld";
		if (dimension.skyType == null) dimension.skyType = "NORMAL";
		if (dimension.infiniburnTag == null) dimension.infiniburnTag = "minecraft:infiniburn_overworld";
		if (dimension.igniterName == null) dimension.igniterName = "";
		if (dimension.igniterRarity == null) dimension.igniterRarity = "COMMON";
		if (dimension.specialInformation == null)
			dimension.specialInformation = new net.mcreator.element.parts.procedure.StringListProcedure(null, java.util.List.of());
		if (dimension.creativeTabs == null) dimension.creativeTabs = new java.util.ArrayList<>();
		if (dimension.mainFillerBlock == null)
			dimension.mainFillerBlock = new net.mcreator.element.parts.MItemBlock(workspace, "Blocks.STONE");
		if (dimension.fluidBlock == null)
			dimension.fluidBlock = new net.mcreator.element.parts.MItemBlock(workspace, "Blocks.WATER");
		if (dimension.portalFrame == null)
			dimension.portalFrame = new net.mcreator.element.parts.MItemBlock(workspace, "Blocks.OBSIDIAN");
		if (dimension.portalParticles == null)
			dimension.portalParticles = new net.mcreator.element.parts.ParticleEntry(workspace, "PORTAL");
		if (dimension.portalSound == null)
			dimension.portalSound = new net.mcreator.element.parts.Sound(workspace, "block.portal.ambient");
		if (dimension.biomesInDimension == null) dimension.biomesInDimension = new java.util.ArrayList<>();
	}

	private static void applyDimensionFields(net.mcreator.element.types.Dimension dimension, Workspace workspace, JsonObject fields) {
		if (fields.has("worldGenType") && !fields.get("worldGenType").getAsString().isEmpty())
			dimension.worldGenType = fields.get("worldGenType").getAsString();
		if (fields.has("mainFillerBlock")) {
			String v = optStr(fields, "mainFillerBlock", "");
			if (!v.isEmpty()) {
				String converted = convertItemValue(workspace, v);
				dimension.mainFillerBlock = new net.mcreator.element.parts.MItemBlock(workspace, converted);
			}
		}
		if (fields.has("fluidBlock")) {
			String v = optStr(fields, "fluidBlock", "");
			if (!v.isEmpty()) {
				String converted = convertItemValue(workspace, v);
				dimension.fluidBlock = new net.mcreator.element.parts.MItemBlock(workspace, converted);
			}
		}
		if (fields.has("seaLevel")) dimension.seaLevel = fields.get("seaLevel").getAsInt();
		if (fields.has("generateOreVeins")) dimension.generateOreVeins = fields.get("generateOreVeins").getAsBoolean();
		if (fields.has("generateAquifers")) dimension.generateAquifers = fields.get("generateAquifers").getAsBoolean();
		if (fields.has("horizontalNoiseSize")) dimension.horizontalNoiseSize = fields.get("horizontalNoiseSize").getAsInt();
		if (fields.has("verticalNoiseSize")) dimension.verticalNoiseSize = fields.get("verticalNoiseSize").getAsInt();
		if (fields.has("enablePortal")) dimension.enablePortal = fields.get("enablePortal").getAsBoolean();
		if (fields.has("portalFrame")) {
			String v = optStr(fields, "portalFrame", "");
			if (!v.isEmpty()) {
				String converted = convertItemValue(workspace, v);
				dimension.portalFrame = new net.mcreator.element.parts.MItemBlock(workspace, converted);
			}
		}
		if (fields.has("portalLuminance")) dimension.portalLuminance = fields.get("portalLuminance").getAsInt();
		if (fields.has("portalSound") && !fields.get("portalSound").getAsString().isEmpty()) {
			try {
				String sound = fields.get("portalSound").getAsString();
				if (sound.startsWith("minecraft:")) sound = sound.substring("minecraft:".length());
				dimension.portalSound = new net.mcreator.element.parts.Sound(workspace, sound);
			} catch (Exception e) { LOG.warn("applyDimensionFields: could not set portal sound", e); }
		}
		if (fields.has("portalTexture") && !fields.get("portalTexture").getAsString().isEmpty())
			dimension.portalTexture = new TextureHolder(workspace, fields.get("portalTexture").getAsString());
		if (fields.has("enableIgniter")) dimension.enableIgniter = fields.get("enableIgniter").getAsBoolean();
		if (fields.has("igniterName")) dimension.igniterName = fields.get("igniterName").getAsString();
		if (fields.has("igniterRarity") && !fields.get("igniterRarity").getAsString().isEmpty())
			dimension.igniterRarity = fields.get("igniterRarity").getAsString();
		if (fields.has("texture") && !fields.get("texture").getAsString().isEmpty())
			dimension.texture = new TextureHolder(workspace, fields.get("texture").getAsString());
		if (fields.has("defaultEffects") && !fields.get("defaultEffects").getAsString().isEmpty())
			dimension.defaultEffects = fields.get("defaultEffects").getAsString();
		if (fields.has("useCustomEffects")) dimension.useCustomEffects = fields.get("useCustomEffects").getAsBoolean();
		if (fields.has("airColor")) {
			String hex = fields.get("airColor").getAsString();
			if (!hex.isEmpty()) dimension.airColor = java.awt.Color.decode(hex);
		}
		if (fields.has("skyType") && !fields.get("skyType").getAsString().isEmpty())
			dimension.skyType = fields.get("skyType").getAsString();
		if (fields.has("hasClouds")) dimension.hasClouds = fields.get("hasClouds").getAsBoolean();
		if (fields.has("cloudHeight")) dimension.cloudHeight = fields.get("cloudHeight").getAsInt();
		if (fields.has("sunHeightAffectsFog")) dimension.sunHeightAffectsFog = fields.get("sunHeightAffectsFog").getAsBoolean();
		if (fields.has("hasFog")) dimension.hasFog = fields.get("hasFog").getAsBoolean();
		if (fields.has("ambientLight")) dimension.ambientLight = fields.get("ambientLight").getAsDouble();
		if (fields.has("doesWaterVaporize")) dimension.doesWaterVaporize = fields.get("doesWaterVaporize").getAsBoolean();
		if (fields.has("hasFixedTime")) dimension.hasFixedTime = fields.get("hasFixedTime").getAsBoolean();
		if (fields.has("fixedTimeValue")) dimension.fixedTimeValue = fields.get("fixedTimeValue").getAsInt();
		if (fields.has("coordinateScale")) dimension.coordinateScale = fields.get("coordinateScale").getAsDouble();
		if (fields.has("infiniburnTag") && !fields.get("infiniburnTag").getAsString().isEmpty())
			dimension.infiniburnTag = fields.get("infiniburnTag").getAsString();
		if (fields.has("enableCustomSkyboxTextures")) dimension.enableCustomSkyboxTextures = fields.get("enableCustomSkyboxTextures").getAsBoolean();
		if (fields.has("skyboxTextureUp") && !fields.get("skyboxTextureUp").getAsString().isEmpty())
			dimension.skyboxTextureUp = new TextureHolder(workspace, fields.get("skyboxTextureUp").getAsString());
		if (fields.has("skyboxTextureDown") && !fields.get("skyboxTextureDown").getAsString().isEmpty())
			dimension.skyboxTextureDown = new TextureHolder(workspace, fields.get("skyboxTextureDown").getAsString());
		if (fields.has("skyboxTextureNorth") && !fields.get("skyboxTextureNorth").getAsString().isEmpty())
			dimension.skyboxTextureNorth = new TextureHolder(workspace, fields.get("skyboxTextureNorth").getAsString());
		if (fields.has("skyboxTextureSouth") && !fields.get("skyboxTextureSouth").getAsString().isEmpty())
			dimension.skyboxTextureSouth = new TextureHolder(workspace, fields.get("skyboxTextureSouth").getAsString());
		if (fields.has("skyboxTextureWest") && !fields.get("skyboxTextureWest").getAsString().isEmpty())
			dimension.skyboxTextureWest = new TextureHolder(workspace, fields.get("skyboxTextureWest").getAsString());
		if (fields.has("skyboxTextureEast") && !fields.get("skyboxTextureEast").getAsString().isEmpty())
			dimension.skyboxTextureEast = new TextureHolder(workspace, fields.get("skyboxTextureEast").getAsString());
		if (fields.has("enableCustomSunMoonTextures")) dimension.enableCustomSunMoonTextures = fields.get("enableCustomSunMoonTextures").getAsBoolean();
		if (fields.has("sunTexture") && !fields.get("sunTexture").getAsString().isEmpty())
			dimension.sunTexture = new TextureHolder(workspace, fields.get("sunTexture").getAsString());
		if (fields.has("moonTexture") && !fields.get("moonTexture").getAsString().isEmpty())
			dimension.moonTexture = new TextureHolder(workspace, fields.get("moonTexture").getAsString());
		if (fields.has("canRespawnHere")) dimension.canRespawnHere = fields.get("canRespawnHere").getAsBoolean();
		if (fields.has("bedWorks")) dimension.bedWorks = fields.get("bedWorks").getAsBoolean();
		if (fields.has("hasSkyLight")) dimension.hasSkyLight = fields.get("hasSkyLight").getAsBoolean();
		if (fields.has("imitateOverworldBehaviour")) dimension.imitateOverworldBehaviour = fields.get("imitateOverworldBehaviour").getAsBoolean();
		if (fields.has("piglinSafe")) dimension.piglinSafe = fields.get("piglinSafe").getAsBoolean();
		if (fields.has("hasRaids")) dimension.hasRaids = fields.get("hasRaids").getAsBoolean();
		if (fields.has("minMonsterSpawnLightLimit")) dimension.minMonsterSpawnLightLimit = fields.get("minMonsterSpawnLightLimit").getAsInt();
		if (fields.has("maxMonsterSpawnLightLimit")) dimension.maxMonsterSpawnLightLimit = fields.get("maxMonsterSpawnLightLimit").getAsInt();
		if (fields.has("monsterSpawnBlockLightLimit")) dimension.monsterSpawnBlockLightLimit = fields.get("monsterSpawnBlockLightLimit").getAsInt();
		if (fields.has("onPlayerEntersDimension") && !fields.get("onPlayerEntersDimension").getAsString().isEmpty())
			dimension.onPlayerEntersDimension = new net.mcreator.element.parts.procedure.Procedure(fields.get("onPlayerEntersDimension").getAsString());
		if (fields.has("onPlayerLeavesDimension") && !fields.get("onPlayerLeavesDimension").getAsString().isEmpty())
			dimension.onPlayerLeavesDimension = new net.mcreator.element.parts.procedure.Procedure(fields.get("onPlayerLeavesDimension").getAsString());
		if (fields.has("biomesInDimension"))
			dimension.biomesInDimension = parseBiomeEntries(workspace, fields.get("biomesInDimension"));
		if (fields.has("biomesInDimensionCaves"))
			dimension.biomesInDimensionCaves = parseBiomeEntries(workspace, fields.get("biomesInDimensionCaves"));
		if (fields.has("creativeTabs")) {
			java.util.List<net.mcreator.element.parts.TabEntry> tabs = new java.util.ArrayList<>();
			JsonElement raw = fields.get("creativeTabs");
			if (raw.isJsonArray()) {
				for (JsonElement el : raw.getAsJsonArray()) {
					if (el == null || el.isJsonNull()) continue;
					String v = el.getAsString().trim();
					if (!v.isEmpty()) tabs.add(new net.mcreator.element.parts.TabEntry(workspace, v));
				}
			} else if (raw.isJsonPrimitive()) {
				String v = raw.getAsString().trim();
				if (!v.isEmpty()) tabs.add(new net.mcreator.element.parts.TabEntry(workspace, v));
			}
			dimension.creativeTabs = tabs;
		}
		// a dimension with no biome cannot be entered so guarantee at least one
		if ((dimension.biomesInDimension == null || dimension.biomesInDimension.isEmpty())
				&& (dimension.biomesInDimensionCaves == null || dimension.biomesInDimensionCaves.isEmpty())) {
			if (dimension.biomesInDimension == null)
				dimension.biomesInDimension = new java.util.ArrayList<>();
			dimension.biomesInDimension.add(new net.mcreator.element.parts.BiomeEntry(workspace, "plains"));
		}
	}

	// reads a biome field that is either one biome value or an array of them and turns it
	// into backend biome entries, skipping blanks. a value starting CUSTOM: is a project biome
	private static java.util.List<net.mcreator.element.parts.BiomeEntry> parseBiomeEntries(Workspace workspace, JsonElement value) {
		java.util.List<net.mcreator.element.parts.BiomeEntry> list = new java.util.ArrayList<>();
		if (value == null || value.isJsonNull()) return list;
		if (value.isJsonArray()) {
			for (JsonElement el : value.getAsJsonArray()) {
				if (el == null || el.isJsonNull()) continue;
				String v = el.getAsString().trim();
				if (!v.isEmpty()) list.add(new net.mcreator.element.parts.BiomeEntry(workspace, v));
			}
		} else if (value.isJsonPrimitive()) {
			String v = value.getAsString().trim();
			if (!v.isEmpty()) list.add(new net.mcreator.element.parts.BiomeEntry(workspace, v));
		}
		return list;
	}

	// ============= ENCHANTMENT =============
	private static void setEnchantmentDefaults(net.mcreator.element.types.Enchantment enchantment, Workspace workspace) {
		if (enchantment.name == null) enchantment.name = "";
		if (enchantment.supportedSlots == null)
			enchantment.supportedSlots = new net.mcreator.element.parts.EquipmentSlotEntry(workspace, "any");
		if (enchantment.incompatibleEnchantments == null) enchantment.incompatibleEnchantments = new java.util.ArrayList<>();
		if (enchantment.supportedItems == null) enchantment.supportedItems = new java.util.ArrayList<>();
	}

	private static void applyEnchantmentFields(net.mcreator.element.types.Enchantment enchantment, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) enchantment.name = fields.get("name").getAsString();
		if (fields.has("supportedSlots") && !fields.get("supportedSlots").getAsString().isEmpty()) {
			try { enchantment.supportedSlots = new net.mcreator.element.parts.EquipmentSlotEntry(workspace, fields.get("supportedSlots").getAsString()); }
			catch (Exception e) { LOG.warn("applyEnchantmentFields: could not set supported slots", e); }
		}
		if (fields.has("weight")) enchantment.weight = fields.get("weight").getAsInt();
		if (fields.has("anvilCost")) enchantment.anvilCost = fields.get("anvilCost").getAsInt();
		if (fields.has("maxLevel")) enchantment.maxLevel = fields.get("maxLevel").getAsInt();
		if (fields.has("damageModifier")) enchantment.damageModifier = fields.get("damageModifier").getAsInt();
		if (fields.has("isTreasureEnchantment")) enchantment.isTreasureEnchantment = fields.get("isTreasureEnchantment").getAsBoolean();
		if (fields.has("isCurse")) enchantment.isCurse = fields.get("isCurse").getAsBoolean();
		if (fields.has("canGenerateInLootTables")) enchantment.canGenerateInLootTables = fields.get("canGenerateInLootTables").getAsBoolean();
		if (fields.has("canVillagerTrade")) enchantment.canVillagerTrade = fields.get("canVillagerTrade").getAsBoolean();
	}

	// ============= PARTICLE =============
	private static void setParticleDefaults(net.mcreator.element.types.Particle particle, Workspace workspace) {
		if (particle.name == null) particle.name = "";
		if (particle.renderType == null) particle.renderType = "translucent";
		if (particle.scale == null) particle.scale = new net.mcreator.element.parts.procedure.NumberProcedure(null, 1);
	}

	private static void applyParticleFields(net.mcreator.element.types.Particle particle, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) particle.name = fields.get("name").getAsString();
		if (fields.has("texture") && !fields.get("texture").getAsString().isEmpty())
			particle.texture = new TextureHolder(workspace, fields.get("texture").getAsString());
		if (fields.has("renderType") && !fields.get("renderType").getAsString().isEmpty()) {
			// config uses lowercase names, backend stores uppercase; "lit" maps
			// to OPAQUE + emissive rendering (LIT was removed from MCreator)
			String rt = fields.get("renderType").getAsString();
			if (rt.equalsIgnoreCase("lit")) {
				particle.renderType = "OPAQUE";
				particle.emissiveRendering = true;
			} else {
				particle.renderType = rt.toUpperCase();
				particle.emissiveRendering = false;
			}
		}
		if (fields.has("hasPhysics")) particle.canCollide = fields.get("hasPhysics").getAsBoolean();
		if (fields.has("gravity")) particle.gravity = fields.get("gravity").getAsDouble();
		if (fields.has("scale")) {
			double s = fields.get("scale").getAsDouble();
			particle.scale = new net.mcreator.element.parts.procedure.NumberProcedure(null, s);
		}
		if (fields.has("lifetime")) particle.maxAge = fields.get("lifetime").getAsInt();
		if (fields.has("onParticleTick") && !fields.get("onParticleTick").getAsString().isEmpty())
			particle.rotationProvider = new net.mcreator.element.parts.procedure.Procedure(fields.get("onParticleTick").getAsString());
	}

	private static JsonObject particleToFieldsJSON(net.mcreator.element.types.Particle particle) {
		JsonObject obj = generatableElementToFieldsJSON(particle);
		// convert render type back from the backend form (uppercase; OPAQUE +
		// emissiveRendering stands for the config's "lit" option)
		if (obj.has("renderType")) {
			String rt = obj.get("renderType").getAsString();
			boolean lit = obj.has("emissiveRendering") && obj.get("emissiveRendering").getAsBoolean();
			String cfg = lit ? "lit" : rt.toLowerCase();
			obj.addProperty("renderType", cfg);
			if (obj.has("render_type")) obj.addProperty("render_type", cfg);
		}
		// frontend config ids for backend fields
		if (obj.has("canCollide")) obj.add("hasPhysics", obj.get("canCollide").deepCopy());
		if (obj.has("maxAge")) obj.add("lifetime", obj.get("maxAge").deepCopy());
		return obj;
	}

	private static JsonObject plantToFieldsJSON(net.mcreator.element.types.Plant plant) {
		JsonObject obj = generatableElementToFieldsJSON(plant);
		// frontend config ids for backend fields
		if (obj.has("generateFeature")) obj.add("generateInWorld", obj.get("generateFeature").deepCopy());
		if (obj.has("isSolid")) obj.add("hasCollision", obj.get("isSolid").deepCopy());
		if (obj.has("frequencyOnChunks")) obj.add("spawnFrequency", obj.get("frequencyOnChunks").deepCopy());
		return obj;
	}

	private static JsonObject potionEffectToFieldsJSON(net.mcreator.element.types.PotionEffect pe) {
		JsonObject obj = generatableElementToFieldsJSON(pe);
		// frontend config ids for backend fields
		if (obj.has("effectName")) obj.add("name", obj.get("effectName").deepCopy());
		if (obj.has("icon")) obj.add("texture", obj.get("icon").deepCopy());
		if (obj.has("color")) obj.add("effectColor", obj.get("color").deepCopy());
		if (obj.has("mobEffectCategory")) {
			boolean beneficial = "BENEFICIAL".equals(obj.get("mobEffectCategory").getAsString());
			obj.addProperty("isBeneficial", beneficial);
		}
		return obj;
	}

	// ============= POTIONEFFECT =============
	private static void setPotionEffectDefaults(net.mcreator.element.types.PotionEffect potionEffect, Workspace workspace) {
		if (potionEffect.effectName == null) potionEffect.effectName = "";
		if (potionEffect.mobEffectCategory == null) potionEffect.mobEffectCategory = "NEUTRAL";
		if (potionEffect.color == null) potionEffect.color = java.awt.Color.RED;
		if (potionEffect.modifiers == null) potionEffect.modifiers = new java.util.ArrayList<>();
		if (potionEffect.onAddedSound == null) {
			try { potionEffect.onAddedSound = new net.mcreator.element.parts.Sound(workspace, "block.enchantment_table.use"); }
			catch (Exception e) { LOG.warn("setPotionEffectDefaults: could not set default sound", e); }
		}
	}

	private static void applyPotionEffectFields(net.mcreator.element.types.PotionEffect potionEffect, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) potionEffect.effectName = fields.get("name").getAsString();
		if (fields.has("texture") && !fields.get("texture").getAsString().isEmpty())
			potionEffect.icon = new TextureHolder(workspace, fields.get("texture").getAsString());
		if (fields.has("effectColor")) {
			String hex = fields.get("effectColor").getAsString();
			if (!hex.isEmpty()) potionEffect.color = java.awt.Color.decode(hex);
		}
		if (fields.has("isInstant")) potionEffect.isInstant = fields.get("isInstant").getAsBoolean();
		if (fields.has("isBeneficial")) {
			boolean beneficial = fields.get("isBeneficial").getAsBoolean();
			potionEffect.mobEffectCategory = beneficial ? "BENEFICIAL" : "HARMFUL";
		}
		if (fields.has("onActiveTick") && !fields.get("onActiveTick").getAsString().isEmpty())
			potionEffect.onActiveTick = new net.mcreator.element.parts.procedure.Procedure(fields.get("onActiveTick").getAsString());
		if (fields.has("onEffectStarted") && !fields.get("onEffectStarted").getAsString().isEmpty())
			potionEffect.onStarted = new net.mcreator.element.parts.procedure.Procedure(fields.get("onEffectStarted").getAsString());
		if (fields.has("onEffectEnded") && !fields.get("onEffectEnded").getAsString().isEmpty())
			potionEffect.onExpired = new net.mcreator.element.parts.procedure.Procedure(fields.get("onEffectEnded").getAsString());
	}

	// ============= POTION =============
	private static void setPotionDefaults(net.mcreator.element.types.Potion potion, Workspace workspace) {
		if (potion.potionName == null) potion.potionName = "";
		if (potion.splashName == null) potion.splashName = "";
		if (potion.lingeringName == null) potion.lingeringName = "";
		if (potion.arrowName == null) potion.arrowName = "";
		if (potion.effects == null) potion.effects = new java.util.ArrayList<>();
	}

	private static void applyPotionFields(net.mcreator.element.types.Potion potion, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) potion.potionName = fields.get("name").getAsString();
		if (fields.has("basePotion")) potion.basePotion = fields.get("basePotion").getAsString();
		if (fields.has("ingredient")) potion.ingredient = fields.get("ingredient").getAsString();
		if (fields.has("effect") || fields.has("duration") || fields.has("amplifier")) {
			if (potion.effects == null) potion.effects = new java.util.ArrayList<>();
			if (potion.effects.isEmpty()) potion.effects.add(new net.mcreator.element.types.Potion.CustomEffectEntry());
			net.mcreator.element.types.Potion.CustomEffectEntry entry = potion.effects.get(0);
			if (fields.has("effect") && !fields.get("effect").getAsString().isEmpty())
				entry.effect = new net.mcreator.element.parts.EffectEntry(workspace, fields.get("effect").getAsString());
			if (fields.has("duration")) entry.duration = fields.get("duration").getAsInt();
			if (fields.has("amplifier")) entry.amplifier = fields.get("amplifier").getAsInt();
		}
	}

	private static JsonObject potionToFieldsJSON(net.mcreator.element.types.Potion potion) {
		JsonObject obj = generatableElementToFieldsJSON(potion);
		// frontend config ids for backend fields
		if (obj.has("potionName")) obj.add("name", obj.get("potionName").deepCopy());
		if (potion.effects != null && !potion.effects.isEmpty()) {
			net.mcreator.element.types.Potion.CustomEffectEntry entry = potion.effects.get(0);
			if (entry.effect != null) obj.addProperty("effect", entry.effect.getUnmappedValue());
			obj.addProperty("duration", entry.duration);
			obj.addProperty("amplifier", entry.amplifier);
		}
		if (potion.basePotion != null) obj.addProperty("basePotion", potion.basePotion);
		if (potion.ingredient != null) obj.addProperty("ingredient", potion.ingredient);
		return obj;
	}

	// ============= LIVINGENTITY =============
	private static void setLivingEntityDefaults(net.mcreator.element.types.LivingEntity entity, Workspace workspace) {
		if (entity.mobName == null) entity.mobName = "";
		if (entity.mobLabel == null) entity.mobLabel = "";
		if (entity.mobModelName == null) entity.mobModelName = "Biped";
		if (entity.mobModelTexture == null) entity.mobModelTexture = "";
		if (entity.creativeTabs == null) entity.creativeTabs = new java.util.ArrayList<>();
		if (entity.bossBarColor == null) entity.bossBarColor = "PINK";
		if (entity.bossBarType == null) entity.bossBarType = "PROGRESS";
		if (entity.mobBehaviourType == null) entity.mobBehaviourType = "Creature";
		if (entity.mobCreatureType == null) entity.mobCreatureType = "UNDEFINED";
		if (entity.aiBase == null) entity.aiBase = "(none)";
		if (entity.mobSpawningType == null) entity.mobSpawningType = new net.mcreator.element.parts.MobSpawnType(workspace, "monster");
		if (entity.aixml == null) {
			try {
				java.lang.reflect.Field xf = net.mcreator.element.types.LivingEntity.class.getDeclaredField("XML_BASE");
				xf.setAccessible(true);
				entity.aixml = (String) xf.get(null);
			} catch (Exception e) {
				entity.aixml = "<xml xmlns=\"https://developers.google.com/blockly/xml\"><block type=\"aitasks_container\" deletable=\"false\" x=\"40\" y=\"40\"></block></xml>";
			}
		}
		if (entity.rangedItemType == null) entity.rangedItemType = "Default item";
		if (entity.equipmentMainHand == null)
			entity.equipmentMainHand = new net.mcreator.element.parts.MItemBlock(workspace, "");
		if (entity.equipmentOffHand == null)
			entity.equipmentOffHand = new net.mcreator.element.parts.MItemBlock(workspace, "");
		if (entity.equipmentHelmet == null)
			entity.equipmentHelmet = new net.mcreator.element.parts.MItemBlock(workspace, "");
		if (entity.equipmentBody == null)
			entity.equipmentBody = new net.mcreator.element.parts.MItemBlock(workspace, "");
		if (entity.equipmentLeggings == null)
			entity.equipmentLeggings = new net.mcreator.element.parts.MItemBlock(workspace, "");
		if (entity.equipmentBoots == null)
			entity.equipmentBoots = new net.mcreator.element.parts.MItemBlock(workspace, "");
		if (entity.mobDrop == null) entity.mobDrop = new net.mcreator.element.parts.MItemBlock(workspace, "");
		if (entity.rangedAttackItem == null)
			entity.rangedAttackItem = new net.mcreator.element.parts.MItemBlock(workspace, "");
		if (entity.modelLayers == null) entity.modelLayers = new java.util.ArrayList<>();
		if (entity.animations == null) entity.animations = new java.util.ArrayList<>();
		if (entity.entityDataEntries == null) entity.entityDataEntries = new java.util.ArrayList<>();
		if (entity.restrictionBiomes == null) entity.restrictionBiomes = new java.util.ArrayList<>();
		if (entity.vibrationalEvents == null) entity.vibrationalEvents = new java.util.ArrayList<>();
		if (entity.breedTriggerItems == null) entity.breedTriggerItems = new java.util.ArrayList<>();
		if (entity.visualScale == null) entity.visualScale = new net.mcreator.element.parts.procedure.NumberProcedure(null, 1);
		if (entity.boundingBoxScale == null) entity.boundingBoxScale = new net.mcreator.element.parts.procedure.NumberProcedure(null, 1);
		if (entity.transparentModelCondition == null) entity.transparentModelCondition = new net.mcreator.element.parts.procedure.LogicProcedure(null, false);
		if (entity.isShakingCondition == null) entity.isShakingCondition = new net.mcreator.element.parts.procedure.LogicProcedure(null, false);
		if (entity.solidBoundingBox == null) entity.solidBoundingBox = new net.mcreator.element.parts.procedure.LogicProcedure(null, false);
		if (entity.breatheUnderwater == null) entity.breatheUnderwater = new net.mcreator.element.parts.procedure.LogicProcedure(null, false);
		if (entity.pushedByFluids == null) entity.pushedByFluids = new net.mcreator.element.parts.procedure.LogicProcedure(null, false);
		if (entity.vibrationSensitivityRadius == null) entity.vibrationSensitivityRadius = new net.mcreator.element.parts.procedure.NumberProcedure(null, 16);
	}

	private static void applyLivingEntityFields(net.mcreator.element.types.LivingEntity entity, Workspace workspace, JsonObject fields) {
		if (fields.has("mobName")) entity.mobName = fields.get("mobName").getAsString();
		if (fields.has("name")) entity.mobName = fields.get("name").getAsString();
		if (fields.has("mobLabel")) entity.mobLabel = fields.get("mobLabel").getAsString();
		if (fields.has("mobModelName"))
			entity.mobModelName = fields.get("mobModelName").getAsString();
		if (fields.has("mobModelTexture"))
			entity.mobModelTexture = fields.get("mobModelTexture").getAsString();
		if (fields.has("modelWidth")) entity.modelWidth = fields.get("modelWidth").getAsDouble();
		if (fields.has("modelHeight")) entity.modelHeight = fields.get("modelHeight").getAsDouble();
		if (fields.has("modelShadowSize")) entity.modelShadowSize = fields.get("modelShadowSize").getAsDouble();
		if (fields.has("mountedYOffset")) entity.mountedYOffset = fields.get("mountedYOffset").getAsDouble();
		if (fields.has("health")) entity.health = fields.get("health").getAsInt();
		if (fields.has("attackStrength")) entity.attackStrength = fields.get("attackStrength").getAsInt();
		if (fields.has("attackKnockback")) entity.attackKnockback = fields.get("attackKnockback").getAsDouble();
		if (fields.has("movementSpeed")) entity.movementSpeed = fields.get("movementSpeed").getAsDouble();
		if (fields.has("stepHeight")) entity.stepHeight = fields.get("stepHeight").getAsDouble();
		if (fields.has("trackingRange")) entity.trackingRange = fields.get("trackingRange").getAsInt();
		if (fields.has("followRange")) entity.followRange = fields.get("followRange").getAsInt();
		if (fields.has("armorBaseValue")) entity.armorBaseValue = fields.get("armorBaseValue").getAsDouble();
		if (fields.has("knockbackResistance")) entity.knockbackResistance = fields.get("knockbackResistance").getAsDouble();
		if (fields.has("xpAmount")) entity.xpAmount = fields.get("xpAmount").getAsInt();
		if (fields.has("isBoss")) entity.isBoss = fields.get("isBoss").getAsBoolean();
		if (fields.has("bossBarColor") && !fields.get("bossBarColor").getAsString().isEmpty())
			entity.bossBarColor = fields.get("bossBarColor").getAsString();
		if (fields.has("bossBarType") && !fields.get("bossBarType").getAsString().isEmpty())
			entity.bossBarType = fields.get("bossBarType").getAsString();
		if (fields.has("hasSpawnEgg")) entity.hasSpawnEgg = fields.get("hasSpawnEgg").getAsBoolean();
		if (fields.has("spawnEggTexture") && !fields.get("spawnEggTexture").getAsString().isEmpty())
			entity.spawnEggTexture = new TextureHolder(workspace, fields.get("spawnEggTexture").getAsString());
		if (fields.has("waterMob")) entity.waterMob = fields.get("waterMob").getAsBoolean();
		if (fields.has("flyingMob")) entity.flyingMob = fields.get("flyingMob").getAsBoolean();
		if (fields.has("ridable")) entity.ridable = fields.get("ridable").getAsBoolean();
		if (fields.has("spawnThisMob")) entity.spawnThisMob = fields.get("spawnThisMob").getAsBoolean();
		if (fields.has("doesDespawnWhenIdle")) entity.doesDespawnWhenIdle = fields.get("doesDespawnWhenIdle").getAsBoolean();
		if (fields.has("spawningProbability")) entity.spawningProbability = fields.get("spawningProbability").getAsInt();
		if (fields.has("minNumberOfMobsPerGroup")) entity.minNumberOfMobsPerGroup = fields.get("minNumberOfMobsPerGroup").getAsInt();
		if (fields.has("maxNumberOfMobsPerGroup")) entity.maxNumberOfMobsPerGroup = fields.get("maxNumberOfMobsPerGroup").getAsInt();
		if (fields.has("spawnInDungeons")) entity.spawnInDungeons = fields.get("spawnInDungeons").getAsBoolean();
		if (fields.has("disableCollisions")) entity.disableCollisions = fields.get("disableCollisions").getAsBoolean();
		if (fields.has("canControlForward")) entity.canControlForward = fields.get("canControlForward").getAsBoolean();
		if (fields.has("canControlStrafe")) entity.canControlStrafe = fields.get("canControlStrafe").getAsBoolean();
		if (fields.has("inventorySize")) entity.inventorySize = fields.get("inventorySize").getAsInt();
		if (fields.has("inventoryStackSize")) entity.inventoryStackSize = fields.get("inventoryStackSize").getAsInt();
		if (fields.has("hasAI")) entity.hasAI = fields.get("hasAI").getAsBoolean();
		if (fields.has("aiBase") && !fields.get("aiBase").getAsString().isEmpty())
			entity.aiBase = fields.get("aiBase").getAsString();
		if (fields.has("mobSpawningType") && !fields.get("mobSpawningType").getAsString().isEmpty())
			entity.mobSpawningType = new net.mcreator.element.parts.MobSpawnType(workspace, fields.get("mobSpawningType").getAsString());
		if (fields.has("mobBehaviourType") && !fields.get("mobBehaviourType").getAsString().isEmpty())
			entity.mobBehaviourType = fields.get("mobBehaviourType").getAsString();
		if (fields.has("mobCreatureType") && !fields.get("mobCreatureType").getAsString().isEmpty())
			entity.mobCreatureType = fields.get("mobCreatureType").getAsString();
		if (fields.has("breedable")) entity.breedable = fields.get("breedable").getAsBoolean();
		if (fields.has("tameable")) entity.tameable = fields.get("tameable").getAsBoolean();
		if (fields.has("ranged")) entity.ranged = fields.get("ranged").getAsBoolean();
		if (fields.has("rangedAttackInterval")) entity.rangedAttackInterval = fields.get("rangedAttackInterval").getAsInt();
		if (fields.has("rangedAttackRadius")) entity.rangedAttackRadius = fields.get("rangedAttackRadius").getAsDouble();
		// equipment (MItemBlock)
		String[] equipFields = {"equipmentMainHand", "equipmentOffHand", "equipmentHelmet", "equipmentBody", "equipmentLeggings", "equipmentBoots"};
		for (String ef : equipFields) {
			if (fields.has(ef) && !fields.get(ef).getAsString().isEmpty()) {
				try {
					String converted = convertItemValue(workspace, fields.get(ef).getAsString());
					java.lang.reflect.Field jf = entity.getClass().getField(ef);
					jf.set(entity, new net.mcreator.element.parts.MItemBlock(workspace, converted));
				} catch (Exception ignored) {}
			}
		}
		// rangedAttackItem (MItemBlock)
		if (fields.has("rangedAttackItem") && !fields.get("rangedAttackItem").getAsString().isEmpty()) {
			String converted = convertItemValue(workspace, fields.get("rangedAttackItem").getAsString());
			entity.rangedAttackItem = new net.mcreator.element.parts.MItemBlock(workspace, converted);
		}
		// immunities
		String[] immuneFields = {"immuneToFire", "immuneToArrows", "immuneToFallDamage", "immuneToCactus",
				"immuneToDrowning", "immuneToLightning", "immuneToPotions", "immuneToPlayer",
				"immuneToExplosion", "immuneToTrident", "immuneToAnvil", "immuneToWither", "immuneToDragonBreath"};
		for (String f : immuneFields) {
			if (fields.has(f)) {
				try { java.lang.reflect.Field jf = entity.getClass().getField(f); jf.set(entity, fields.get(f).getAsBoolean()); }
				catch (Exception ignored) {}
			}
		}
		// sounds
		if (fields.has("livingSound") && !fields.get("livingSound").getAsString().isEmpty()) {
			try { entity.livingSound = new net.mcreator.element.parts.Sound(workspace, fields.get("livingSound").getAsString()); }
			catch (Exception e) { LOG.warn("applyLivingEntityFields: could not set living sound", e); }
		}
		if (fields.has("hurtSound") && !fields.get("hurtSound").getAsString().isEmpty()) {
			try { entity.hurtSound = new net.mcreator.element.parts.Sound(workspace, fields.get("hurtSound").getAsString()); }
			catch (Exception e) { LOG.warn("applyLivingEntityFields: could not set hurt sound", e); }
		}
		if (fields.has("deathSound") && !fields.get("deathSound").getAsString().isEmpty()) {
			try { entity.deathSound = new net.mcreator.element.parts.Sound(workspace, fields.get("deathSound").getAsString()); }
			catch (Exception e) { LOG.warn("applyLivingEntityFields: could not set death sound", e); }
		}
		if (fields.has("stepSound") && !fields.get("stepSound").getAsString().isEmpty()) {
			try { entity.stepSound = new net.mcreator.element.parts.Sound(workspace, fields.get("stepSound").getAsString()); }
			catch (Exception e) { LOG.warn("applyLivingEntityFields: could not set step sound", e); }
		}
		if (fields.has("raidCelebrationSound") && !fields.get("raidCelebrationSound").getAsString().isEmpty()) {
			try { entity.raidCelebrationSound = new net.mcreator.element.parts.Sound(workspace, fields.get("raidCelebrationSound").getAsString()); }
			catch (Exception e) { LOG.warn("applyLivingEntityFields: could not set raid sound", e); }
		}
		// spawn egg colors
		if (fields.has("spawnEggBaseColor")) {
			String hex = fields.get("spawnEggBaseColor").getAsString();
			if (!hex.isEmpty()) entity.spawnEggBaseColor = java.awt.Color.decode(hex);
		}
		if (fields.has("spawnEggDotColor")) {
			String hex = fields.get("spawnEggDotColor").getAsString();
			if (!hex.isEmpty()) entity.spawnEggDotColor = java.awt.Color.decode(hex);
		}
		// mob drop
		if (fields.has("mobDrop")) {
			String v = optStr(fields, "mobDrop", "");
			if (!v.isEmpty()) {
				String converted = convertItemValue(workspace, v);
				entity.mobDrop = new net.mcreator.element.parts.MItemBlock(workspace, converted);
			}
		}
		// triggers - frontend names -> java field names
		if (fields.has("onStruckByLightning") && !fields.get("onStruckByLightning").getAsString().isEmpty())
			entity.onStruckByLightning = new net.mcreator.element.parts.procedure.Procedure(fields.get("onStruckByLightning").getAsString());
		if (fields.has("onEntityFalls") && !fields.get("onEntityFalls").getAsString().isEmpty())
			entity.whenMobFalls = new net.mcreator.element.parts.procedure.Procedure(fields.get("onEntityFalls").getAsString());
		if (fields.has("onEntityDies") && !fields.get("onEntityDies").getAsString().isEmpty())
			entity.whenMobDies = new net.mcreator.element.parts.procedure.Procedure(fields.get("onEntityDies").getAsString());
		if (fields.has("whenMobIsHurt") && !fields.get("whenMobIsHurt").getAsString().isEmpty())
			entity.whenMobIsHurt = new net.mcreator.element.parts.procedure.Procedure(fields.get("whenMobIsHurt").getAsString());
		if (fields.has("onRightClickedOn") && !fields.get("onRightClickedOn").getAsString().isEmpty())
			entity.onRightClickedOn = new net.mcreator.element.parts.procedure.Procedure(fields.get("onRightClickedOn").getAsString());
		if (fields.has("whenThisMobKillsAnother") && !fields.get("whenThisMobKillsAnother").getAsString().isEmpty())
			entity.whenThisMobKillsAnother = new net.mcreator.element.parts.procedure.Procedure(fields.get("whenThisMobKillsAnother").getAsString());
		if (fields.has("onEntityTickUpdate") && !fields.get("onEntityTickUpdate").getAsString().isEmpty())
			entity.onMobTickUpdate = new net.mcreator.element.parts.procedure.Procedure(fields.get("onEntityTickUpdate").getAsString());
		if (fields.has("onPlayerCollidesWith") && !fields.get("onPlayerCollidesWith").getAsString().isEmpty())
			entity.onPlayerCollidesWith = new net.mcreator.element.parts.procedure.Procedure(fields.get("onPlayerCollidesWith").getAsString());
		if (fields.has("onInitialSpawn") && !fields.get("onInitialSpawn").getAsString().isEmpty())
			entity.onInitialSpawn = new net.mcreator.element.parts.procedure.Procedure(fields.get("onInitialSpawn").getAsString());
	}

	// ============= RECIPE =============
	private static void setRecipeDefaults(net.mcreator.element.types.Recipe recipe, Workspace workspace) {
		if (recipe.recipeType == null) recipe.recipeType = "crafting";
		if (recipe.namespace == null) recipe.namespace = "mod";
		if (recipe.name == null) recipe.name = "";
		if (recipe.craftingBookCategory == null) recipe.craftingBookCategory = "MISC";
		if (recipe.cookingBookCategory == null) recipe.cookingBookCategory = "MISC";
		if (recipe.unlockingItems == null) recipe.unlockingItems = new java.util.ArrayList<>();
		if (recipe.recipeSlots == null) {
			recipe.recipeSlots = new net.mcreator.element.parts.MItemBlock[9];
			for (int i = 0; i < recipe.recipeSlots.length; i++)
				recipe.recipeSlots[i] = new net.mcreator.element.parts.MItemBlock(workspace, "");
		}
	}

	private static void applyRecipeFields(net.mcreator.element.types.Recipe recipe, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) recipe.name = fields.get("name").getAsString();
		if (fields.has("namespace") && !fields.get("namespace").getAsString().isEmpty())
			recipe.namespace = fields.get("namespace").getAsString();
		if (fields.has("recipeType") && !fields.get("recipeType").getAsString().isEmpty())
			recipe.recipeType = fields.get("recipeType").getAsString();
		if (fields.has("recipeRetstackSize")) recipe.recipeRetstackSize = fields.get("recipeRetstackSize").getAsInt();
		if (fields.has("group")) recipe.group = fields.get("group").getAsString();
		if (fields.has("xpReward")) recipe.xpReward = fields.get("xpReward").getAsDouble();
		if (fields.has("cookingTime")) recipe.cookingTime = fields.get("cookingTime").getAsInt();
		if (fields.has("recipeShapeless")) recipe.recipeShapeless = fields.get("recipeShapeless").getAsBoolean();
		if (fields.has("craftingBookCategory") && !fields.get("craftingBookCategory").getAsString().isEmpty())
			recipe.craftingBookCategory = fields.get("craftingBookCategory").getAsString();
		if (fields.has("cookingBookCategory") && !fields.get("cookingBookCategory").getAsString().isEmpty())
			recipe.cookingBookCategory = fields.get("cookingBookCategory").getAsString();
		if (fields.has("recipeSlots") && fields.get("recipeSlots").isJsonArray()) {
			List<net.mcreator.element.parts.MItemBlock> slots = new ArrayList<>();
			for (JsonElement slot : fields.getAsJsonArray("recipeSlots"))
				slots.add(new net.mcreator.element.parts.MItemBlock(workspace, convertItemValue(workspace, slot.getAsString())));
			recipe.recipeSlots = slots.toArray(new net.mcreator.element.parts.MItemBlock[0]);
		}
		applyRecipeStack(recipe, workspace, fields, "recipeReturnStack");
		applyRecipeStack(recipe, workspace, fields, "smeltingInputStack");
		applyRecipeStack(recipe, workspace, fields, "smeltingReturnStack");
		applyRecipeStack(recipe, workspace, fields, "blastingInputStack");
		applyRecipeStack(recipe, workspace, fields, "blastingReturnStack");
		applyRecipeStack(recipe, workspace, fields, "smokingInputStack");
		applyRecipeStack(recipe, workspace, fields, "smokingReturnStack");
		applyRecipeStack(recipe, workspace, fields, "stoneCuttingInputStack");
		applyRecipeStack(recipe, workspace, fields, "stoneCuttingReturnStack");
		applyRecipeStack(recipe, workspace, fields, "campfireCookingInputStack");
		applyRecipeStack(recipe, workspace, fields, "campfireCookingReturnStack");
		applyRecipeStack(recipe, workspace, fields, "smithingInputStack");
		applyRecipeStack(recipe, workspace, fields, "smithingInputAdditionStack");
		applyRecipeStack(recipe, workspace, fields, "smithingInputTemplateStack");
		applyRecipeStack(recipe, workspace, fields, "smithingReturnStack");
		applyRecipeStack(recipe, workspace, fields, "brewingInputStack");
		applyRecipeStack(recipe, workspace, fields, "brewingIngredientStack");
		applyRecipeStack(recipe, workspace, fields, "brewingReturnStack");
	}

	private static void applyRecipeStack(net.mcreator.element.types.Recipe recipe, Workspace workspace,
			JsonObject fields, String field) {
		if (!fields.has(field) || fields.get(field).isJsonNull()) return;
		try {
			java.lang.reflect.Field target = recipe.getClass().getField(field);
			String value = fields.get(field).getAsString();
			target.set(recipe, new net.mcreator.element.parts.MItemBlock(workspace, convertItemValue(workspace, value)));
		} catch (NoSuchFieldException | IllegalAccessException ignored) {
		}
	}

	// ============= VILLAGERTRADE =============
	private static void setVillagerTradeDefaults(net.mcreator.element.types.VillagerTrade trade, Workspace workspace) {
		if (trade.trades == null) trade.trades = new java.util.ArrayList<>();
		if (trade.villagerProfession == null)
			trade.villagerProfession = new net.mcreator.element.parts.ProfessionEntry(workspace, "Farmer");
	}
	private static void applyVillagerTradeFields(net.mcreator.element.types.VillagerTrade trade, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) trade.name = fields.get("name").getAsString();
		if (fields.has("profession") && !fields.get("profession").getAsString().isEmpty()) {
			try { trade.villagerProfession = new net.mcreator.element.parts.ProfessionEntry(workspace, fields.get("profession").getAsString()); }
			catch (Exception e) { LOG.warn("applyVillagerTradeFields: could not set profession", e); }
		}
		// frontend sends individual fields for a single trade entry
		String itemIn1 = optStr(fields, "itemIn1", "");
		String itemIn2 = optStr(fields, "itemIn2", "");
		String itemOut = optStr(fields, "itemOut", "");
		if (!itemIn1.isEmpty() && !itemOut.isEmpty()) {
			net.mcreator.element.types.VillagerTrade.TradeEntry entry = new net.mcreator.element.types.VillagerTrade.TradeEntry();
			entry.price1 = new net.mcreator.element.parts.MItemBlock(workspace, convertItemValue(workspace, itemIn1));
			entry.countPrice1 = fields.has("itemIn1Count") ? fields.get("itemIn1Count").getAsInt() : 1;
			if (!itemIn2.isEmpty()) {
				entry.price2 = new net.mcreator.element.parts.MItemBlock(workspace, convertItemValue(workspace, itemIn2));
				entry.countPrice2 = fields.has("itemIn2Count") ? fields.get("itemIn2Count").getAsInt() : 1;
			}
			entry.offer = new net.mcreator.element.parts.MItemBlock(workspace, convertItemValue(workspace, itemOut));
			entry.countOffer = fields.has("itemOutCount") ? fields.get("itemOutCount").getAsInt() : 1;
			entry.level = fields.has("tradeLevel") ? fields.get("tradeLevel").getAsInt() : 1;
			entry.maxTrades = fields.has("maxUses") ? fields.get("maxUses").getAsInt() : 12;
			entry.xp = fields.has("xpReward") ? fields.get("xpReward").getAsInt() : 2;
			trade.trades.clear();
			trade.trades.add(entry);
		}
		// also handle trades as json array if provided
		if (fields.has("trades") && fields.get("trades").isJsonArray()) {
			trade.trades.clear();
			for (JsonElement te : fields.get("trades").getAsJsonArray()) {
				if (te.isJsonObject()) {
					JsonObject to = te.getAsJsonObject();
					net.mcreator.element.types.VillagerTrade.TradeEntry entry = new net.mcreator.element.types.VillagerTrade.TradeEntry();
					if (to.has("price1") && !to.get("price1").getAsString().isEmpty()) {
						String converted = convertItemValue(workspace, to.get("price1").getAsString());
						entry.price1 = new net.mcreator.element.parts.MItemBlock(workspace, converted);
					}
					if (to.has("countPrice1")) entry.countPrice1 = to.get("countPrice1").getAsInt();
					if (to.has("price2") && !to.get("price2").getAsString().isEmpty()) {
						String converted = convertItemValue(workspace, to.get("price2").getAsString());
						entry.price2 = new net.mcreator.element.parts.MItemBlock(workspace, converted);
					}
					if (to.has("countPrice2")) entry.countPrice2 = to.get("countPrice2").getAsInt();
					if (to.has("offer") && !to.get("offer").getAsString().isEmpty()) {
						String converted = convertItemValue(workspace, to.get("offer").getAsString());
						entry.offer = new net.mcreator.element.parts.MItemBlock(workspace, converted);
					}
					if (to.has("countOffer")) entry.countOffer = to.get("countOffer").getAsInt();
					if (to.has("level")) entry.level = to.get("level").getAsInt();
					if (to.has("maxTrades")) entry.maxTrades = to.get("maxTrades").getAsInt();
					if (to.has("xp")) entry.xp = to.get("xp").getAsInt();
					if (to.has("priceMultiplier")) entry.priceMultiplier = to.get("priceMultiplier").getAsDouble();
					trade.trades.add(entry);
				}
			}
		}
	}

	private static JsonObject villagerTradeToFieldsJSON(net.mcreator.element.types.VillagerTrade trade) {
		JsonObject obj = generatableElementToFieldsJSON(trade);
		// frontend config uses flat single-trade ids
		if (trade.villagerProfession != null)
			obj.addProperty("profession", trade.villagerProfession.getUnmappedValue());
		if (trade.name != null) obj.addProperty("name", trade.name);
		if (trade.trades != null && !trade.trades.isEmpty()) {
			net.mcreator.element.types.VillagerTrade.TradeEntry e = trade.trades.get(0);
			if (e.price1 != null) obj.addProperty("itemIn1", e.price1.getUnmappedValue());
			obj.addProperty("itemIn1Count", e.countPrice1);
			if (e.price2 != null) obj.addProperty("itemIn2", e.price2.getUnmappedValue());
			obj.addProperty("itemIn2Count", e.countPrice2);
			if (e.offer != null) obj.addProperty("itemOut", e.offer.getUnmappedValue());
			obj.addProperty("itemOutCount", e.countOffer);
			obj.addProperty("tradeLevel", e.level);
			obj.addProperty("xpReward", e.xp);
			obj.addProperty("maxUses", e.maxTrades);
		}
		return obj;
	}

	// ============= ACHIEVEMENT (Advancement) =============
	private static void setAchievementDefaults(net.mcreator.element.types.Achievement achievement, Workspace workspace) {
		if (achievement.achievementName == null) achievement.achievementName = "";
		if (achievement.achievementDescription == null) achievement.achievementDescription = "";
		if (achievement.background == null) achievement.background = "Default";
		if (achievement.achievementType == null) achievement.achievementType = "task";
		if (achievement.rewardLoot == null) achievement.rewardLoot = new java.util.ArrayList<>();
		if (achievement.rewardRecipes == null) achievement.rewardRecipes = new java.util.ArrayList<>();
	}

	private static void applyAchievementFields(net.mcreator.element.types.Achievement achievement, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) achievement.achievementName = fields.get("name").getAsString();
		if (fields.has("achievementDescription")) achievement.achievementDescription = fields.get("achievementDescription").getAsString();
		if (fields.has("achievementIcon")) {
			String v = optStr(fields, "achievementIcon", "");
			if (!v.isEmpty()) {
				String converted = convertItemValue(workspace, v);
				achievement.achievementIcon = new net.mcreator.element.parts.MItemBlock(workspace, converted);
			}
		}
		if (fields.has("background") && !fields.get("background").getAsString().isEmpty())
			achievement.background = fields.get("background").getAsString();
		if (fields.has("disableDisplay")) achievement.disableDisplay = fields.get("disableDisplay").getAsBoolean();
		if (fields.has("showPopup")) achievement.showPopup = fields.get("showPopup").getAsBoolean();
		if (fields.has("announceToChat")) achievement.announceToChat = fields.get("announceToChat").getAsBoolean();
		if (fields.has("hideIfNotCompleted")) achievement.hideIfNotCompleted = fields.get("hideIfNotCompleted").getAsBoolean();
		if (fields.has("rewardXP")) achievement.rewardXP = fields.get("rewardXP").getAsInt();
		if (fields.has("achievementType") && !fields.get("achievementType").getAsString().isEmpty())
			achievement.achievementType = fields.get("achievementType").getAsString();
	}

	// ============= LOOTTABLE =============
	private static String lootTableTypeFromConfig(String v) {
		if (v == null) return null;
		return switch (v) {
			case "empty" -> "Empty";
			case "chest" -> "Chest";
			case "block" -> "Block";
			case "entity" -> "Entity";
			case "fishing" -> "Fishing";
			case "gift" -> "Gift";
			case "advancement_reward" -> "Advancement reward";
			case "barter" -> "Barter";
			case "archaeology" -> "Archaeology";
			default -> v;
		};
	}

	private static String lootTableTypeToConfig(String v) {
		if (v == null) return null;
		return switch (v) {
			case "Empty" -> "empty";
			case "Chest" -> "chest";
			case "Block" -> "block";
			case "Entity" -> "entity";
			case "Fishing" -> "fishing";
			case "Gift" -> "gift";
			case "Advancement reward" -> "advancement_reward";
			case "Barter" -> "barter";
			case "Archaeology" -> "archaeology";
			default -> v;
		};
	}

	private static void setLootTableDefaults(net.mcreator.element.types.LootTable lootTable, Workspace workspace) {
		if (lootTable.type == null) lootTable.type = "Block";
		if (lootTable.name == null) lootTable.name = "";
		if (lootTable.namespace == null) lootTable.namespace = "mod";
		if (lootTable.pools == null) lootTable.pools = new java.util.ArrayList<>();
		if (lootTable.jsonCode == null) lootTable.jsonCode = "";
	}

	private static void applyLootTableFields(net.mcreator.element.types.LootTable lootTable, Workspace workspace, JsonObject fields) {
		if (fields.has("name")) lootTable.name = fields.get("name").getAsString();
		if (fields.has("namespace") && !fields.get("namespace").getAsString().isEmpty())
			lootTable.namespace = fields.get("namespace").getAsString();
		if (fields.has("type") && !fields.get("type").getAsString().isEmpty())
			lootTable.type = lootTableTypeFromConfig(fields.get("type").getAsString());
		if (fields.has("jsonCode")) lootTable.jsonCode = fields.get("jsonCode").getAsString();
	}

	private static JsonObject lootTableToFieldsJSON(net.mcreator.element.types.LootTable lootTable) {
		JsonObject obj = new JsonObject();
		obj.addProperty("name", lootTable.name);
		obj.addProperty("namespace", lootTable.namespace);
		obj.addProperty("type", lootTableTypeToConfig(lootTable.type));
		if (lootTable.jsonCode != null) obj.addProperty("jsonCode", lootTable.jsonCode);
		return obj;
	}

	/**
	 * Lists all mod elements in a workspace.
	 */
	private static void listElements(JsonObject args) {
		String modid = optStr(args, "modid", "").trim();
		if (modid.isEmpty()) { sendError("modid is required"); return; }

		File wsFolder = getWorkspaceFolder(args);
		if (wsFolder == null) return;

		try {
			Workspace workspace = Workspace.readFromFS(Objects.requireNonNull(findWorkspaceFile(wsFolder), "workspace file not found"), null);
			JsonArray result = new JsonArray();
			for (ModElement me : workspace.getModElements()) {
				JsonObject entry = new JsonObject();
				entry.addProperty("name", me.getName());
				entry.addProperty("type", me.getType().getRegistryName());
				entry.addProperty("registry_name", me.getRegistryName());
				entry.addProperty("compiles", me.doesCompile());
				result.add(entry);
			}
			workspace.close();

			JsonObject data = new JsonObject();
			data.add("elements", result);
			data.addProperty("count", result.size());
			sendResult(data);
		} catch (Exception e) {
			LOG.error("list_elements failed", e);
			sendError("list_elements: " + e.getMessage());
		}
	}

	/**
	 * Loads full field data for a single mod element.
	 */
	private static void getElement(JsonObject args) {
		String modid = optStr(args, "modid", "").trim();
		String name = optStr(args, "name", "").trim();
		if (modid.isEmpty() || name.isEmpty()) { sendError("modid and name are required"); return; }

		File wsFolder = getWorkspaceFolder(args);
		if (wsFolder == null) return;

		try {
			Workspace workspace = Workspace.readFromFS(Objects.requireNonNull(findWorkspaceFile(wsFolder), "workspace file not found"), null);
			ModElement me = workspace.getModElementByName(name);
			if (me == null) {
				workspace.close();
				sendError("element not found: " + name);
				return;
			}

			JsonObject data = new JsonObject();
			data.addProperty("name", me.getName());
			data.addProperty("type", me.getType().getRegistryName());
			data.addProperty("registry_name", me.getRegistryName());
			JsonArray generatedFiles = new JsonArray();
			for (File generatedFile : me.getAssociatedFiles()) {
				generatedFiles.add(workspace.getFolderManager().getPathInWorkspace(generatedFile).replace(File.separator, "/"));
			}
			data.add("generated_files", generatedFiles);

			GeneratableElement ge = me.getGeneratableElement();
			if (ge != null) {
				JsonObject fields;
				if (ge instanceof net.mcreator.element.types.Armor) {
					fields = armorToFieldsJSON((net.mcreator.element.types.Armor) ge);
				} else if (ge instanceof net.mcreator.element.types.KeyBinding) {
					fields = keyBindingToFieldsJSON((net.mcreator.element.types.KeyBinding) ge);
				} else if (ge instanceof net.mcreator.element.types.LootTable) {
					fields = lootTableToFieldsJSON((net.mcreator.element.types.LootTable) ge);
				} else if (ge instanceof net.mcreator.element.types.Painting) {
					fields = paintingToFieldsJSON((net.mcreator.element.types.Painting) ge);
				} else if (ge instanceof net.mcreator.element.types.Particle) {
					fields = particleToFieldsJSON((net.mcreator.element.types.Particle) ge);
				} else if (ge instanceof net.mcreator.element.types.Plant) {
					fields = plantToFieldsJSON((net.mcreator.element.types.Plant) ge);
				} else if (ge instanceof net.mcreator.element.types.PotionEffect) {
					fields = potionEffectToFieldsJSON((net.mcreator.element.types.PotionEffect) ge);
				} else if (ge instanceof net.mcreator.element.types.Potion) {
					fields = potionToFieldsJSON((net.mcreator.element.types.Potion) ge);
				} else if (ge instanceof net.mcreator.element.types.Tool) {
					fields = toolToFieldsJSON((net.mcreator.element.types.Tool) ge);
				} else if (ge instanceof net.mcreator.element.types.VillagerTrade) {
					fields = villagerTradeToFieldsJSON((net.mcreator.element.types.VillagerTrade) ge);
				} else {
					fields = generatableElementToFieldsJSON(ge);
				}
				data.add("fields", fields);
				Object storedSelections = me.getMetadata("custom_procedure_selections");
				if (storedSelections != null) {
					JsonElement storedJson = GSON.toJsonTree(storedSelections);
					if (storedJson.isJsonObject()) {
						JsonObject selections = storedJson.getAsJsonObject();
						for (String key : selections.keySet()) fields.add(key, selections.get(key).deepCopy());
					}
				}
			} else {
				data.add("fields", new JsonObject());
			}

			workspace.close();
			sendResult(data);
		} catch (Exception e) {
			LOG.error("get_element failed", e);
			sendError("get_element: " + e.getMessage());
		}
	}

	/**
	 * Updates an existing mod element's fields.
	 */
	static void updateElement(JsonObject args) throws Exception {
		Workspace workspace = null;
		WorkspaceJournal journal = null;
		boolean committed = false;
		Exception failure = null;
		try {
		String modid = optStr(args, "modid", "").trim();
		String name = optStr(args, "name", "").trim();
		if (modid.isEmpty() || name.isEmpty()) { sendError("modid and name are required"); return; }

		File wsFolder = getWorkspaceFolder(args);
		if (wsFolder == null) return;

		File wsFile = findWorkspaceFile(wsFolder);
		if (wsFile == null) { sendError("workspace file not found"); return; }

			workspace = Workspace.readFromFS(wsFile, null);
			journal = WorkspaceJournal.capture(wsFolder.toPath());
			SOURCE_PREPARATION.ensureReady(workspace);
			ModElement me = workspace.getModElementByName(name);
			if (me == null) {
				workspace.closeWithoutSaveStrict();
				workspace = null;
				sendError("element not found: " + name);
				return;
			}

			GeneratableElement element = me.getGeneratableElement();
			if (element == null) {
				workspace.closeWithoutSaveStrict();
				workspace = null;
				sendError("could not load generatable element: " + name);
				return;
			}

		// Apply fields from JSON args
		if (args.has("fields") && args.get("fields").isJsonObject()) {
			JsonObject fields = args.getAsJsonObject("fields");
			storeCustomProcedureSelections(me, fields);
			if (element instanceof net.mcreator.element.types.Item item) {
				applyItemFields(item, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Block block) {
				applyBlockFields(block, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Armor armor) {
				setArmorDefaults(armor, workspace);
				applyArmorFields(armor, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Biome biome) {
				applyBiomeFields(biome, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Tool tool) {
				setToolDefaults(tool, workspace);
				applyToolFields(tool, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Fluid fluid) {
				setFluidDefaults(fluid, workspace);
				applyFluidFields(fluid, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Command command) {
				setCommandDefaults(command, workspace);
				applyCommandFields(command, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Function function) {
				setFunctionDefaults(function, workspace);
				applyFunctionFields(function, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.CustomElement customElement) {
				setCustomElementDefaults(customElement);
				applyCustomElementFields(customElement, fields);
			} else if (element instanceof net.mcreator.element.types.KeyBinding keyBinding) {
				setKeyBindingDefaults(keyBinding, workspace);
				applyKeyBindingFields(keyBinding, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Tab tab) {
				setTabDefaults(tab, workspace);
				applyTabFields(tab, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Painting painting) {
				setPaintingDefaults(painting, workspace);
				applyPaintingFields(painting, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Plant plant) {
				setPlantDefaults(plant, workspace);
				applyPlantFields(plant, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Dimension dimension) {
				setDimensionDefaults(dimension, workspace);
				applyDimensionFields(dimension, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Enchantment enchantment) {
				setEnchantmentDefaults(enchantment, workspace);
				applyEnchantmentFields(enchantment, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Particle particle) {
				setParticleDefaults(particle, workspace);
				applyParticleFields(particle, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.PotionEffect potionEffect) {
				setPotionEffectDefaults(potionEffect, workspace);
				applyPotionEffectFields(potionEffect, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Potion potion) {
				setPotionDefaults(potion, workspace);
				applyPotionFields(potion, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.LivingEntity livingEntity) {
				setLivingEntityDefaults(livingEntity, workspace);
				applyLivingEntityFields(livingEntity, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Recipe recipe) {
				setRecipeDefaults(recipe, workspace);
				applyRecipeFields(recipe, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.VillagerTrade villagerTrade) {
				setVillagerTradeDefaults(villagerTrade, workspace);
				applyVillagerTradeFields(villagerTrade, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.Achievement achievement) {
				setAchievementDefaults(achievement, workspace);
				applyAchievementFields(achievement, workspace, fields);
			} else if (element instanceof net.mcreator.element.types.LootTable lootTable) {
				setLootTableDefaults(lootTable, workspace);
				applyLootTableFields(lootTable, workspace, fields);
			}
			applyProcedureFieldsReflectively(element, fields);
		}

		validateElementInvariants(element, workspace);

		sendProgress("Generating code...", 30);
		GenerationCoordinator.generateElementStrict(workspace, element);

		sendProgress("Saving element picture...", 45);
		workspace.getModElementManager().storeModElementPictureStrict(element);

		sendProgress("Saving element data...", 55);
		workspace.getModElementManager().storeModElementStrict(element);

		// persist element before base regen so global registries see it on disk
		sendProgress("Updating registries...", 60);
		GenerationCoordinator.generateBaseStrict(workspace);

		sendProgress("Saving workspace...", 90);
		workspace.getFileManager().saveWorkspaceDirectlyAndWaitStrict();
		verifySavedWorkspace(wsFile, modid, workspace.getModElements(),
				GenerationCoordinator.trackedBasePaths(workspace), name);

		JsonObject result = new JsonObject();
		result.addProperty("name", name);
		result.addProperty("type", me.getType().getRegistryName());
		result.addProperty("registry_name", me.getRegistryName());

		workspace.closeStrict();
		workspace = null;
		committed = true;
		sendResult(result);
		} catch (Exception e) {
			failure = e;
			throw e;
		} finally {
			Exception rollbackError = null;
			if (workspace != null) {
				try {
					if (committed)
						workspace.close();
					else
						workspace.closeWithoutSaveStrict();
				} catch (Exception e) {
					rollbackError = e;
				}
			}
			if (!committed && journal != null) {
				try {
					journal.restore();
				} catch (Exception e) {
					if (rollbackError == null) rollbackError = e;
					else rollbackError.addSuppressed(e);
				}
			}
			if (rollbackError != null) {
				if (failure != null) failure.addSuppressed(rollbackError);
				else throw rollbackError;
			}
		}
	}

	/**
	 * Deletes a mod element from the workspace and regenerates base files.
	 */
	static void deleteElement(JsonObject args) throws Exception {
		String modid = optStr(args, "modid", "").trim();
		String name = optStr(args, "name", "").trim();
		if (modid.isEmpty() || name.isEmpty()) { sendError("modid and name are required"); return; }

		File wsFolder = getWorkspaceFolder(args);
		if (wsFolder == null) return;

		File wsFile = findWorkspaceFile(wsFolder);
		if (wsFile == null) { sendError("workspace file not found"); return; }

			Workspace workspace = null;
			WorkspaceJournal journal = null;
			Exception failure = null;
			boolean committed = false;
			try {
				workspace = Workspace.readFromFS(wsFile, null);
				ModElement me = workspace.getModElementByName(name);
				if (me == null) {
					workspace.closeWithoutSaveStrict();
					workspace = null;
					sendError("element not found: " + name);
					return;
				}

				journal = WorkspaceJournal.capture(wsFolder.toPath());
				sendProgress("Removing element files...", 30);
				workspace.removeModElement(me);

				sendProgress("Regenerating registries...", 60);
				GenerationCoordinator.generateBaseStrict(workspace);

				sendProgress("Saving workspace...", 85);
				workspace.getFileManager().saveWorkspaceDirectlyAndWaitStrict();
				verifySavedWorkspace(wsFile, modid, workspace.getModElements(),
						GenerationCoordinator.trackedBasePaths(workspace), null);

				JsonObject result = new JsonObject();
				result.addProperty("deleted", true);
				result.addProperty("name", name);

				workspace.closeStrict();
				workspace = null;
				committed = true;
				sendResult(result);
			} catch (Exception e) {
				failure = e;
				throw e;
			} finally {
				Exception rollbackError = null;
				if (workspace != null) {
					try {
						if (committed)
							workspace.close();
						else
							workspace.closeWithoutSaveStrict();
					} catch (Exception e) {
						rollbackError = e;
					}
				}
				if (!committed && journal != null) {
					try {
						journal.restore();
					} catch (Exception e) {
						if (rollbackError == null) rollbackError = e;
						else rollbackError.addSuppressed(e);
					}
				}
				if (rollbackError != null) {
					if (failure != null) failure.addSuppressed(rollbackError);
					else throw rollbackError;
				}
			}
	}

	/**
	 * Extracts public field values from a GeneratableElement into a JSON object.
	 */
	private static JsonObject generatableElementToFieldsJSON(GeneratableElement ge) {
		JsonObject obj = new JsonObject();
		// walk the whole class hierarchy (excluding GeneratableElement itself)
		// so inherited fields such as NamespacedGeneratableElement.name and
		// .namespace are also exposed to the frontend
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (Class<?> c = ge.getClass(); c != null && c != GeneratableElement.class; c = c.getSuperclass()) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (!seen.add(f.getName())) continue;
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
				if (java.lang.reflect.Modifier.isTransient(f.getModifiers())) continue;
				try {
					f.setAccessible(true);
				Object val = f.get(ge);
				String key = f.getName();
				if (val == null) {
					obj.add(key, com.google.gson.JsonNull.INSTANCE);
				} else if (val instanceof TextureHolder th) {
					obj.addProperty(key, th.toString());
				} else if (val instanceof net.mcreator.element.parts.procedure.NumberProcedure np) {
					obj.addProperty(key, np.getFixedValue());
				} else if (val instanceof net.mcreator.element.parts.procedure.Procedure p) {
					obj.addProperty(key, p.getName() != null ? p.getName() : "");
				} else if (val instanceof net.mcreator.generator.mapping.MappableElement me) {
					obj.addProperty(key, me.getUnmappedValue());
				} else if (val instanceof java.awt.Color color) {
					String hex = String.format("#%06x", color.getRGB() & 0xFFFFFF);
					obj.addProperty(key, hex);
				} else if (val instanceof String s) {
					if (ge instanceof net.mcreator.element.types.Armor && f.getName().equals("armorTextureFile")) {
						obj.addProperty("armor_layer_texture_1", s + "_layer_1");
						obj.addProperty("armor_layer_texture_2", s + "_layer_2");
					} else {
						obj.addProperty(key, s);
					}
				} else if (val instanceof Number n) {
					if (ge instanceof net.mcreator.element.types.Block && f.getName().equals("rotationMode")) {
						// frontend config dropdown uses names, backend stores int
						String rotStr = switch (n.intValue()) {
							case 1 -> "player_y_axis";
							case 2 -> "player_all_axis";
							case 3 -> "block_y_axis";
							case 4 -> "block_all_axis";
							case 5 -> "log";
							default -> "none";
						};
						obj.addProperty(key, rotStr);
					} else {
						obj.addProperty(key, n);
					}
				} else if (val instanceof Boolean b) {
					if (ge instanceof net.mcreator.element.types.Biome && f.getName().equals("spawnParticles")) {
						// frontend config treats spawn_particles as a dropdown ("0"/"1")
						obj.addProperty("spawn_particles", b ? "1" : "0");
					} else {
						obj.addProperty(key, b);
					}
				} else if (val instanceof net.mcreator.element.types.Biome.ClimatePoint cp) {
					// frontend config expects separate gen_<x>_min / gen_<x>_max ids
					String base = camelToSnake(key);
					obj.addProperty(base + "_min", cp.min);
					obj.addProperty(base + "_max", cp.max);
				} else if (val instanceof JsonElement je) {
					obj.add(key, je);
				} else if (val instanceof java.util.List<?> list) {
					obj.add(key, GSON.toJsonTree(val));
				} else {
					obj.add(key, GSON.toJsonTree(val));
				}
				// also expose a snake_case alias of the field name so frontend
				// element configs that use snake_case ids can look the value up
				// by the config id (e.g. ground_block <-> groundBlock)
				String snakeKey = camelToSnake(key);
				if (!snakeKey.equals(key) && !obj.has(snakeKey) && obj.has(key)) {
					obj.add(snakeKey, obj.get(key).deepCopy());
				}
				} catch (Throwable e) {
					LOG.error("generatableElementToFieldsJSON failed for field " + f.getName(), e);
				}
			}
		}
		// music-disc frontend config uses short ids that map onto Item's
		// music disc fields (musicDiscDescription etc. and creativeTabs)
		if (ge instanceof net.mcreator.element.types.Item) {
			if (obj.has("musicDiscMusic")) obj.add("music", obj.get("musicDiscMusic").deepCopy());
			if (obj.has("musicDiscDescription")) obj.add("description", obj.get("musicDiscDescription").deepCopy());
			if (obj.has("musicDiscLengthInTicks")) obj.add("lengthInTicks", obj.get("musicDiscLengthInTicks").deepCopy());
			if (obj.has("musicDiscAnalogOutput")) obj.add("analogOutput", obj.get("musicDiscAnalogOutput").deepCopy());
			if (obj.has("creativeTabs") && obj.get("creativeTabs").isJsonArray()) {
				JsonArray tabs = obj.getAsJsonArray("creativeTabs");
				if (!tabs.isEmpty() && tabs.get(0).isJsonObject()) {
					JsonObject first = tabs.get(0).getAsJsonObject();
					if (first.has("value")) obj.addProperty("creativeTab", first.get("value").getAsString());
				}
			}
			// ranged-item frontend config maps customModel onto the use animation
			if (obj.has("animation")) {
				String anim = obj.get("animation").getAsString();
				String cm = "Normal";
				if ("bow".equals(anim)) cm = "Bow";
				else if ("crossbow".equals(anim)) cm = "Crossbow";
				obj.addProperty("customModel", cm);
			}
		}
		return obj;
	}

	private static void applyProcedureFieldsReflectively(GeneratableElement element, JsonObject fields) {
		for (String key : fields.keySet()) {
			if (!fields.get(key).isJsonPrimitive() || !fields.get(key).getAsJsonPrimitive().isString()) continue;
			String value = fields.get(key).getAsString();
			if (value.isEmpty()) continue;
			try {
				java.lang.reflect.Field field = element.getClass().getField(key);
				if (field.getType() == net.mcreator.element.parts.procedure.Procedure.class)
					field.set(element, new net.mcreator.element.parts.procedure.Procedure(value));
			} catch (NoSuchFieldException ignored) {
			} catch (Exception e) {
				LOG.warn("Could not apply procedure field {}", key, e);
			}
		}
	}

	private static void storeCustomProcedureSelections(ModElement element, JsonObject fields) {
		JsonObject selections = new JsonObject();
		for (String key : fields.keySet()) {
			if (key.startsWith("on") && fields.get(key).isJsonPrimitive()
					&& fields.get(key).getAsJsonPrimitive().isString()
					&& !fields.get(key).getAsString().isEmpty())
				selections.addProperty(key, fields.get(key).getAsString());
		}
		if (!selections.isEmpty()) element.putMetadata("custom_procedure_selections", selections);
	}

	private static String camelToSnake(String camel) {
		if (camel == null || camel.isEmpty()) return camel;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < camel.length(); i++) {
			char c = camel.charAt(i);
			if (Character.isUpperCase(c)) {
				if (i > 0) sb.append('_');
				sb.append(Character.toLowerCase(c));
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private static JsonObject armorToFieldsJSON(net.mcreator.element.types.Armor armor) {
		JsonObject obj = new JsonObject();

		obj.addProperty("enable_helmet", armor.enableHelmet);
		if (armor.textureHelmet != null) obj.addProperty("texture_helmet", armor.textureHelmet.toString());
		obj.addProperty("helmet_name", armor.helmetName != null ? armor.helmetName : "");
		obj.addProperty("helmet_immune_to_fire", armor.helmetImmuneToFire);
		obj.addProperty("helmet_glow_effect", armor.helmetGlowCondition != null && armor.helmetGlowCondition.getFixedValue());
		obj.addProperty("helmet_piglin_neutral", armor.helmetPiglinNeutral != null && armor.helmetPiglinNeutral.getFixedValue());
		if (armor.helmetSpecialInformation != null && !armor.helmetSpecialInformation.getFixedValue().isEmpty())
			obj.addProperty("helmet_special_info", String.join("\n", armor.helmetSpecialInformation.getFixedValue()));
		obj.addProperty("helmet_item_render_type", armor.helmetItemRenderType);
		obj.addProperty("helmet_custom_model_name", armor.helmetItemCustomModelName != null ? armor.helmetItemCustomModelName : "Normal");

		obj.addProperty("enable_chestplate", armor.enableBody);
		if (armor.textureBody != null) obj.addProperty("texture_chestplate", armor.textureBody.toString());
		obj.addProperty("chestplate_name", armor.bodyName != null ? armor.bodyName : "");
		obj.addProperty("chestplate_immune_to_fire", armor.bodyImmuneToFire);
		obj.addProperty("chestplate_glow_effect", armor.bodyGlowCondition != null && armor.bodyGlowCondition.getFixedValue());
		obj.addProperty("chestplate_piglin_neutral", armor.bodyPiglinNeutral != null && armor.bodyPiglinNeutral.getFixedValue());
		if (armor.bodySpecialInformation != null && !armor.bodySpecialInformation.getFixedValue().isEmpty())
			obj.addProperty("chestplate_special_info", String.join("\n", armor.bodySpecialInformation.getFixedValue()));
		obj.addProperty("chestplate_item_render_type", armor.bodyItemRenderType);
		obj.addProperty("chestplate_custom_model_name", armor.bodyItemCustomModelName != null ? armor.bodyItemCustomModelName : "Normal");

		obj.addProperty("enable_leggings", armor.enableLeggings);
		if (armor.textureLeggings != null) obj.addProperty("texture_leggings", armor.textureLeggings.toString());
		obj.addProperty("leggings_name", armor.leggingsName != null ? armor.leggingsName : "");
		obj.addProperty("leggings_immune_to_fire", armor.leggingsImmuneToFire);
		obj.addProperty("leggings_glow_effect", armor.leggingsGlowCondition != null && armor.leggingsGlowCondition.getFixedValue());
		obj.addProperty("leggings_piglin_neutral", armor.leggingsPiglinNeutral != null && armor.leggingsPiglinNeutral.getFixedValue());
		if (armor.leggingsSpecialInformation != null && !armor.leggingsSpecialInformation.getFixedValue().isEmpty())
			obj.addProperty("leggings_special_info", String.join("\n", armor.leggingsSpecialInformation.getFixedValue()));
		obj.addProperty("leggings_item_render_type", armor.leggingsItemRenderType);
		obj.addProperty("leggings_custom_model_name", armor.leggingsItemCustomModelName != null ? armor.leggingsItemCustomModelName : "Normal");

		obj.addProperty("enable_boots", armor.enableBoots);
		if (armor.textureBoots != null) obj.addProperty("texture_boots", armor.textureBoots.toString());
		obj.addProperty("boots_name", armor.bootsName != null ? armor.bootsName : "");
		obj.addProperty("boots_immune_to_fire", armor.bootsImmuneToFire);
		obj.addProperty("boots_glow_effect", armor.bootsGlowCondition != null && armor.bootsGlowCondition.getFixedValue());
		obj.addProperty("boots_piglin_neutral", armor.bootsPiglinNeutral != null && armor.bootsPiglinNeutral.getFixedValue());
		if (armor.bootsSpecialInformation != null && !armor.bootsSpecialInformation.getFixedValue().isEmpty())
			obj.addProperty("boots_special_info", String.join("\n", armor.bootsSpecialInformation.getFixedValue()));
		obj.addProperty("boots_item_render_type", armor.bootsItemRenderType);
		obj.addProperty("boots_custom_model_name", armor.bootsItemCustomModelName != null ? armor.bootsItemCustomModelName : "Normal");

		if (armor.armorTextureFile != null) {
			obj.addProperty("armor_layer_texture_1", armor.armorTextureFile + "_layer_1");
			obj.addProperty("armor_layer_texture_2", armor.armorTextureFile + "_layer_2");
		}
		obj.addProperty("rarity", armor.rarity != null ? armor.rarity : "COMMON");
		if (armor.creativeTabs != null && !armor.creativeTabs.isEmpty())
			obj.addProperty("creative_tabs", armor.creativeTabs.get(0).getUnmappedValue());
		if (armor.equipSound != null) obj.addProperty("equip_sound", armor.equipSound.getUnmappedValue());
		obj.addProperty("max_damage", armor.maxDamage);
		obj.addProperty("damage_value_helmet", armor.damageValueHelmet);
		obj.addProperty("damage_value_chestplate", armor.damageValueBody);
		obj.addProperty("damage_value_leggings", armor.damageValueLeggings);
		obj.addProperty("damage_value_boots", armor.damageValueBoots);
		obj.addProperty("enchantability", armor.enchantability);
		obj.addProperty("toughness", armor.toughness);
		obj.addProperty("knockback_resistance", armor.knockbackResistance);

		if (armor.repairItems != null && !armor.repairItems.isEmpty())
			obj.addProperty("repair_items", armor.repairItems.get(0).getUnmappedValue());

		if (armor.onHelmetTick != null && armor.onHelmetTick.getName() != null)
			obj.addProperty("onHelmetTick", armor.onHelmetTick.getName());
		if (armor.onBodyTick != null && armor.onBodyTick.getName() != null)
			obj.addProperty("onChestplateTick", armor.onBodyTick.getName());
		if (armor.onLeggingsTick != null && armor.onLeggingsTick.getName() != null)
			obj.addProperty("onLeggingsTick", armor.onLeggingsTick.getName());
		if (armor.onBootsTick != null && armor.onBootsTick.getName() != null)
			obj.addProperty("onBootsTick", armor.onBootsTick.getName());

		return obj;
	}

	/**
	 * blocksitems mapping key (like "Blocks.STONE#0") by reverse-looking-up the
	 * generator's blocksitems mapping table.
	 */
	private static String convertItemValue(Workspace workspace, String frontendValue) {
		if (frontendValue == null || frontendValue.isEmpty()) return frontendValue;
		if (frontendValue.startsWith("Blocks.") || frontendValue.startsWith("Items.")) return frontendValue;
		if (frontendValue.startsWith("CUSTOM:") || frontendValue.startsWith("TAG:")) return frontendValue;

		try {
			Map<?, ?> mapping = workspace.getGenerator().getMappings().getMapping("blocksitems");
			if (mapping != null) {
				for (Map.Entry<?, ?> entry : mapping.entrySet()) {
					Object key = entry.getKey();
					if (key == null || key.toString().startsWith("_")) continue;
					Object value = entry.getValue();
					if (value instanceof List<?> list && list.size() > 1) {
						Object registryName = list.get(1);
						if (registryName != null && (registryName.toString().equals(frontendValue)
								|| (frontendValue.startsWith("minecraft:") && registryName.toString()
								.equals(frontendValue.substring("minecraft:".length()))))) {
							return key.toString();
						}
					}
				}
			}
		} catch (Exception e) {
			LOG.warn("convertItemValue failed for {}: {}", frontendValue, e.getMessage());
		}
		return frontendValue;
	}

	/**
	 * Resolves the workspace folder for a given modid.
	 */
	private static File findWorkspaceFolder(String modid) {
		File root = WorkspaceFolderManager.getSuggestedWorkspaceFoldersRoot();
		File wsFolder = new File(root, modid);
		if (!wsFolder.isDirectory()) {
			File[] candidates = root.listFiles(File::isDirectory);
			if (candidates != null) {
				for (File candidate : candidates) {
					File file = findWorkspaceFile(candidate);
					if (file != null && workspaceModid(file).equals(modid)) {
						wsFolder = candidate;
						break;
					}
				}
			}
		}
		if (!wsFolder.isDirectory()) {
			return null;
		}
		return wsFolder;
	}

	record WorkspaceTarget(File folder, File file, String modid) {
	}

	static String validateWorkspaceTarget(JsonObject args) {
		return resolveWorkspaceTarget(args).error();
	}

	private static WorkspaceTarget getWorkspaceTarget(JsonObject args) {
		WorkspaceTargetResolution resolution = resolveWorkspaceTarget(args);
		if (resolution.error() != null) {
			sendError(resolution.error());
			return null;
		}
		return resolution.target();
	}

	private static WorkspaceTargetResolution resolveWorkspaceTarget(JsonObject args) {
		String requestedModid = optStr(args, "modid", "").trim();
		if (requestedModid.isEmpty()) {
			return WorkspaceTargetResolution.invalid("modid is required");
		}
		String folder = optStr(args, "folder", "").trim();
		File wsFolder = folder.isEmpty() ? findWorkspaceFolder(requestedModid) : new File(folder).getAbsoluteFile();
		if (wsFolder == null) {
			return WorkspaceTargetResolution.invalid("workspace folder not found for modid: " + requestedModid);
		}
		try {
			wsFolder = wsFolder.getCanonicalFile();
		} catch (IOException e) {
			return WorkspaceTargetResolution.invalid("workspace folder is invalid: " + e.getMessage());
		}
		if (!wsFolder.isDirectory()) {
			return WorkspaceTargetResolution.invalid("workspace folder not found: " + wsFolder);
		}
		File wsFile = findWorkspaceFile(wsFolder);
		if (wsFile == null) {
			return WorkspaceTargetResolution.invalid("workspace marker not found: " + wsFolder);
		}
		String actualModid = workspaceModid(wsFile).trim();
		if (actualModid.isEmpty()) {
			return WorkspaceTargetResolution.invalid("workspace modid is missing: " + wsFile.getName());
		}
		if (!actualModid.equals(requestedModid)) {
			return WorkspaceTargetResolution.invalid("workspace modid does not match requested modid");
		}
		return new WorkspaceTargetResolution(new WorkspaceTarget(wsFolder, wsFile, actualModid), null);
	}

	private record WorkspaceTargetResolution(WorkspaceTarget target, String error) {
		private static WorkspaceTargetResolution invalid(String error) {
			return new WorkspaceTargetResolution(null, error);
		}
	}

	private static File getWorkspaceFolder(JsonObject args) {
		WorkspaceTarget target = getWorkspaceTarget(args);
		return target == null ? null : target.folder();
	}

	private static File optionalFolder(JsonObject args) {
		String folder = optStr(args, "folder", "").trim();
		return folder.isEmpty() ? null : new File(folder).getAbsoluteFile();
	}

	private static String workspaceModid(File workspaceFile) {
		try {
			JsonObject root = JsonParser.parseString(Files.readString(workspaceFile.toPath())).getAsJsonObject();
			return root.has("workspaceSettings") && root.get("workspaceSettings").isJsonObject()
					? optStr(root.getAsJsonObject("workspaceSettings"), "modid", "") : "";
		} catch (Exception ignored) {
			return "";
		}
	}

	private static boolean isSafeResourceName(String name) {
		return name != null && !name.isEmpty() && !name.contains("\\") && !name.contains("..")
				&& !new File(name).isAbsolute() && name.matches("[A-Za-z0-9_./-]+")
				&& !name.startsWith("/") && !name.endsWith("/");
	}

	private static File resolveWorkspaceResourceFile(File workspaceFolder, String name, String resourceDir) {
		if (!isSafeResourceName(name)) return null;
		File workspaceFile = findWorkspaceFile(workspaceFolder);
		if (workspaceFile == null) return null;
		String modid = workspaceModid(workspaceFile);
		if (modid.isEmpty()) return null;
		File root = new File(workspaceFolder, "src/main/resources/assets");
		File file = new File(root, modid + "/" + resourceDir + "/" + name);
		try {
			File canonicalRoot = root.getCanonicalFile();
			File canonicalFile = file.getCanonicalFile();
			return canonicalFile.toPath().startsWith(canonicalRoot.toPath()) ? canonicalFile : null;
		} catch (IOException ignored) {
			return null;
		}
	}

	private static File resolveResourceFile(JsonObject args, String path) throws IOException {
		File workspaceFolder = getWorkspaceFolder(args);
		if (workspaceFolder == null) throw new IOException("workspace folder not found");
		File requested = new File(path);
		File file = requested.isAbsolute() ? requested : new File(workspaceFolder, path);
		File root = workspaceFolder.getCanonicalFile();
		File canonical = file.getCanonicalFile();
		if (!canonical.toPath().startsWith(root.toPath())) throw new IOException("resource path is outside workspace");
		return canonical;
	}

	/**
	 * Finds the most recent .modstudio_ai / .mcreator file in the folder.
	 */
	private static File findWorkspaceFile(File folder) {
		if (!folder.isDirectory()) return null;
		File[] files = folder.listFiles(File::isFile);
		if (files == null) return null;
		return Arrays.stream(files)
				.filter(f -> {
					String n = f.getName();
					return n.endsWith(".modstudio_ai") || n.endsWith(".mcreator");
				})
				.max(Comparator.comparingLong(File::lastModified))
				.orElse(null);
	}

	private static String optStr(JsonObject o, String key, String def) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull())
			return def;
		if (e.isJsonPrimitive() && e.getAsString() != null)
			return e.getAsString();
		return def;
	}

	private static boolean optBool(JsonObject o, String key, boolean def) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull())
			return def;
		if (e.isJsonPrimitive() && e.getAsBoolean())
			return true;
		return def;
	}

	private static String toPascalCase(String input) {
		if (input == null || input.isEmpty()) return input;
		StringBuilder sb = new StringBuilder();
		boolean capitalizeNext = true;
		for (char c : input.toCharArray()) {
			if (Character.isWhitespace(c) || c == '_') {
				capitalizeNext = true;
			} else if (capitalizeNext) {
				sb.append(Character.toUpperCase(c));
				capitalizeNext = false;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

}
