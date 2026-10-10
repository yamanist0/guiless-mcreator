package net.mcreator.workspace;

import net.mcreator.element.GeneratableElement;
import net.mcreator.io.zip.ZipIO;
import net.mcreator.init.L10N;
import net.mcreator.workspace.elements.ModElement;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class ShareableZIPManager {

	private static final Logger LOG = LogManager.getLogger("Shareable ZIP manager");

	private static final Set<String> EXCLUDES = new HashSet<>() {{
		add(".eclipse/");
		add(".gradle/");
		add(".modstudio_ai/");
		add("build/");
		add("gradle/");
		add("#build.gradle");
		add("#gradlew");
		add("#gradlew.bat");
		add("#modstudio_ai.gradle");
		add("#.classpath");
		add("#.project");
		add(".idea/");
		add(".settings/");
		add(".git/");
	}};

	public static void excludeWhenZipping(String fileOrFolder) {
		EXCLUDES.add(fileOrFolder);
	}

	@Nullable public static ImportResult importZIP(File file, File workspaceDir) {
		try {
			ZipIO.unzip(file.getAbsolutePath(), workspaceDir.getAbsolutePath());

			File workspaceFile = WorkspaceUtils.getWorkspaceFileForWorkspaceFolder(workspaceDir);
			if (workspaceFile != null) {
				try (Workspace workspace = Workspace.readFromFS(workspaceFile, null)) {
					for (ModElement mod : workspace.getModElements()) {
						ModElement check = workspace.getModElementByName(mod.getName());
						if (check != null && check.getType() == mod.getType()) {
							GeneratableElement generatableElement = mod.getGeneratableElement();
							if (generatableElement != null) {
								workspace.getModElementManager().storeModElementPicture(generatableElement);
								generatableElement.getModElement().reinit(workspace);
							}
						} else {
							LOG.debug("Skipping preloading of mod element {} as it was converted to a different type",
									mod.getName());
						}
					}
					workspace.markDirty();
					return new ImportResult(workspaceFile, workspace.isRegenerateRequired());
				} catch (UnsupportedGeneratorException | MissingGeneratorFeaturesException e) {
					return null;
				} catch (Exception e) {
					LOG.error("Failed to import workspace", e);
					return null;
				}
			}
		} catch (Exception e) {
			LOG.error("Failed to import workspace", e);
		}
		return null;
	}

	public static void exportZIP(String title, File file, Workspace workspace, boolean excludeRunDir) {
		try {
			Set<String> excludes = new HashSet<>(EXCLUDES);
			if (excludeRunDir)
				excludes.addAll(Set.of("run/", "runs/"));

			ZipIO.zipDir(workspace.getWorkspaceFolder().getAbsolutePath(), file.getAbsolutePath(),
					excludes.toArray(new String[0]));

			workspace.getHistoryManager().importantCheckpoint(() -> false, "export_workspace");
		} catch (IOException e) {
			LOG.error("Failed to export workspace", e);
		}
	}

	public record ImportResult(File file, boolean regenerateRequired) {}

}
