package net.mcreator.element.parts.gui;

import net.mcreator.element.parts.procedure.Procedure;
import net.mcreator.element.parts.procedure.StringProcedure;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.elements.VariableTypeLoader;

import javax.annotation.Nonnull;
import java.awt.*;
import java.awt.image.ImageObserver;

public class Label extends GUIComponent {

	@Nonnull public String name;

	public StringProcedure text;
	public Color color;
	public boolean hasShadow;

	public Procedure displayCondition;

	public Label(@Nonnull String name, int x, int y, StringProcedure text, Color color, boolean hasShadow,
			Procedure displayCondition) {
		super(x, y);
		this.text = text;
		this.color = color;
		this.hasShadow = hasShadow;
		this.displayCondition = displayCondition;
		this.name = name;
	}

	public Label(String name, int x, int y, StringProcedure text, Color color, boolean hasShadow,
			Procedure displayCondition, AnchorPoint anchorPoint) {
		this(name, x, y, text, color, hasShadow, displayCondition);
		this.anchorPoint = anchorPoint;
	}

	@Nonnull @Override public String getName() {
		return name;
	}

	@Override public final int getWidth(Workspace workspace) {
		return getRenderText().length() * 6;
	}

	@Override public final int getHeight(Workspace workspace) {
		return 10;
	}

	@Override public int getWeight() {
		return 10;
	}

	@Override public boolean isSizeKnown() {
		return false;
	}

	public String getRenderText() {
		if (text.getName() == null)
			return text.getFixedValue();
		else
			return text.getName();
	}

	@Override public void paintComponent(int cx, int cy, ImageObserver observer, Graphics2D g) {
		int textheight = (int) (g.getFont().getStringBounds(this.getRenderText(), g.getFontRenderContext()).getHeight()) - 1;

		if (hasShadow) {
			g.setColor(this.color.darker());
			g.drawString(this.getRenderText(), cx + 1, cy + textheight + 1);
		}

		g.setColor(this.color);
		g.drawString(this.getRenderText(), cx, cy + textheight);

		if (text.getName() != null) {
			g.setColor(VariableTypeLoader.BuiltInTypes.STRING.getBlocklyColor());
			g.drawLine(cx, cy + getHeight(null), cx + this.getWidth(null), cy + getHeight(null));
		}
	}

}
