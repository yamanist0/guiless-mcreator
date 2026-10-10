package net.mcreator;

import com.google.gson.JsonObject;
import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildActionExecuter;
import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.ModelBuilder;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.TestLauncher;
import org.gradle.tooling.ResultHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleTransactionTest {

	@TempDir Path temporaryDirectory;

	@AfterEach void restoreSeams() {
		IPCServer.SOURCE_PREPARATION = GradleSourcePreparation::ensureReady;
		IPCServer.GRADLE_CONNECTION = net.mcreator.gradle.GradleUtils::getGradleProjectConnection;
	}

	@Test void createElementPersistsGeneratedOutputs() throws Exception {
		File folder = newProjectWithFunctionElement("lifecycle_test");

		assertTrue(folder.toPath().resolve("src/main/resources/data/lifecycle_test/function/smoke.mcfunction")
				.toFile().isFile());
		assertTrue(folder.toPath().resolve("elements/Smoke.mod.json").toFile().isFile());
		String saved = Files.readString(folder.toPath().resolve("lifecycle_test.modstudio_ai"));
		assertTrue(saved.contains("Smoke"));
	}

	@Test void createElementFailureLeavesWorkspaceUnchanged() throws Exception {
		File folder = newProject("lifecycle_test");
		IPCServer.SOURCE_PREPARATION = workspace -> {
		};
		Path blocker = folder.toPath().resolve("src/main/resources/data/lifecycle_test/function");
		Files.createDirectories(blocker.getParent());
		Files.writeString(blocker, "blocker");
		Map<String, String> before = snapshot(folder);

		assertThrows(IOException.class, () -> IPCServer.createElement(functionArgs(folder, "lifecycle_test")));

		assertEquals(before, snapshot(folder));
		assertFalse(folder.toPath().resolve("elements/Smoke.mod.json").toFile().isFile());
	}

	@Test void updateElementFailureRestoresPreviousState() throws Exception {
		File folder = newProjectWithFunctionElement("lifecycle_test");
		Path output = folder.toPath().resolve("src/main/resources/data/lifecycle_test/function/smoke.mcfunction");
		Files.delete(output);
		Files.createDirectory(output);
		Files.writeString(output.resolve("child"), "child");
		Map<String, String> before = snapshot(folder);

		JsonObject args = functionArgs(folder, "lifecycle_test");
		args.getAsJsonObject("fields").addProperty("code", "say changed");
		assertThrows(IOException.class, () -> IPCServer.updateElement(args));

		assertEquals(before, snapshot(folder));
	}

	@Test void deleteElementFailureKeepsElement() throws Exception {
		File folder = newProjectWithFunctionElement("lifecycle_test");
		Path base = folder.toPath().resolve("src/main/resources/META-INF/neoforge.mods.toml");
		Files.delete(base);
		Files.createDirectory(base);
		Files.writeString(base.resolve("child"), "child");
		Map<String, String> before = snapshot(folder);

		JsonObject args = new JsonObject();
		args.addProperty("folder", folder.getAbsolutePath());
		args.addProperty("modid", "lifecycle_test");
		args.addProperty("name", "Smoke");
		assertThrows(IOException.class, () -> IPCServer.deleteElement(args));

		assertEquals(before, snapshot(folder));
		assertTrue(folder.toPath().resolve("elements/Smoke.mod.json").toFile().isFile());
	}

	@Test void runGameFailurePreventsLaunchAndRestoresWorkspace() throws Exception {
		File folder = newProjectWithFunctionElement("lifecycle_test");
		Path output = folder.toPath().resolve("src/main/resources/data/lifecycle_test/function/smoke.mcfunction");
		Files.delete(output);
		Files.createDirectory(output);
		Files.writeString(output.resolve("child"), "child");
		Map<String, String> before = snapshot(folder, "game-output.log");
		IPCServer.GRADLE_CONNECTION = workspace -> new StubConnection();

		IPCServer.WorkspaceTarget target = new IPCServer.WorkspaceTarget(folder,
				folder.toPath().resolve("lifecycle_test.modstudio_ai").toFile(), "lifecycle_test");
		AtomicBoolean started = new AtomicBoolean();
		assertThrows(IOException.class, () -> IPCServer.runGame(target, started));

		assertFalse(started.get());
		String log = Files.readString(folder.toPath().resolve("game-output.log"));
		assertTrue(log.contains("EXIT_CODE: 1"));
		assertEquals(before, snapshot(folder, "game-output.log"));
	}

	@Test void runGameFailsClosedWithoutGradleConnection() throws Exception {
		File folder = newProjectWithFunctionElement("lifecycle_test");
		IPCServer.GRADLE_CONNECTION = workspace -> null;

		IPCServer.WorkspaceTarget target = new IPCServer.WorkspaceTarget(folder,
				folder.toPath().resolve("lifecycle_test.modstudio_ai").toFile(), "lifecycle_test");
		AtomicBoolean started = new AtomicBoolean();
		assertThrows(IOException.class, () -> IPCServer.runGame(target, started));

		assertFalse(started.get());
		String log = Files.readString(folder.toPath().resolve("game-output.log"));
		assertTrue(log.contains("EXIT_CODE: 1"));
	}

	private static JsonObject functionArgs(File folder, String modid) {
		JsonObject args = new JsonObject();
		args.addProperty("folder", folder.getAbsolutePath());
		args.addProperty("modid", modid);
		args.addProperty("name", "Smoke");
		args.addProperty("type", "function");
		JsonObject fields = new JsonObject();
		fields.addProperty("name", "smoke");
		fields.addProperty("namespace", modid);
		fields.addProperty("code", "say hello");
		args.add("fields", fields);
		return args;
	}

	private File newProjectWithFunctionElement(String modid) throws Exception {
		File folder = newProject(modid);
		IPCServer.SOURCE_PREPARATION = workspace -> {
		};
		assertDoesNotThrow(() -> IPCServer.createElement(functionArgs(folder, modid)));
		return folder;
	}

	private File newProject(String modid) throws IOException {
		if (net.mcreator.element.ModElementType.ADVANCEMENT == null)
			initHeadlessApplicationState();
		initLauncherState();
		net.mcreator.workspace.settings.WorkspaceSettings settings = new net.mcreator.workspace.settings.WorkspaceSettings(
				modid);
		settings.setModName("Lifecycle Test");
		settings.setVersion("1.0.0");
		settings.setDescription("lifecycle test mod");
		settings.setAuthor("test");
		settings.setLicense("MIT");
		settings.setModElementsPackage("net.mcreator." + modid);
		settings.setCurrentGenerator("neoforge-1.21.1");
		Path root = Files.createDirectory(temporaryDirectory.resolve("ws-" + System.nanoTime()));
		net.mcreator.workspace.Workspace workspace = net.mcreator.workspace.Workspace.createWorkspace(
				root.resolve(modid + ".modstudio_ai").toFile(), settings);
		File folder = workspace.getWorkspaceFolder();
		workspace.closeWithoutSaveStrict();
		return folder;
	}

	// mirrors the headless application init so generation runs like in production
	private static void initHeadlessApplicationState() {
		initLauncherState();
		net.mcreator.init.L10N.initTranslations();
		net.mcreator.init.BlockItemIcons.init();
		net.mcreator.minecraft.DataListLoader.preloadCache();
		net.mcreator.init.ImageMakerTexturesCache.init();
		net.mcreator.init.ArmorMakerTexturesCache.init();
		net.mcreator.plugin.modapis.ModAPIManager.initAPIs();
		net.mcreator.workspace.elements.VariableTypeLoader.loadVariableTypes();
		net.mcreator.init.BlocklyJavaScriptsLoader.init();
		net.mcreator.init.BlocklyToolboxesLoader.init();
		net.mcreator.blockly.data.BlocklyLoader.init();
		net.mcreator.init.EntityAnimationsLoader.init();
		net.mcreator.element.ModElementTypeLoader.loadModElements();
		java.util.List<String> fileNames = new java.util.ArrayList<>(net.mcreator.plugin.PluginLoader.INSTANCE.getResources(
				java.util.regex.Pattern.compile("generator\\.yaml")));
		java.util.Collections.sort(fileNames);
		for (String generator : fileNames) {
			generator = generator.replace("/generator.yaml", "");
			net.mcreator.generator.Generator.GENERATOR_CACHE.put(generator,
					new net.mcreator.generator.GeneratorConfiguration(generator));
		}
	}

	private static void initLauncherState() {
		if (net.mcreator.Launcher.version == null) {
			java.util.Properties properties = new java.util.Properties();
			properties.setProperty("mcreator", "2026.1");
			net.mcreator.Launcher.version = new net.mcreator.util.MCreatorVersionNumber(properties);
		}
		if (net.mcreator.preferences.PreferencesManager.PREFERENCES == null)
			net.mcreator.preferences.PreferencesManager.init();
		if (net.mcreator.plugin.PluginLoader.INSTANCE == null)
			net.mcreator.plugin.PluginLoader.initInstance();
	}

	private static Map<String, String> snapshot(File folder, String... excluded) throws IOException {
		Map<String, String> files = new TreeMap<>();
		List<String> excludedNames = Arrays.asList(excluded);
		try (Stream<Path> paths = Files.walk(folder.toPath())) {
			for (Path path : paths.toList()) {
				String key = folder.toPath().relativize(path).toString().replace('\\', '/');
				if (key.startsWith(".modstudio_ai/localHistory"))
					continue;
				if (excludedNames.contains(key))
					continue;
				if (Files.isRegularFile(path))
					files.put(key, java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
			}
		}
		return files;
	}

	private static final class StubConnection implements ProjectConnection {
		@Override public BuildLauncher newBuild() {
			throw new UnsupportedOperationException("stub connection must not run builds");
		}

		@Override public TestLauncher newTestLauncher() {
			throw new UnsupportedOperationException("stub connection must not run builds");
		}

		@Override public <T> ModelBuilder<T> model(Class<T> aClass) {
			throw new UnsupportedOperationException("stub connection must not run builds");
		}

		@Override public <T> T getModel(Class<T> aClass) {
			throw new UnsupportedOperationException("stub connection must not run builds");
		}

		@Override public <T> void getModel(Class<T> aClass, ResultHandler<? super T> resultHandler) {
			throw new UnsupportedOperationException("stub connection must not run builds");
		}

		@Override public <T> BuildActionExecuter<T> action(BuildAction<T> buildAction) {
			throw new UnsupportedOperationException("stub connection must not run builds");
		}

		@Override public BuildActionExecuter.Builder action() {
			throw new UnsupportedOperationException("stub connection must not run builds");
		}

		@Override public void notifyDaemonsAboutChangedPaths(List<Path> list) {
		}

		@Override public void close() {
		}
	}
}
