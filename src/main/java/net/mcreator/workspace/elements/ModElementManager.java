/*
 * MCreator (https://mcreator.net/)
 * Copyright (C) 2020 Pylo and contributors
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

package net.mcreator.workspace.elements;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import net.mcreator.element.GeneratableElement;
import net.mcreator.element.ModElementType;
import net.mcreator.element.parts.procedure.RetvalProcedure;
import net.mcreator.element.types.CustomElement;
import net.mcreator.element.util.GEValidator;
import net.mcreator.generator.GeneratorTemplate;
import net.mcreator.generator.TagsUtils;
import net.mcreator.io.FileIO;
import net.mcreator.workspace.Workspace;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.swing.ImageIcon;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * ModElementManager is not thread safe
 */
@NotThreadSafe public final class ModElementManager {

	private static final Logger LOG = LogManager.getLogger("ModElementManager");

	private final Gson gson;

	private final Map<ModElement, GeneratableElement> cache = new ConcurrentHashMap<>();

	@Nonnull private final Workspace workspace;

	private final Stack<ModElement> modElementsInConversion = new Stack<>();

	public ModElementManager(@Nonnull Workspace workspace) {
		this.workspace = workspace;

		GsonBuilder gsonBuilder = new GsonBuilder().registerTypeHierarchyAdapter(GeneratableElement.class,
						new GeneratableElement.GSONAdapter(this.workspace)).disableHtmlEscaping().setPrettyPrinting()
				.setStrictness(Strictness.LENIENT);
		RetvalProcedure.GSON_ADAPTERS.forEach(gsonBuilder::registerTypeAdapter);

		this.gson = gsonBuilder.create();
	}

	public ModElement getLastElementInConversion() {
		return modElementsInConversion.peek();
	}

	/**
	 * Also stores to cache. It is thread safe.
	 *
	 * @param element GeneratableElement to convert to store
	 */
	public void storeModElementStrict(GeneratableElement element) throws IOException {
		if (element == null)
			throw new IOException("cannot store a null element");

		String json = generatableElementToJSON(element);
		File destination = new File(workspace.getFolderManager().getModElementsDir(),
				element.getModElement().getName() + ".mod.json");
		File parent = destination.getAbsoluteFile().getParentFile();
		if (parent != null)
			java.nio.file.Files.createDirectories(parent.toPath());
		java.nio.file.Files.writeString(destination.toPath(), json, java.nio.charset.StandardCharsets.UTF_8);
		if (!java.nio.file.Files.readString(destination.toPath(), java.nio.charset.StandardCharsets.UTF_8).equals(json))
			throw new IOException("element data verification failed");
		cache.put(element.getModElement(), element);
	}

	public void storeModElement(GeneratableElement element) {
		try {
			storeModElementStrict(element);
		} catch (IOException e) {
			LOG.error("Failed to store mod element", e);
		}
	}

	public void removeModElement(ModElement element) {
		cache.remove(element);

		// first we ask generator to remove all related files
		if (element.getType() != ModElementType.UNKNOWN) {
			GeneratableElement generatableElement = element.getGeneratableElement();
			if (generatableElement != null && workspace.getGenerator() != null)
				workspace.getGenerator().removeElementFilesAndWorkspaceLinks(generatableElement);
			else
				LOG.warn("Failed to remove element files for element {}", element);
		}

		// remove any potential references to this mod element in tags
		TagsUtils.removeTagEntriesReferencingModElement(workspace, element);

		// after we don't need the definition anymore, remove actual files
		new File(workspace.getFolderManager().getModElementsDir(), element.getName() + ".mod.json").delete();
		new File(workspace.getFolderManager().getModElementPicturesCacheDir(), element.getName() + ".png").delete();
	}

