// headless application - replaces the old ui-based mcreatorapplication
// handles plugin loading, generator loading, and workspace management without any gui

package net.mcreator;

import net.mcreator.blockly.data.BlocklyLoader;
import net.mcreator.element.ModElementTypeLoader;
import net.mcreator.generator.Generator;
import net.mcreator.generator.GeneratorConfiguration;
import net.mcreator.init.*;
import net.mcreator.io.net.analytics.DeviceInfo;
import net.mcreator.io.net.analytics.GoogleAnalytics;
import net.mcreator.io.net.api.IWebAPI;
import net.mcreator.io.net.api.MCreatorNetWebAPI;
import net.mcreator.minecraft.DataListLoader;
import net.mcreator.plugin.MCREvent;
import net.mcreator.plugin.PluginLoader;
import net.mcreator.plugin.events.ApplicationLoadedEvent;
import net.mcreator.plugin.events.PreGeneratorsLoadingEvent;
import net.mcreator.plugin.modapis.ModAPIManager;
import net.mcreator.preferences.PreferencesManager;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.elements.VariableTypeLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

public final class HeadlessApplication {

	private static final Logger LOG = LogManager.getLogger("Application");

	public static final IWebAPI WEB_API = new MCreatorNetWebAPI();
	public static final String SERVER_DOMAIN = "https://mcreator.net";
	public static boolean isInternet = true;

	private static boolean applicationStarted = false;

	private DeviceInfo deviceInfo;
	private GoogleAnalytics analytics;

	private final List<Workspace> openWorkspaces = new ArrayList<>();

	private HeadlessApplication(List<String> launchArguments) {
		LOG.info("Starting headless MCreator backend");

		PluginLoader.initInstance();

		MCREvent.event(new ApplicationLoadedEvent(this));

		PreferencesManager.initNonCore();

		L10N.initTranslations();

		// load datalists and icons
		BlockItemIcons.init();
		DataListLoader.preloadCache();

		// load templates for image makers
		ImageMakerTexturesCache.init();
		ArmorMakerTexturesCache.init();

		// load apis defined by plugins
		ModAPIManager.initAPIs();

		// load variable elements
		VariableTypeLoader.loadVariableTypes();

		// load special files for blockly
		BlocklyJavaScriptsLoader.init();
		BlocklyToolboxesLoader.init();

		// load blockly blocks
		BlocklyLoader.init();

		// load entity animations
		EntityAnimationsLoader.init();

		// register mod element types
		ModElementTypeLoader.loadModElements();

		// load generators
		MCREvent.event(new PreGeneratorsLoadingEvent(this));

		Set<String> fileNamesUnordered = PluginLoader.INSTANCE.getResources(Pattern.compile("generator\\.yaml"));
		List<String> fileNames = new ArrayList<>(fileNamesUnordered);
		Collections.sort(fileNames);
		for (String generator : fileNames) {
			LOG.info("Loading generator: {}", generator);
			generator = generator.replace("/generator.yaml", "");
			try {
				Generator.GENERATOR_CACHE.put(generator, new GeneratorConfiguration(generator));
			} catch (Exception e) {
				LOG.error("Failed to load generator: {}", generator, e);
			}
		}

		// init analytics
		deviceInfo = new DeviceInfo();
		isInternet = false;
		analytics = new GoogleAnalytics(deviceInfo);

		LOG.info("Headless application loader finished");

		// if workspace file was passed as argument, open it
		if (!launchArguments.isEmpty()) {
			String lastArg = launchArguments.getLast();
			if (lastArg.length() >= 2 && lastArg.charAt(0) == '"' && lastArg.charAt(lastArg.length() - 1) == '"')
				lastArg = lastArg.substring(1, lastArg.length() - 1);
			File passedFile = new File(lastArg);
			if (passedFile.isFile() && passedFile.getName().endsWith(".modstudio_ai")) {
				openWorkspace(passedFile);
			}
		}
	}

	public Workspace openWorkspace(File workspaceFile) {
		try {
			Workspace workspace = Workspace.readFromFS(workspaceFile, null);
			openWorkspaces.add(workspace);
			LOG.info("Opened workspace: {}", workspaceFile);
			return workspace;
		} catch (Exception e) {
			LOG.error("Failed to open workspace: {}", workspaceFile, e);
			return null;
		}
	}

	public void closeWorkspace(Workspace workspace) {
		try {
			workspace.close();
			openWorkspaces.remove(workspace);
			LOG.info("Closed workspace: {}", workspace);
		} catch (Exception e) {
			LOG.error("Failed to close workspace", e);
		}
	}

	public GoogleAnalytics getAnalytics() {
		return analytics;
	}

	public DeviceInfo getDeviceInfo() {
		return deviceInfo;
	}

	public List<Workspace> getOpenWorkspaces() {
		return openWorkspaces;
	}

	public void closeApplication() {
		LOG.debug("Closing application");

		for (Workspace workspace : new ArrayList<>(openWorkspaces)) {
			closeWorkspace(workspace);
		}

		PreferencesManager.savePreferences();

		try {
			PluginLoader.INSTANCE.close();
		} catch (IOException e) {
			LOG.warn("Failed to close plugin loader", e);
		}

		LOG.debug("Exiting MCreator");
		System.exit(0);
	}

	public static HeadlessApplication createApplication(List<String> arguments) {
		if (!applicationStarted) {
			applicationStarted = true;
			return new HeadlessApplication(arguments);
		}
		return null;
	}

}
