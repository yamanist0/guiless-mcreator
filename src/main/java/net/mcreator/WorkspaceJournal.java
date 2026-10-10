package net.mcreator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class WorkspaceJournal {

	private static final Set<Path> EXCLUDED_ROOTS = Set.of(Path.of(".modstudio_ai", "localHistory"));

	private final Path root;
	private final Map<Path, byte[]> files;
	private final Set<Path> directories;

	private WorkspaceJournal(Path root, Map<Path, byte[]> files, Set<Path> directories) {
		this.root = root;
		this.files = files;
		this.directories = directories;
	}

	static WorkspaceJournal capture(Path root) throws IOException {
		Path canonicalRoot = root.toRealPath();
		Map<Path, byte[]> files = new LinkedHashMap<>();
		Set<Path> directories = new java.util.LinkedHashSet<>();
		try (Stream<Path> paths = Files.walk(canonicalRoot)) {
			for (Path path : paths.toList()) {
				Path relative = canonicalRoot.relativize(path);
				if (isExcluded(relative)) continue;
				BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
				if (attributes.isDirectory())
					directories.add(relative);
				else if (attributes.isRegularFile())
					files.put(relative, Files.readAllBytes(path));
			}
		}
		return new WorkspaceJournal(canonicalRoot, files, directories);
	}

	void restore() throws IOException {
		if (!Files.exists(root))
			Files.createDirectories(root);
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				if (path.equals(root))
					continue;
				Path relative = root.relativize(path);
				if (isExcluded(relative)) continue;
				boolean typeConflict = files.containsKey(relative) && Files.isDirectory(path)
						|| directories.contains(relative) && !Files.isDirectory(path);
				if (typeConflict || !files.containsKey(relative) && !directories.contains(relative))
					deletePath(path);
			}
		}
		for (Path directory : directories)
			Files.createDirectories(root.resolve(directory));
		for (Map.Entry<Path, byte[]> file : files.entrySet()) {
			Path target = root.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
	}

	private static void deletePath(Path path) throws IOException {
		if (!Files.isDirectory(path)) {
			Files.deleteIfExists(path);
			return;
		}
		try (Stream<Path> children = Files.walk(path)) {
			for (Path child : children.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(child);
		}
	}

	private static boolean isExcluded(Path relative) {
		return EXCLUDED_ROOTS.stream().anyMatch(relative::startsWith);
	}
}