	GeneratableElement loadGeneratableElement(ModElement element) {
		// To prevent circular reference (and thus stack overflow), we return Unknown GE if we are loading the
		// mod element that is being converted as otherwise this will try to start the conversion again
		if (modElementsInConversion.contains(element))
			return new GeneratableElement.Unknown(element);

		GeneratableElement cachedGeneratableElement = cache.get(element);
		if (cachedGeneratableElement != null) {
			if (cachedGeneratableElement.getModElement() != element) {
				try {
					throw new IllegalStateException(
							"Cache element: " + cachedGeneratableElement.getModElement().getName() + ", type: "
									+ cachedGeneratableElement.getModElement().getType() + ", queried element: "
									+ element.getName() + ", type: " + element.getType());
				} catch (IllegalStateException e) {
					LOG.error("Cache contains mod element with same name but different object. This should not happen!",
							e);
				}
			}
			return cachedGeneratableElement;
		}

		File genFile = new File(workspace.getFolderManager().getModElementsDir(), element.getName() + ".mod.json");

		if (element.getType() == ModElementType.CODE && !genFile.isFile())
			return new CustomElement(element);

		if (!genFile.isFile())
			return null;

		String importJSON = FileIO.readFileToString(genFile);

		GeneratableElement generatableElement = fromJSONtoGeneratableElementOrNull(importJSON, element);
		if (generatableElement != null && element.getType() != ModElementType.UNKNOWN) {

			// Store the mod element in case the conversion was applied
			if (generatableElement.wasConversionApplied())
				storeModElement(generatableElement);

			// Add it to the cache
			cache.put(element, generatableElement);
		}

		return generatableElement;
	}

	public String generatableElementToJSON(GeneratableElement element) {
		return gson.toJson(element);
	}

	@Nullable public GeneratableElement fromJSONtoGeneratableElementOrNull(String json, ModElement modElement) {
		try {
			return fromJSONtoGeneratableElement(json, modElement,
					message -> LOG.debug("GE validation notice: {}", message));
		} catch (Exception e) {
			LOG.warn("Failed to load generatable element {} from JSON. This can lead to errors further down the road!",
					modElement.getName(), e);
			return null;
		}
	}

	@Nonnull
	public GeneratableElement fromJSONtoGeneratableElement(String json, ModElement modElement,
			@Nullable Consumer<String> validationLog) throws IOException, GEValidator.ValidationException {
		this.modElementsInConversion.push(modElement);

		try {
			GeneratableElement retval = gson.fromJson(json, GeneratableElement.class);
			GEValidator.validateAndTryToCorrect(retval, validationLog);
			return retval;
		} catch (GEValidator.ValidationException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Failed to parse and load JSON of:" + modElement.getName(), e);
		} finally {
			this.modElementsInConversion.pop();
		}
	}

	public boolean requiresElementGradleBuild(GeneratableElement generatableElement) {
		List<GeneratorTemplate> templates = new ArrayList<>(workspace.getGenerator()
				.getGlobalTemplatesListForModElementType(generatableElement.getModElement().getType(),
						new AtomicInteger()));

		templates.addAll(workspace.getGenerator().getModElementGeneratorTemplatesList(generatableElement));

		for (GeneratorTemplate template : templates) {
			String writer = (String) template.getTemplateDefinition().get("writer");
			if (writer == null || writer.equals("java"))
				return true;
		}

		return false;
	}

	/**
	 * This method should be called after the mod element is generated by Generator
	 * So potential metadata fields of ModElement are properly loaded.
	 *
	 * @param element GeneratableElement to generate the picture for
	 */
	public void storeModElementPictureStrict(GeneratableElement element) throws IOException {
		if (element == null)
			throw new IOException("cannot store a picture for a null element");
		File destination = new File(workspace.getFolderManager().getModElementPicturesCacheDir(),
				element.getModElement().getName() + ".png");
		try {
			BufferedImage image = element.generateModElementPicture();
			if (image == null) {
				java.nio.file.Files.deleteIfExists(destination.toPath());
				return;
			}
			File parent = destination.getAbsoluteFile().getParentFile();
			if (parent != null)
				java.nio.file.Files.createDirectories(parent.toPath());
			if (!javax.imageio.ImageIO.write(image, "png", destination))
				throw new IOException("element picture encoder is unavailable");
			try (java.io.InputStream input = java.nio.file.Files.newInputStream(destination.toPath())) {
				if (javax.imageio.ImageIO.read(input) == null)
					throw new IOException("element picture verification failed");
			}
			image.flush();
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("element picture generation failed", e);
		}
	}

	public void storeModElementPicture(GeneratableElement element) {
		try {
			storeModElementPictureStrict(element);
		} catch (IOException e) {
			LOG.warn("Failed to generate mod element picture for {}", element.getModElement().getName(), e);
		}
	}

	public static ImageIcon getModElementIcon(ModElement element) {
		return element.getElementIcon();
	}

	/**
	 * Invalidates the generatable element cache
	 */
	public void invalidateCache() {
		cache.clear();
	}

}
