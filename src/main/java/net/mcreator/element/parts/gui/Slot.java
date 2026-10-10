package net.mcreator.element.parts.gui;

import net.mcreator.element.parts.procedure.LogicProcedure;
import net.mcreator.element.parts.procedure.Procedure;
import net.mcreator.minecraft.MinecraftImageGenerator;
import net.mcreator.util.image.ImageUtils;
import net.mcreator.workspace.Workspace;

import javax.swing.*;
import java.awt.*;
import java.awt.Image;
import java.awt.image.ImageObserver;

public abstract class Slot extends GUIComponent {

	public Color color;
	public int id;
	public LogicProcedure disablePickup;

	public boolean dropItemsWhenNotBound;

	public Procedure onSlotChanged;
	public Procedure onTakenFromSlot;
	public Procedure onStackTransfer;

	private static final Image itemSlot = MinecraftImageGenerator.generateItemSlot();

	@SuppressWarnings("unused") Slot() {
		super();
		this.dropItemsWhenNotBound = true;
	}

	public Slot(int id, int x, int y, Color color, LogicProcedure disablePickup, boolean dropItemsWhenNotBound,
			Procedure onSlotChanged, Procedure onTakenFromSlot, Procedure onStackTransfer) {
		super(x, y);
		this.color = color;
		this.id = id;
		this.disablePickup = disablePickup;
		this.dropItemsWhenNotBound = dropItemsWhenNotBound;
		this.onSlotChanged = onSlotChanged;
		this.onTakenFromSlot = onTakenFromSlot;
		this.onStackTransfer = onStackTransfer;
	}

	@Override public int getWeight() {
		return 50;
	}

	@Override public final int getWidth(Workspace workspace) {
		return 18;
	}

	@Override public final int getHeight(Workspace workspace) {
		return 18;
	}

	@Override public void paintComponent(int cx, int cy, ImageObserver observer, Graphics2D g) {
		g.setFont(g.getFont().deriveFont(5f));
		g.setColor(Color.gray.brighter().brighter().brighter());

		if (this.color == null)
			g.drawImage(itemSlot, cx, cy, 18, 18, observer);
		else
			g.drawImage(ImageUtils.colorize(itemSlot, this.color, true), cx, cy, 18, 18, observer);

		g.drawString(String.format("%02d", this.id), cx + 10, cy + 6);
	}

}
