package net.mcreator.element.parts.gui;

import net.mcreator.element.parts.procedure.Procedure;
import net.mcreator.util.FilenameUtilsPatched;
import net.mcreator.util.image.ImageUtils;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.references.TextureReference;
import net.mcreator.workspace.resources.TextureType;

import javax.annotation.Nonnull;
import javax.swing.*;
import java.awt.*;
import java.awt.Image;
import java.awt.image.ImageObserver;

public class ImageButton extends GUIComponent {

	@Nonnull public String name;

	@Nonnull @TextureReference(TextureType.SCREEN) public String image;
	@TextureReference(TextureType.SCREEN) public String hoveredImage;

	public Procedure onClick, displayCondition;

	private transient Image imageIcon, hoveredImageIcon;

	public ImageButton(@Nonnull String name, int x, int y, @Nonnull String image, String hoveredImage,
			Procedure onClick, Procedure displayCondition) {
		super(x, y);
		this.name = name;
		this.image = image;
		this.hoveredImage = hoveredImage;
		this.onClick = onClick;
		this.displayCondition = displayCondition;
	}

	@Nonnull @Override public String getName() {
		return name;
	}

	public Image getImage(Workspace workspace) {
		if (imageIcon == null) {
			imageIcon = new ImageIcon(workspace.getFolderManager()
					.getTextureFile(FilenameUtilsPatched.removeExtension(image), TextureType.SCREEN)
					.getAbsolutePath()).getImage();
		}
		return imageIcon;
	}

	public Image getHoveredImage(Workspace workspace) {
		Image image = getImage(workspace);

		if (hoveredImageIcon == null) {
			if (hoveredImage != null && !hoveredImage.isEmpty()) {
				Image hovered = new ImageIcon(workspace.getFolderManager()
						.getTextureFile(FilenameUtilsPatched.removeExtension(hoveredImage), TextureType.SCREEN)
						.getAbsolutePath()).getImage();
				hoveredImageIcon = ImageUtils.checkIfSameSize(image, hovered) ? hovered : image;
			} else {
				hoveredImageIcon = image;
			}
		}
		return hoveredImageIcon;
	}

	@Override public void paintComponent(int cx, int cy, ImageObserver observer, Graphics2D g) {

	}

	@Override public int getWidth(Workspace workspace) {
		return getImage(workspace).getWidth(null);
	}

	@Override public int getHeight(Workspace workspace) {
		return getImage(workspace).getHeight(null);
	}

	@Override public int getWeight() {
		return 25;
	}

}
