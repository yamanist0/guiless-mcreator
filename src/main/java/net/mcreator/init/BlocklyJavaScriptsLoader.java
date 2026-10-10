// blockly javascript files loader - moved from ui.init
// loads blockly js files from plugins, no ui dependency

package net.mcreator.init;

import net.mcreator.io.FileIO;
import net.mcreator.plugin.PluginLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class BlocklyJavaScriptsLoader {

	private static final Logger LOG = LogManager.getLogger("Blockly JavaScript Files loader");

	public static BlocklyJavaScriptsLoader INSTANCE;

	public static void init() {
		INSTANCE = new BlocklyJavaScriptsLoader();
	}

	private final List<String> SCRIPTS = new ArrayList<>();

	public BlocklyJavaScriptsLoader() {
		LOG.debug("Loading Blockly JavaScript files from plugins");

		Set<String> fileNames = PluginLoader.INSTANCE.getResources("blockly.js", Pattern.compile("^[^$].*\\.js$"));
		for (String fileName : fileNames)
			SCRIPTS.add(FileIO.readResourceToString(PluginLoader.INSTANCE, fileName));
	}

	public List<String> getScripts() {
		return SCRIPTS;
	}
}
