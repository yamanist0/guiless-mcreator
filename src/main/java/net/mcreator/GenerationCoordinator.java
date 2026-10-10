package net.mcreator;

import net.mcreator.element.GeneratableElement;
import net.mcreator.generator.Generator;
import net.mcreator.generator.GeneratorTemplate;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.elements.ModElement;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

final class GenerationCoordinator {

	private GenerationCoordinator() {
	}

	static void generateElementStrict(Workspace workspace, GeneratableElement element) throws IOException {
		Generator generator = workspace.getGenerator();
		Set<String> expected = paths(workspace,
				generator.getModElementGeneratorTemplatesList(element).stream().map(GeneratorTemplate::getFile).toList());
		Set<String> previous = paths(workspace, element.getModElement().getAssociatedFiles());
		Map<String, Long> writtenBefore = lastModified(workspace, expected);
		requireSuccess(generateElementWithStrictWrites(generator, element), "element generation");
		verifyTrackedFiles(workspace, expected, paths(workspace, element.getModElement().getAssociatedFiles()), previous,
				"element", writtenBefore);
	}

	static void generateBaseStrict(Workspace workspace) throws IOException {
		Generator generator = workspace.getGenerator();
		Set<String> expected = paths(workspace,
				generator.getModBaseGeneratorTemplatesList().stream().map(GeneratorTemplate::getFile).toList());
		Set<String> previous = metadataPaths(workspace.getMetadata("files"));
		Map<String, Long> writtenBefore = lastModified(workspace, expected);
		requireSuccess(generateBaseWithStrictWrites(generator), "base generation");
		verifyTrackedFiles(workspace, expected, metadataPaths(workspace.getMetadata("files")), previous, "base",
				writtenBefore);
	}

	static boolean generateElementWithStrictWrites(Generator generator, GeneratableElement element) throws IOException {
		return withStrictWrites(generator, "element generation", () -> generator.generateElement(element));
	}

	static boolean generateBaseWithStrictWrites(Generator generator) throws IOException {
		return withStrictWrites(generator, "base generation", generator::generateBase);
	}

	static void verifyElementStrict(Workspace workspace, GeneratableElement element, Set<String> previous)
			throws IOException {
		Generator generator = workspace.getGenerator();
		Set<String> expected = paths(workspace,
				generator.getModElementGeneratorTemplatesList(element).stream().map(GeneratorTemplate::getFile).toList());
		verifyTrackedFiles(workspace, expected, paths(workspace, element.getModElement().getAssociatedFiles()), previous,
				"element");
	}

	static void verifyBaseStrict(Workspace workspace, Set<String> previous) throws IOException {
		Generator generator = workspace.getGenerator();
		Set<String> expected = paths(workspace,
				generator.getModBaseGeneratorTemplatesList().stream().map(GeneratorTemplate::getFile).toList());
		verifyTrackedFiles(workspace, expected, metadataPaths(workspace.getMetadata("files")), previous, "base");
	}

	static Set<String> trackedElementPaths(Workspace workspace, GeneratableElement element) throws IOException {
		return paths(workspace, element.getModElement().getAssociatedFiles());
	}

	static Set<String> trackedBasePaths(Workspace workspace) throws IOException {
		return metadataPaths(workspace.getMetadata("files"));
	}

	static int regenerateAndLaunchTransactional(java.nio.file.Path workspaceRoot,
			List<GeneratableElement> elements, Actions actions) throws Exception {
		WorkspaceJournal journal = WorkspaceJournal.capture(workspaceRoot);
		try {
			return regenerateAndLaunch(elements, actions);
		} catch (Exception failure) {
			try {
				actions.abort();
			} catch (Exception abortFailure) {
				failure.addSuppressed(abortFailure);
			}
			try {
				journal.restore();
			} catch (Exception rollbackFailure) {
				failure.addSuppressed(rollbackFailure);
			}
			throw failure;
		}
	}

