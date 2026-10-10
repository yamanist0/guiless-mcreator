/*
 * MCreator (https://mcreator.net/)
 * Copyright (C) 2012-2020, Pylo
 * Copyright (C) 2020-2026, Pylo, opensource contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.mcreator.generator.io;

import net.mcreator.generator.IGeneratorProvider;
import net.mcreator.io.FileIO;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class GradleTrackingFileIO {

	public static void writeFile(@Nullable IGeneratorProvider generatorProvider, String code, File path) {
		track(generatorProvider, path);
		if (strict(generatorProvider)) {
			try {
				File parent = path.getAbsoluteFile().getParentFile();
				if (parent != null)
					Files.createDirectories(parent.toPath());
				Files.writeString(path.toPath(), code, StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new StrictFileWriteException(path, e);
			}
		} else {
			FileIO.writeStringToFile(code, path);
		}
	}

	public static final class StrictFileWriteException extends RuntimeException {
		private StrictFileWriteException(File path, IOException cause) {
			this("failed to write generated file: " + path, cause);
		}

		public StrictFileWriteException(String message, IOException cause) {
			super(message, cause);
		}
	}

	public static void deleteFile(@Nullable IGeneratorProvider generatorProvider, File path) {
		track(generatorProvider, path);
		if (strict(generatorProvider)) {
			try {
				Files.deleteIfExists(path.toPath());
			} catch (IOException e) {
				throw new StrictFileWriteException(path, e);
			}
		} else {
			path.delete();
		}
	}

	public static void emptyDirectory(@Nullable IGeneratorProvider generatorProvider, File path) {
		track(generatorProvider, path);
		if (strict(generatorProvider)) {
			try {
				deleteChildren(path);
			} catch (IOException e) {
				throw new StrictFileWriteException(path, e);
			}
		} else {
			FileIO.emptyDirectory(path);
		}
	}

	public static void copyFile(@Nullable IGeneratorProvider generatorProvider, File from, File to) {
		track(generatorProvider, from);
		track(generatorProvider, to);
		if (strict(generatorProvider)) {
			try {
				File parent = to.getAbsoluteFile().getParentFile();
				if (parent != null)
					Files.createDirectories(parent.toPath());
				Files.copy(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				throw new StrictFileWriteException(to, e);
			}
		} else {
			FileIO.copyFile(from, to);
		}
	}

	public static void writePNG(@Nullable IGeneratorProvider generatorProvider, RenderedImage image, File path) {
		track(generatorProvider, path);
		if (strict(generatorProvider)) {
			try {
				File parent = path.getAbsoluteFile().getParentFile();
				if (parent != null)
					Files.createDirectories(parent.toPath());
				if (!ImageIO.write(image, "png", path))
					throw new IOException("png encoder is unavailable");
			} catch (IOException e) {
				throw new StrictFileWriteException(path, e);
			}
		} else {
			FileIO.writeImageToPNGFile(image, path);
		}
	}

	private static boolean strict(@Nullable IGeneratorProvider generatorProvider) {
		return generatorProvider != null && generatorProvider.getGenerator() != null
				&& generatorProvider.getGenerator().isStrictFileWrites();
	}

	private static void track(@Nullable IGeneratorProvider generatorProvider, File path) {
		if (generatorProvider != null && generatorProvider.getGenerator() != null)
			generatorProvider.getGenerator().trackFile(path);
	}

	private static void deleteChildren(File directory) throws IOException {
		if (!directory.isDirectory())
			return;
		try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(directory.toPath())) {
			for (java.nio.file.Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
				if (!path.equals(directory.toPath()))
					Files.deleteIfExists(path);
			}
		}
	}

}
