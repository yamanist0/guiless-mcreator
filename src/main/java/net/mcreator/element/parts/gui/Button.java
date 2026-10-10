package net.mcreator.element.parts.gui;

import net.mcreator.element.parts.procedure.Procedure;
import net.mcreator.minecraft.MinecraftImageGenerator;

import javax.annotation.Nonnull;
import java.awt.*;
import java.awt.image.ImageObserver;

public class Button extends SizedComponent {

	@Nonnull public String name;
	public final String text;
	public final boolean isUndecorated;
	public final Procedure onClick;
	public final Procedure displayCondition;

	public Button(@Nonnull String name, int x, int y, String text, int width, int height, boolean isUndecorated,
			Procedure onClick, Procedure displayCondition) {
		super(x, y, width, height);
		this.text = text;
		this.onClick = onClick;
		this.displayCondition = displayCondition;
		this.name = name;
		this.isUndecorated = isUndecorated;
	}

	@Override public int getWeight() {
		return 30;
	}

	@Override public String getName() {
		return name;
	}

	@Override public void paintComponent(int cx, int cy, ImageObserver observer, Graphics2D g) {
		int textwidth = (int) (g.getFont().getStringBounds(this.text, g.getFontRenderContext()).getWidth());
		int textheight = (int) (g.getFont().getStringBounds(this.text, g.getFontRenderContext()).getHeight()) - 4;

		if (isUndecorated) {
			g.drawString(this.text, cx, cy + textheight + 4);
		} else {
			g.drawImage(MinecraftImageGenerator.generateButton(this.width, this.height), cx, cy, this.width,
					this.height, observer);
			g.drawString(this.text, cx + (this.width / 2) - (textwidth / 2),
					cy + textheight + (this.height / 2) - (textheight / 2));
		}
	}

}