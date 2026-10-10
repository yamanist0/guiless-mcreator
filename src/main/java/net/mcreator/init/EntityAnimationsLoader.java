// entity animations loader - moved from ui.init
// loads entity animation data from plugins, no ui dependency

package net.mcreator.init;

import com.google.gson.Gson;
import net.mcreator.io.FileIO;
import net.mcreator.plugin.PluginLoader;
import org.apache.commons.io.FilenameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class EntityAnimationsLoader {

	private static final Logger LOG = LogManager.getLogger("Entity Animations Loader");

	private static final LinkedHashMap<String, String[]> entityAnimations = new LinkedHashMap<>();

	public static void init() {
		LOG.debug("Loading entity animations");

		Set<String> fileNames = PluginLoader.INSTANCE.getResources("templates.animations",
				Pattern.compile("^[^$].*\\.json$"));

		entityAnimations.put("No animation", new String[] {});

		final Gson gson = new Gson();

		for (String file : fileNames) {
			String[] animationCodes = gson.fromJson(FileIO.readResourceToString(PluginLoader.INSTANCE, file),
					String[].class);
			entityAnimations.put(FilenameUtils.removeExtension(file.replace("templates/animations/", "")),
					animationCodes);
		}
	}

	public static List<String> getAnimationIDs() {
		return new ArrayList<>(entityAnimations.keySet());
	}

	public static String[] getAnimationCodesFromID(String animationID) {
		return entityAnimations.get(animationID);
	}

}
