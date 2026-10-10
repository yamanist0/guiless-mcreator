package net.mcreator;

import net.mcreator.element.GeneratableElement;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.elements.ModElement;
import net.mcreator.workspace.elements.ModElementManager;
import net.mcreator.workspace.settings.WorkspaceSettings;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModElementPictureTest {

	@TempDir Path temporaryDirectory;

	private Workspace workspace;

	@AfterEach void closeWorkspace() throws IOException {
		if (workspace != null) {
			workspace.closeWithoutSaveStrict();
			workspace = null;
		}
	}

	@Test void nullPictureDeletesStaleThumbnail() throws IOException {
		Workspace workspace = newWorkspace();
		ModElementManager manager = workspace.getModElementManager();
		ModElement element = new ModElement(workspace, "NullPicture", net.mcreator.element.ModElementType.ADVANCEMENT);
		File destination = new File(workspace.getFolderManager().getModElementPicturesCacheDir(),
				"NullPicture.png");
		destination.getParentFile().mkdirs();
		Files.writeString(destination.toPath(), "stale");

		manager.storeModElementPictureStrict(new GeneratableElement(element) {
			@Override public java.awt.image.BufferedImage generateModElementPicture() {
				return null;
			}
		});

		assertFalse(destination.isFile());
	}

	@Test void pictureGenerationFailureIsChecked() throws IOException {
		Workspace workspace = newWorkspace();
		ModElementManager manager = workspace.getModElementManager();
		ModElement element = new ModElement(workspace, "WriteFailure", net.mcreator.element.ModElementType.ADVANCEMENT);

		assertThrows(IOException.class,
				() -> manager.storeModElementPictureStrict(new GeneratableElement(element) {
					@Override public BufferedImage generateModElementPicture() {
						throw new IllegalStateException("picture renderer failed");
					}
				}));
	}

	@Test void pictureFailureIsRolledBackByJournal() throws IOException {
		Workspace workspace = newWorkspace();
		ModElementManager manager = workspace.getModElementManager();
		ModElement element = new ModElement(workspace, "Rollback", net.mcreator.element.ModElementType.ADVANCEMENT);
		File destination = new File(workspace.getFolderManager().getModElementPicturesCacheDir(),
				"Rollback.png");
		destination.getParentFile().mkdirs();
		Files.writeString(destination.toPath(), "old thumbnail");
		WorkspaceJournal journal = WorkspaceJournal.capture(workspace.getWorkspaceFolder().toPath());

		assertThrows(IOException.class,
				() -> manager.storeModElementPictureStrict(new GeneratableElement(element) {
					@Override public BufferedImage generateModElementPicture() {
						try {
							Files.writeString(destination.toPath(), "partial");
						} catch (IOException ignored) {
						}
						throw new IllegalStateException("renderer failed after partial write");
					}
				}));
		journal.restore();

		assertEquals("old thumbnail", Files.readString(destination.toPath()));
	}

	@Test void nullElementIsRejected() throws IOException {
		Workspace workspace = newWorkspace();

		assertThrows(IOException.class, () -> workspace.getModElementManager().storeModElementPictureStrict(null));
	}

	@Test void decodeFailureIsChecked() throws IOException {
		Workspace workspace = newWorkspace();
		ModElementManager manager = workspace.getModElementManager();
		ModElement element = new ModElement(workspace, "DecodeFailure", net.mcreator.element.ModElementType.ADVANCEMENT);
		File destination = new File(workspace.getFolderManager().getModElementPicturesCacheDir(),
				"DecodeFailure.png");

		// parent dir will be a file so createDirectories fails before any write
		File parent = destination.getParentFile();
		parent.getParentFile().mkdirs();
		Files.writeString(parent.toPath(), "blocker");

		assertThrows(IOException.class,
				() -> manager.storeModElementPictureStrict(new GeneratableElement(element) {
					@Override public BufferedImage generateModElementPicture() {
						return new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
					}
				}));
	}

	@Test void validPictureIsWrittenAndVerified() throws IOException {
		Workspace workspace = newWorkspace();
		ModElementManager manager = workspace.getModElementManager();
		ModElement element = new ModElement(workspace, "ValidPicture", net.mcreator.element.ModElementType.ADVANCEMENT);
		File destination = new File(workspace.getFolderManager().getModElementPicturesCacheDir(),
				"ValidPicture.png");

		manager.storeModElementPictureStrict(new GeneratableElement(element) {
			@Override public BufferedImage generateModElementPicture() {
				return new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
			}
		});

		assertTrue(destination.isFile());
	}

	private Workspace newWorkspace() throws IOException {
		if (net.mcreator.element.ModElementType.ADVANCEMENT == null) {
			if (net.mcreator.Launcher.version == null) {
				java.util.Properties properties = new java.util.Properties();
				properties.setProperty("mcreator", "2026.1");
				net.mcreator.Launcher.version = new net.mcreator.util.MCreatorVersionNumber(properties);
			}
			if (net.mcreator.preferences.PreferencesManager.PREFERENCES == null)
				net.mcreator.preferences.PreferencesManager.init();
			if (net.mcreator.plugin.PluginLoader.INSTANCE == null)
				net.mcreator.plugin.PluginLoader.initInstance();
			net.mcreator.init.L10N.initTranslations();
			net.mcreator.element.ModElementTypeLoader.loadModElements();
		}
		if (net.mcreator.Launcher.version == null) {
			java.util.Properties properties = new java.util.Properties();
			properties.setProperty("mcreator", "2026.1");
			net.mcreator.Launcher.version = new net.mcreator.util.MCreatorVersionNumber(properties);
		}
		if (net.mcreator.preferences.PreferencesManager.PREFERENCES == null)
			net.mcreator.preferences.PreferencesManager.init();
		if (net.mcreator.plugin.PluginLoader.INSTANCE == null)
			net.mcreator.plugin.PluginLoader.initInstance();
		WorkspaceSettings settings = new WorkspaceSettings("picture_test");
		settings.setCurrentGenerator("neoforge-1.21.1");
		Path root = Files.createDirectory(temporaryDirectory.resolve("ws-" + System.nanoTime()));
		workspace = Workspace.createWorkspace(root.resolve("picture_test.modstudio_ai").toFile(), settings);
		return workspace;
	}
}
