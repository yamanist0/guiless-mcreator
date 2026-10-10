// armor maker textures cache - moved from ui.init
// stores armor textures using bufferedimage instead of imageicon

package net.mcreator.init;

import net.mcreator.io.ResourcePointer;
import net.mcreator.io.TemplatesLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ArmorMakerTexturesCache {
	private static final Logger LOG = LogManager.getLogger("Armor Texture Cache");

	private static final Map<String, BufferedImage> CACHE = new ConcurrentHashMap<>();

	private static String[] TEMPLATE_NAMES = new String[0];

	public static void init() {
		List<ResourcePointer> templatesSorted = TemplatesLoader.loadTemplates("textures.armormaker", "png");
		ImageIO.setUseCache(false);
		templatesSorted.forEach(resourcePointer -> {
			try {
				CACHE.put(resourcePointer.toString(), ImageIO.read(resourcePointer.getStream()));
			} catch (Exception e) {
				LOG.warn("Failed to load armor texture from template: {}", resourcePointer.identifier);
			}
		});
		ImageIO.setUseCache(true);

		Set<String> templateNamesSet = new HashSet<>();

		CACHE.keySet().forEach(imageName -> {
			for (ArmorTexturePart part : ArmorTexturePart.values()) {
				if (imageName.endsWith(part.getSuffix())) {
					templateNamesSet.add(imageName.substring(0, imageName.length() - part.getSuffix().length()));
				}
			}
		});

		TEMPLATE_NAMES = templateNamesSet.toArray(new String[0]);
	}

	public static BufferedImage getImage(@Nullable String templateName, ArmorTexturePart part) {
		if (templateName != null && CACHE.get(templateName + part.getSuffix()) != null)
			return CACHE.get(templateName + part.getSuffix());
		else
			return CACHE.get("Standard" + part.getSuffix());
	}

	public static String[] getTemplateNames() {
		return TEMPLATE_NAMES;
	}

	public enum ArmorTexturePart {

		LAYER1("_layer_1"), LAYER2("_layer_2"), HELMET("_helmet"), BODY("_body"), LEGGINGS("_leggings"), BOOTS(
				"_boots");

		private final String suffix;

		ArmorTexturePart(String suffix) {
			this.suffix = suffix;
		}

		public String getSuffix() {
			return suffix;
		}

	}

}
