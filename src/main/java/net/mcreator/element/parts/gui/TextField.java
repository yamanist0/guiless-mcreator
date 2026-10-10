package net.mcreator.element.parts.gui;

import net.mcreator.minecraft.MinecraftImageGenerator;

import javax.annotation.Nonnull;
import java.awt.*;
import java.awt.image.ImageObserver;

public class TextField extends SizedComponent {

	public String placeholder;

	@Nonnull public String name;

	public TextField(@Nonnull String name, int x, int y, int width, int height, String placeholder) {
		super(x, y, width, height);
		this.placeholder = placeholder;

		this.name = name;
	}

	@Override public @Nonnull String getName() {
		return name;
	}

	@Override public int getWeight() {
		return 0;
	}

	@Override public void paintComponent(int cx, int cy, ImageObserver observer, Graphics2D g) {
		g.drawImage(MinecraftImageGenerator.generateTextField(this.width, this.height), cx, cy, this.width, this.height,
				observer);
	}

}
