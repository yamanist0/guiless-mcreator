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

package net.mcreator.minecraft;

import net.mcreator.element.types.interfaces.IMCItemProvider;
import net.mcreator.generator.GeneratorWrapper;
import net.mcreator.generator.mapping.NameMapper;
import net.mcreator.io.ResourcePointer;
import net.mcreator.init.BlockItemIcons;
import net.mcreator.init.ImageMakerTexturesCache;
import net.mcreator.util.image.ImageUtils;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.elements.ModElement;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MCItem extends DataListEntry {

	private static final Logger LOG = LogManager.getLogger(MCItem.class);

	protected BufferedImage icon;

	private boolean hasSubtypes;

	private MCItem(String name) {
		super(name);
	}

	public MCItem(DataListEntry entry) {
		super(entry.getName());
		setReadableName(entry.getReadableName());
		setDescription(entry.getDescription());
		setType(entry.getType());
		setTexture(entry.getTexture());
		setRequiredAPIs(entry.getRequiredAPIs());
		setOther(entry.getOther());
		setIcon(BlockItemIcons.getIconForItem(entry.getTexture()));
	}

	public boolean hasNoSubtypes() {
		return !hasSubtypes;
	}

	public void setSubtypes(boolean hasSubtypes) {
		this.hasSubtypes = hasSubtypes;
	}

	public BufferedImage getIcon() {
		return icon;
	}

	protected void setIcon(BufferedImage icon) {
		if (icon == null) {
			this.icon = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		} else
			this.icon = icon;
	}

	public boolean isPOI() {
		if (getOther() instanceof Map<?, ?> otherMap) {
			return otherMap.containsKey("poi") && Boolean.parseBoolean(otherMap.get("poi").toString());
		}

		return false;
	}

	public static BufferedImage getBlockIconBasedOnName(Workspace workspace, String name) {
		if (name == null || name.isBlank())
			return new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);

		if (name.startsWith("TAG:"))
			return new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);

		if (name.startsWith(NameMapper.EXTERNAL_PREFIX))
			return new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);

		BufferedImage retval = null;
		try {
			if (name.startsWith(NameMapper.MCREATOR_PREFIX)) {
				String elementName = GeneratorWrapper.getElementPlainName(name);
				String suffix = StringUtils.substringAfterLast(name, ".");
				boolean hasGeneratableIcon = false;

				ModElement modElement = workspace.getModElementByName(elementName);

				if (modElement == null) {
					retval = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
					hasGeneratableIcon = true;
				}
				else if (modElement.getGeneratableElement() instanceof IMCItemProvider provider) {
					var providedIcon = provider.getIconForMCItem(workspace, suffix);
					if (providedIcon != null) {
						retval = ImageUtils.toBufferedImage(providedIcon.getImage());
						hasGeneratableIcon = true;
					}
				}

				File elementIconFile = new File(
						workspace.getFolderManager().getModElementPicturesCacheDir().getAbsolutePath() + "/"
								+ elementName + ".png");
				if (!hasGeneratableIcon && elementIconFile.isFile()) {
					retval = ImageIO.read(elementIconFile);
				}
			} else if (name.startsWith("POTION:")) {
				String potion = name.replace("POTION:", "");
				if (potion.startsWith(NameMapper.MCREATOR_PREFIX)) {
					File potionFile = new File(workspace.getFolderManager().getModElementPicturesCacheDir(),
							potion.replace(NameMapper.MCREATOR_PREFIX, "") + ".png");
					if (potionFile.isFile()) {
						retval = ImageIO.read(potionFile);
					} else {
						retval = ImageMakerTexturesCache.CACHE.get(
								new ResourcePointer("templates/textures/texturemaker/potion_bottle_overlay.png"));
					}
				} else if (DataListLoader.loadDataMap("potions").containsKey(potion)) {
					int color = Integer.parseInt(DataListLoader.loadDataMap("potions").get(potion).getTexture());
					retval = MinecraftImageGenerator.Preview.generatePotionIcon(new Color(color));
				} else {
					retval = MinecraftImageGenerator.Preview.generatePotionIcon(Color.BLACK);
				}
			} else {
				Map<String, DataListEntry> blocksitems = DataListLoader.loadDataMap("blocksitems");
				DataListEntry entry = blocksitems.get(name);
				if (entry != null) {
					retval = BlockItemIcons.getIconForItem(entry.getTexture());
				} else {
					retval = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
				}
			}

			if (retval != null && retval.getWidth() > -1 && retval.getHeight() > -1) {
				return ImageUtils.resizeAndCrop(retval, 32);
			}

		} catch (Exception e) {
			LOG.warn("Failed to load icon for item: {}", name, e);
		}

		return new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
	}

	public static final class Custom extends MCItem {

		public Custom(ModElement element, String fieldName, String type) {
			this(element, fieldName, type, null);
		}

		public Custom(ModElement element, String fieldName, String type, @Nullable String descriptor) {
			super(NameMapper.MCREATOR_PREFIX + element.getName() + (fieldName == null ? "" : ("." + fieldName)));

			if (descriptor != null) {
				setReadableName(
						element.getName() + " - " + element.getType().getReadableName() + " " + descriptor.toLowerCase(
								Locale.ENGLISH));
			} else {
				setReadableName(element.getName() + " - " + element.getType().getReadableName());
			}

			setIcon(getBlockIconBasedOnName(element.getWorkspace(), getName()));
			setType(type);
			setDescription(element.getType().getDescription());
		}

		@Override public boolean isSupportedInWorkspace(Workspace workspace) {
			return true;
		}
	}

	public static final class Tag extends MCItem {

		public Tag(@Nonnull Workspace workspace, String name) {
			super("TAG:" + name);
			setType("tag");
			icon = MCItem.getBlockIconBasedOnName(workspace, "TAG:" + name);
		}

		@Override public boolean isSupportedInWorkspace(Workspace workspace) {
			return true;
		}

	}

	public static final class Potion extends MCItem {
		public Potion(@Nonnull Workspace workspace, DataListEntry potion) {
			super("POTION:" + potion.getName());
			setType("potion");
			icon = MCItem.getBlockIconBasedOnName(workspace, "POTION:" + potion.getName());
		}

		@Override public boolean isSupportedInWorkspace(Workspace workspace) {
			return true;
		}
	}

	public interface ListProvider {
		List<MCItem> provide(Workspace workspace);
	}

}
