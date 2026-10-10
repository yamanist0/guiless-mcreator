// blockly toolboxes loader - moved from ui.init
// loads blockly toolbox xml from plugins, no ui dependency

package net.mcreator.init;

import net.mcreator.io.FileIO;
import net.mcreator.plugin.PluginLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class BlocklyToolboxesLoader {

	private static final Logger LOG = LogManager.getLogger("Blockly Toolboxes loader");

	public static BlocklyToolboxesLoader INSTANCE;

	private final Map<String, String> TOOLBOXES = new HashMap<>();

	public static void init() {
		INSTANCE = new BlocklyToolboxesLoader();
	}

	public BlocklyToolboxesLoader() {
		LOG.debug("Loading Blockly toolboxes from plugins");

		Set<String> xmlFiles = PluginLoader.INSTANCE.getResources("blockly.toolboxes",
				Pattern.compile("^[^$].*\\.xml$"));
		for (String fileName : xmlFiles)
			TOOLBOXES.put(fileName, FileIO.readResourceToString(PluginLoader.INSTANCE, fileName));
	}

	public String getToolboxXML(String name) {
		return TOOLBOXES.get("blockly/toolboxes/toolbox_" + name + ".xml");
	}
}
