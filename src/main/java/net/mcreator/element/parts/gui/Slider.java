package net.mcreator.element.parts.gui;

import net.mcreator.element.parts.procedure.Procedure;
import net.mcreator.minecraft.MinecraftImageGenerator;

import javax.annotation.Nonnull;
import java.awt.*;
import java.awt.image.ImageObserver;
import java.math.BigDecimal;

public class Slider extends SizedComponent {

	@Nonnull public String name;
	public double min;
	public double max;
	public double value;
	public double step;
	public final String prefix;
	public final String suffix;
	public final Procedure whenSliderMoves;

	public Slider(int x, int y, int width, int height, @Nonnull String name, double min, double max, double value,
			double step, String prefix, String suffix, Procedure whenSliderMoves) {
		super(x, y, width, height);
		this.name = name;
		this.min = min;
		this.max = max;
		this.value = value;
		this.step = step;
		this.prefix = prefix;
		this.suffix = suffix;
		this.whenSliderMoves = whenSliderMoves;
	}

	@Override public int getWeight() {
		return 15;
	}

	@Nonnull @Override public String getName() {
		return name;
	}

	@Override public void paintComponent(int cx, int cy, ImageObserver observer, Graphics2D g) {
		g.drawImage(MinecraftImageGenerator.generateSliderBackground(this.width, this.height), cx, cy, width, height,
				observer);

		int knobWidth = 8;
		double normalized = (max > min) ? (value - min) / (max - min) : 0.0;
		normalized = Math.clamp(normalized, 0.0, 1.0);
		int knobX = cx + (int) Math.round(normalized * (this.width - knobWidth));
		g.drawImage(MinecraftImageGenerator.generateButton(knobWidth, this.height), knobX, cy, knobWidth, height,
				observer);

		int decimals = BigDecimal.valueOf(step).stripTrailingZeros().scale();
		String fullText = prefix + String.format("%." + decimals + "f", value) + suffix;
		int tx = cx + (width - g.getFontMetrics().stringWidth(fullText)) / 2;
		g.setColor(new Color(0x373737));
		g.drawString(fullText, tx + 1, cy + 13 + 1);
		g.setColor(new Color(0xdddddd));
		g.drawString(fullText, tx, cy + 13);
	}

}