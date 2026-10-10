package net.mcreator;

import net.mcreator.generator.Generator;
import net.mcreator.generator.io.GradleTrackingFileIO;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.settings.WorkspaceSettings;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrictFileIOTest {

	@TempDir Path temporaryDirectory;

	private Workspace workspace;

	@AfterEach void closeWorkspace() throws IOException {
		if (workspace != null) {
			workspace.closeWithoutSaveStrict();
			workspace = null;
		}
	}

	private static final class StrictProvider implements net.mcreator.generator.IGeneratorProvider {
		private final Generator generator;

		StrictProvider(Generator generator) {
			this.generator = generator;
		}

		@Override public Workspace getWorkspace() {
			return generator.getWorkspace();
		}

		@Override public Generator getGenerator() {
			return generator;
		}
	}

	@Test void strictWriteCreatesParentDirectoriesAndWritesContent() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("strict-write"));
		Generator generator = newGenerator(root);
		generator.setStrictFileWrites(true);
		File target = root.resolve("nested/deep/output.txt").toFile();

		GradleTrackingFileIO.writeFile(new StrictProvider(generator), "hello", target);

		assertTrue(target.isFile());
		assertTrue(target.length() > 0);
	}

	@Test void strictWriteFailsOnDirectoryTarget() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("strict-write-dir"));
		Generator generator = newGenerator(root);
		generator.setStrictFileWrites(true);
		File target = Files.createDirectory(root.resolve("target-dir")).toFile();

		assertThrows(GradleTrackingFileIO.StrictFileWriteException.class,
				() -> GradleTrackingFileIO.writeFile(new StrictProvider(generator), "hello", target));
	}

	@Test void strictDeleteRemovesExistingFileAndToleratesMissingFile() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("strict-delete"));
		Generator generator = newGenerator(root);
		generator.setStrictFileWrites(true);
		File existing = Files.writeString(root.resolve("existing.txt"), "content").toFile();
		File missing = root.resolve("missing.txt").toFile();

		assertDoesNotThrow(() -> GradleTrackingFileIO.deleteFile(new StrictProvider(generator), existing));
		assertFalse(existing.isFile());
		assertDoesNotThrow(() -> GradleTrackingFileIO.deleteFile(new StrictProvider(generator), missing));
	}

	@Test void strictDeleteFailsOnNonEmptyDirectory() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("strict-delete-dir"));
		Generator generator = newGenerator(root);
		generator.setStrictFileWrites(true);
		Path directory = Files.createDirectory(root.resolve("occupied"));
		Files.writeString(directory.resolve("child"), "child");

		assertThrows(GradleTrackingFileIO.StrictFileWriteException.class,
				() -> GradleTrackingFileIO.deleteFile(new StrictProvider(generator), directory.toFile()));
	}

	@Test void strictEmptyDirectoryRemovesChildren() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("strict-empty"));
		Generator generator = newGenerator(root);
		generator.setStrictFileWrites(true);
		Path directory = Files.createDirectory(root.resolve("models"));
		Files.writeString(directory.resolve("model.json"), "{}");
		Files.createDirectory(directory.resolve("nested"));
		Files.writeString(directory.resolve("nested/inner"), "inner");

		assertDoesNotThrow(() -> GradleTrackingFileIO.emptyDirectory(new StrictProvider(generator), directory.toFile()));

		assertTrue(Files.isDirectory(directory));
		try (var paths = Files.list(directory)) {
			assertEquals(0, paths.count());
		}
	}

	@Test void strictCopyCreatesParentAndCopiesBytes() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("strict-copy"));
		Generator generator = newGenerator(root);
		generator.setStrictFileWrites(true);
		File source = Files.writeString(root.resolve("source.png"), "pngbytes").toFile();
		File target = root.resolve("nested/target.png").toFile();

		GradleTrackingFileIO.copyFile(new StrictProvider(generator), source, target);

		assertTrue(target.isFile());
		org.junit.jupiter.api.Assertions.assertArrayEquals(
				"pngbytes".getBytes(java.nio.charset.StandardCharsets.UTF_8), Files.readAllBytes(target.toPath()));
	}

	@Test void strictCopyFailsOnMissingSource() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("strict-copy-missing"));
		Generator generator = newGenerator(root);
		generator.setStrictFileWrites(true);

		assertThrows(GradleTrackingFileIO.StrictFileWriteException.class,
				() -> GradleTrackingFileIO.copyFile(new StrictProvider(generator),
						root.resolve("missing-source").toFile(), root.resolve("target").toFile()));
	}

	@Test void strictPNGWriteWritesDecodableImage() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("strict-png"));
		Generator generator = newGenerator(root);
		generator.setStrictFileWrites(true);
		File target = root.resolve("nested/icon.png").toFile();
		BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);

		GradleTrackingFileIO.writePNG(new StrictProvider(generator), image, target);

		assertTrue(target.isFile());
		assertNotNull(ImageIO.read(target));
	}

	@Test void strictWriteFailsWhenParentIsAFile() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("strict-write-file-parent"));
		Generator generator = newGenerator(root);
		generator.setStrictFileWrites(true);
		Files.writeString(root.resolve("blocker"), "blocker");
		File target = root.resolve("blocker/output.txt").toFile();

		assertThrows(GradleTrackingFileIO.StrictFileWriteException.class,
				() -> GradleTrackingFileIO.writeFile(new StrictProvider(generator), "hello", target));
	}

	@Test void nonStrictWriteToleratesFailuresLikeLegacyBehavior() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("legacy"));
		Generator generator = newGenerator(root);
		generator.setStrictFileWrites(false);
		File target = Files.createDirectory(root.resolve("target-dir")).toFile();

		assertDoesNotThrow(() -> GradleTrackingFileIO.writeFile(new StrictProvider(generator), "hello", target));
	}

	@Test void strictWriteFailureIsRolledBackByJournal() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("strict-write-rollback"));
		Generator generator = newGenerator(root);
		generator.setStrictFileWrites(true);
		Path workspaceRoot = workspace.getFileManager().getWorkspaceFile().getParentFile().toPath();
		Files.createDirectories(workspaceRoot.resolve("modElementThumbnails"));
		Path original = Files.writeString(workspaceRoot.resolve("modElementThumbnails/old.png"), "before");
		WorkspaceJournal journal = WorkspaceJournal.capture(workspaceRoot);
		Files.writeString(original, "partial");
		Path blocker = workspaceRoot.resolve("nested");
		Files.writeString(blocker, "blocker");
		File target = workspaceRoot.resolve("nested/out.txt").toFile();

		assertThrows(GradleTrackingFileIO.StrictFileWriteException.class,
				() -> GradleTrackingFileIO.writeFile(new StrictProvider(generator), "hello", target));
		journal.restore();

		assertEquals("before", Files.readString(original));
		assertFalse(Files.exists(workspaceRoot.resolve("nested/out.txt")));
	}

	private Generator newGenerator(Path root) throws IOException {
		if (net.mcreator.Launcher.version == null) {
			java.util.Properties properties = new java.util.Properties();
			properties.setProperty("mcreator", "2026.1");
			net.mcreator.Launcher.version = new net.mcreator.util.MCreatorVersionNumber(properties);
		}
		if (net.mcreator.preferences.PreferencesManager.PREFERENCES == null)
			net.mcreator.preferences.PreferencesManager.init();
		if (net.mcreator.plugin.PluginLoader.INSTANCE == null)
			net.mcreator.plugin.PluginLoader.initInstance();
		WorkspaceSettings settings = new WorkspaceSettings("strict_io_test");
		settings.setCurrentGenerator("neoforge-1.21.1");
		workspace = Workspace.createWorkspace(root.resolve("strict_io_test.modstudio_ai").toFile(), settings);
		return workspace.getGenerator();
	}
}
