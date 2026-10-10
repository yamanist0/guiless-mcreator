package net.mcreator;

import net.mcreator.gradle.GradleUtils;
import net.mcreator.java.ProjectJarManager;
import net.mcreator.workspace.Workspace;
import org.fife.rsta.ac.java.buildpath.SourceLocation;
import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.ProjectConnection;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class GradleSourcePreparation {

	private static final List<String> REQUIRED_CLASSES = List.of(
			"net.minecraft.world.level.portal.PortalShape",
			"net.minecraft.world.level.block.NetherPortalBlock",
			"net.minecraft.world.level.portal.PortalForcer"
	);

	// remember which workspaces we already tried to prepare in this process so a
	// toolchain that cannot produce sources does not re run the slow prep per element
	private static final Set<String> PREPARED = ConcurrentHashMap.newKeySet();

	private GradleSourcePreparation() {
	}

	static void ensureReady(Workspace workspace) throws Exception {
		if (!PREPARED.add(workspace.getWorkspaceFolder().getAbsolutePath()))
			return;
		ensureReady(new Actions() {
			@Override public boolean sourcesReady() {
				return hasRequiredSources(workspace);
			}

			@Override public void prepare() throws IOException {
				ProjectConnection connection = GradleUtils.getGradleProjectConnection(workspace);
				if (connection == null)
					throw new IOException("could not connect to Gradle for source preparation");
				// prefer the generator declared sync task so loom toolchains use gensources
				// fall back to the neogradle source tasks when none is declared
				String syncTask = workspace.getGeneratorConfiguration().getGradleTaskFor("sync_task");
				String[] tasks = (syncTask != null && !syncTask.isBlank()) ?
						syncTask.trim().split("\\s+") :
						new String[] { "createMinecraftArtifacts", "prepareClientRun" };
				BuildLauncher launcher = GradleUtils.getGradleTaskLauncher(workspace.getGeneratorConfiguration(), connection,
						tasks);
				// source prep only attaches decompiled game sources for the editor
				// some toolchains like classic forgegradle lack these tasks so treat a
				// failure here as best effort and let the later build resolve dependencies
				try {
					launcher.run();
				} catch (RuntimeException ex) {
					System.err.println("source preparation task failed, continuing without game source attachment: "
							+ ex.getMessage());
				}
				workspace.getGenerator().reloadGradleCaches();
			}
		});
	}

	static void ensureReady(Actions actions) throws Exception {
		if (actions.sourcesReady())
			return;
		actions.prepare();
		if (!actions.sourcesReady())
			// game source attachment is an editor convenience not a build requirement
			// keep going so toolchains without decompiled sources can still create and build
			System.err.println("game source attachments are unavailable after gradle preparation, continuing");
	}

	private static boolean hasRequiredSources(Workspace workspace) {
		ProjectJarManager jarManager = workspace.getGenerator().getProjectJarManager();
		if (jarManager == null)
			return false;
		for (String className : REQUIRED_CLASSES) {
			try {
				SourceLocation location = jarManager.getSourceLocForClass(className);
				if (location == null || !new File(location.getLocationAsString()).exists())
					return false;
			} catch (Exception ignored) {
				return false;
			}
		}
		return true;
	}

	interface Actions {
		boolean sourcesReady() throws Exception;

		void prepare() throws Exception;
	}
}
