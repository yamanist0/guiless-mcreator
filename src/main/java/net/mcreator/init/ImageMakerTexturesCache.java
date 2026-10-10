// image maker textures cache - moved from ui.init
// stores texture images using bufferedimage instead of imageicon

package net.mcreator.init;

import net.mcreator.io.ResourcePointer;
import net.mcreator.io.TemplatesLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ImageMakerTexturesCache {

	private static final Logger LOG = LogManager.getLogger("Texture Cache");

	public static final Map<ResourcePointer, BufferedImage> CACHE = new ConcurrentHashMap<>();

	public static void init() {
		List<ResourcePointer> templatesSorted = TemplatesLoader.loadTemplates("textures.texturemaker", "png");
		ImageIO.setUseCache(false);
		templatesSorted.forEach(resourcePointer -> {
			try {
				CACHE.put(resourcePointer, ImageIO.read(resourcePointer.getStream()));
			} catch (Exception e) {
				LOG.warn("Failed to load texture from templates: {}", resourcePointer.identifier);
			}
		});
		ImageIO.setUseCache(true);
	}

}
