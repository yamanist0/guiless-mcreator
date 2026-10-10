package net.mcreator.element.parts.gui;

import net.mcreator.element.parts.procedure.NumberProcedure;
import net.mcreator.element.parts.procedure.Procedure;
import net.mcreator.util.FilenameUtilsPatched;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.references.TextureReference;
import net.mcreator.workspace.resources.TextureType;

import javax.annotation.Nonnull;
import javax.swing.*;
import java.awt.*;
import java.awt.image.ImageObserver;

public class Sprite extends GUIComponent {

	@Nonnull @TextureReference(TextureType.SCREEN) public String sprite;

	public int spritesCount;

	public Procedure displayCondition;
	public NumberProcedure spriteIndex;

	public Sprite(int x, int y, @Nonnull String sprite, int spritesCount, Procedure displayCondition,
			NumberProcedure spriteIndex) {
		super(x, y);
		this.sprite = sprite;
		this.spritesCount = spritesCount;
		this.displayCondition = displayCondition;
		this.spriteIndex = spriteIndex;
	}

	public Sprite(int x, int y, String sprite, int spritesCount, Procedure displayCondition,
			NumberProcedure spriteIndex, AnchorPoint anchorPoint) {
		this(x, y, sprite, spritesCount, displayCondition, spriteIndex);
		this.anchorPoint = anchorPoint;
	}

	@Override public String getName() {
		return "sprite_" + FilenameUtilsPatched.removeExtension(sprite);
	}

	@Override public void paintComponent(int cx, int cy, ImageObserver observer, Graphics2D g) {

	}

	@SuppressWarnings("unused") public int getTextureWidth(Workspace workspace) {
		return getImage(workspace).getWidth(null);
	}

	@SuppressWarnings("unused") public int getTextureHeight(Workspace workspace) {
		return getImage(workspace).getHeight(null);
	}

	@Override public int getWidth(Workspace workspace) {
		int width = getImage(workspace).getWidth(null);
		int height = getImage(workspace).getHeight(null);

		return Math.max(1, width > height ? width / spritesCount : width);
	}

	@Override public int getHeight(Workspace workspace) {
		int width = getImage(workspace).getWidth(null);
		int height = getImage(workspace).getHeight(null);

		return Math.max(1, width <= height ? height / spritesCount : height);
	}

	public java.awt.Image getImage(Workspace workspace) {
		return new ImageIcon(workspace.getFolderManager()
				.getTextureFile(FilenameUtilsPatched.removeExtension(sprite), TextureType.SCREEN)
				.getAbsolutePath()).getImage();
	}

	@Override public int getWeight() {
		return 45;
	}
}
