package net.mcreator.element.parts.gui;

import net.mcreator.element.parts.procedure.Procedure;
import net.mcreator.element.parts.procedure.StringProcedure;
import net.mcreator.util.StringUtils;
import net.mcreator.workspace.elements.VariableTypeLoader;

import javax.annotation.Nonnull;
import java.awt.*;
import java.awt.image.ImageObserver;

public class Tooltip extends SizedComponent {

	@Nonnull public final String name;

	public final StringProcedure text;
	public final Procedure displayCondition;

	public Tooltip(@Nonnull String name, int x, int y, int width, int height, StringProcedure text,
			Procedure displayCondition) {
		super(x, y, width, height);
		this.text = text;
		this.displayCondition = displayCondition;
		this.name = name;
	}

	@Nonnull @Override public String getName() {
		return name;
	}

	public String getRenderText() {
		if (text.getName() == null)
			return text.getFixedValue();
		else
			return text.getName();
	}

	@Override public boolean canChangeHeight() {
		return true;
	}

	@Override public void paintComponent(int cx, int cy, ImageObserver observer, Graphics2D g) {
		String renderText = getRenderText();
		if (text.getName() == null)
			renderText = StringUtils.abbreviateString(renderText, 10);

		g.setColor(new Color(0, 0, 0, 70));
		g.fillRect(cx, cy, width, height);

		g.setColor(new Color(230, 230, 230));
		g.setFont(g.getFont().deriveFont(5f));

		int textHeight = (int) (g.getFont().getStringBounds(renderText, g.getFontRenderContext()).getHeight());
		int textWidth = (int) (g.getFont().getStringBounds(renderText, g.getFontRenderContext()).getWidth());
		g.drawString(renderText, cx + 2, cy + textHeight + 1);

		if (text.getName() != null) {
			g.setColor(VariableTypeLoader.BuiltInTypes.STRING.getBlocklyColor());
			g.drawLine(cx + 2, cy + textHeight + 3, cx + 2 + textWidth, cy + textHeight + 3);
		}
	}

	@Override public int getWeight() {
		return -15;
	}

}
