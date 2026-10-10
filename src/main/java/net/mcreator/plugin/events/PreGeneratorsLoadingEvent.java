package net.mcreator.plugin.events;

import net.mcreator.HeadlessApplication;

public class PreGeneratorsLoadingEvent extends ApplicationLoadedEvent {

	public PreGeneratorsLoadingEvent(HeadlessApplication mcreatorApplication) {
		super(mcreatorApplication);
	}
}