package net.mcreator.element.parts.gui;

import net.mcreator.element.parts.procedure.Procedure;
import net.mcreator.util.FilenameUtilsPatched;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.references.TextureReference;
import net.mcreator.workspace.resources.TextureType;

import javax.annotation.Nonnull;
import javax.swing.*;
import java.awt.*;
import java.awt.image.ImageObserver;

public class Image extends GUIComponent {

	@Nonnull @TextureReference(TextureType.SCREEN) public String image;
	public boolean use1Xscale;

	public Procedure displayCondition;

	public Image(int x, int y, @Nonnull String image, boolean use1Xscale, Procedure displayCondition) {
		super(x, y);
		this.image = image;
		this.use1Xscale = use1Xscale;
		this.displayCondition = displayCondition;
	}

	public Image(int x, int y, String image, boolean use1Xscale, Procedure displayCondition, AnchorPoint anchorPoint) {
		this(x, y, image, use1Xscale, displayCondition);
		this.anchorPoint = anchorPoint;
	}

	@Override public String getName() {
		return "image_" + FilenameUtilsPatched.removeExtension(image);
	}

	@Override public int getWidth(Workspace workspace) {
		if (use1Xscale)
			return getImage(workspace).getWidth(null) / 2;
		else
			return getImage(workspace).getWidth(null);
	}

	@Override public int getHeight(Workspace workspace) {
		if (use1Xscale)
			return getImage(workspace).getHeight(null) / 2;
		else
			return getImage(workspace).getHeight(null);
	}

	public java.awt.Image getImage(Workspace workspace) {
		return new ImageIcon(workspace.getFolderManager()
				.getTextureFile(FilenameUtilsPatched.removeExtension(image), TextureType.SCREEN)
				.getAbsolutePath()).getImage();
	}

	@Override public int getWeight() {
		return 40;
	}

	@Override public void paintComponent(int cx, int cy, ImageObserver observer, Graphics2D g) {

	}

}
