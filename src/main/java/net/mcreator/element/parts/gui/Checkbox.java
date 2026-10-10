package net.mcreator.element.parts.gui;

import net.mcreator.element.parts.procedure.Procedure;
import net.mcreator.minecraft.MinecraftImageGenerator;
import net.mcreator.workspace.Workspace;

import javax.annotation.Nonnull;
import java.awt.*;
import java.awt.image.ImageObserver;

public class Checkbox extends GUIComponent {

	@Nonnull public final String name;
	public final String text;
	public final Procedure isCheckedProcedure;

	public Checkbox(@Nonnull String name, int x, int y, String text, Procedure isCheckedProcedure) {
		super(x, y);
		this.text = text;
		this.isCheckedProcedure = isCheckedProcedure;

		this.name = name;
	}

	@Nonnull @Override public String getName() {
		return name;
	}

	@Override public void paintComponent(int cx, int cy, ImageObserver observer, Graphics2D g) {
		g.drawImage(MinecraftImageGenerator.generateCheckbox(), cx, cy, 20, 20, observer);
		g.setColor(new Color(0x373737));
		g.drawString(this.text, cx + 24 + 1, cy + 13 + 1);
		g.setColor(new Color(0xdddddd));
		g.drawString(this.text, cx + 24, cy + 13);
	}

	@Override public int getWidth(Workspace workspace) {
		return 20;
	}

	@Override public int getHeight(Workspace workspace) {
		return 20;
	}

	@Override public int getWeight() {
		return 20;
	}
}
