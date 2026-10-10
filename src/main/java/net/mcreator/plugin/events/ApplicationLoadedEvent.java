package net.mcreator.plugin.events;

import net.mcreator.HeadlessApplication;
import net.mcreator.plugin.MCREvent;

public class ApplicationLoadedEvent extends MCREvent {

	private final HeadlessApplication mcreatorApplication;

	public ApplicationLoadedEvent(HeadlessApplication mcreatorApplication) {
		this.mcreatorApplication = mcreatorApplication;
	}

	public HeadlessApplication getMCreatorApplication() {
		return mcreatorApplication;
	}
}