	static int regenerateAndLaunch(List<GeneratableElement> elements, Actions actions) throws Exception {
		int verified = 0;
		for (GeneratableElement element : elements) {
			requireSuccess(actions.generateElement(element), "element generation");
			actions.verifyElement(element);
			verified++;
		}
		requireSuccess(actions.generateBase(), "base generation");
		actions.verifyBase();
		actions.save();
		actions.launch();
		return verified;
	}

	private static <T> T withStrictWrites(Generator generator, String operation, Supplier<T> action)
			throws IOException {
		boolean previous = generator.isStrictFileWrites();
		generator.setStrictFileWrites(true);
		try {
			return action.get();
		} catch (net.mcreator.generator.io.GradleTrackingFileIO.StrictFileWriteException e) {
			throw new IOException(operation + " failed", e);
		} finally {
			generator.setStrictFileWrites(previous);
		}
	}

	static void requireSuccess(boolean generated, String operation) throws IOException {
		if (!generated)
			throw new IOException(operation + " failed");
	}

	private static void verifyTrackedFiles(Workspace workspace, Set<String> expected, Set<String> tracked,
			Set<String> previous, String kind) throws IOException {
		verifyTrackedFiles(workspace, expected, tracked, previous, kind, java.util.Map.of());
	}

	private static void verifyTrackedFiles(Workspace workspace, Set<String> expected, Set<String> tracked,
			Set<String> previous, String kind, Map<String, Long> writtenBefore) throws IOException {
		if (!tracked.equals(expected))
			throw new IOException(kind + " file metadata does not match generated outputs");
		for (String path : expected) {
			File file = new File(workspace.getWorkspaceFolder(), path.replace('/', File.separatorChar));
			if (!file.isFile())
				throw new IOException(kind + " output is missing: " + path);
			Long before = writtenBefore.get(path);
			if (before != null && file.lastModified() < before)
				throw new IOException(kind + " output was not freshly written: " + path);
		}
		for (String stale : previous) {
			if (!expected.contains(stale)) {
				File file = new File(workspace.getWorkspaceFolder(), stale.replace('/', File.separatorChar));
				if (file.exists())
					throw new IOException("stale " + kind + " output remains: " + stale);
			}
		}
	}

	private static Map<String, Long> lastModified(Workspace workspace, Set<String> expected) {
		Map<String, Long> timestamps = new LinkedHashMap<>();
		for (String path : expected) {
			File file = new File(workspace.getWorkspaceFolder(), path.replace('/', File.separatorChar));
			timestamps.put(path, file.lastModified());
		}
		return timestamps;
	}

	private static Set<String> paths(Workspace workspace, Collection<File> files) throws IOException {
		Set<String> paths = new LinkedHashSet<>();
		File root = workspace.getWorkspaceFolder().getCanonicalFile();
		for (File file : files) {
			File canonical = file.getCanonicalFile();
			if (!workspace.getFolderManager().isFileInWorkspace(canonical, true))
				throw new IOException("generated output is outside workspace: " + canonical);
			String path = root.toPath().relativize(canonical.toPath()).toString().replace(File.separatorChar, '/');
			if (!paths.add(path))
				throw new IOException("duplicate generated output: " + path);
		}
		return paths;
	}

	private static Set<String> metadataPaths(Object metadata) throws IOException {
		Set<String> paths = new LinkedHashSet<>();
		if (metadata == null)
			return paths;
		if (!(metadata instanceof List<?> entries))
			throw new IOException("file metadata is not a list");
		for (Object entry : entries) {
			String path = entry.toString().replace('\\', '/');
			if (path.isBlank() || path.startsWith("/") || path.contains("../") || !paths.add(path))
				throw new IOException("invalid file metadata entry: " + path);
		}
		return paths;
	}

	interface Actions {
		boolean generateElement(GeneratableElement element) throws Exception;

		void verifyElement(GeneratableElement element) throws Exception;

		boolean generateBase() throws Exception;

		void verifyBase() throws Exception;

		void save() throws Exception;

		void launch() throws Exception;

		default void abort() throws Exception {
		}
	}
}
