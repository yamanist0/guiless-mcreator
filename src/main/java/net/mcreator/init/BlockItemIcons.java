// block/item icons loader - moved from ui.init
// headless version using bufferedimage instead of imageicon

package net.mcreator.init;

import net.mcreator.plugin.PluginLoader;
import net.mcreator.util.FilenameUtilsPatched;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BlockItemIcons {

	private static final Map<String, BufferedImage> CACHE = new ConcurrentHashMap<>();

	public static void init() {
		ImageIO.setUseCache(false);
		Map<String, BufferedImage> tmp = PluginLoader.INSTANCE.getResources("datalists.icons", Pattern.compile(".*\\.png$"))
				.parallelStream().collect(Collectors.toMap(
						resource -> FilenameUtilsPatched.removeExtension(FilenameUtilsPatched.getName(resource)),
						resource -> {
							try {
								return ImageIO.read(Objects.requireNonNull(PluginLoader.INSTANCE.getResource(resource)));
							} catch (Exception e) {
								return new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
							}
						}));
		ImageIO.setUseCache(true);
		CACHE.putAll(tmp);
	}

	@Nullable
	public static BufferedImage getIconForItem(@Nullable String itemName) {
		if (itemName != null && CACHE.get(itemName) != null) {
			return CACHE.get(itemName);
		} else if (itemName != null && itemName.matches("[0-9]+")) {
			BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			g.setColor(new Color(Integer.parseInt(itemName)));
			g.fillRect(0, 0, 32, 32);
			g.dispose();
			CACHE.put(itemName, img);
			return img;
		}
		return null;
	}

	public static BufferedImage getIconFor(String itemName) {
		String mappedKey = TEXTURE_MAPPINGS.get(itemName);
		if (mappedKey != null)
			return getIconForItem(mappedKey);
		else
			return getIconForItem(itemName);
	}

	//@formatter:off
	private static final HashMap<String, String> TEXTURE_MAPPINGS = new HashMap<>() {{
		put("Pickaxe", 				"IRON_PICKAXE");
		put("Axe", 					"IRON_AXE");
		put("Sword", 				"IRON_SWORD");
		put("Spade", 				"IRON_SHOVEL");
		put("Hoe", 					"IRON_HOE");
		put("Shield",				"SHIELD");
		put("Shears", 				"SHEARS");
		put("Fishing rod",			"FISHING_ROD");
		put("NONE", 				"BARRIER");
		put("WOOD", 				"OAK_WOOD");
		put("STONE", 				"STONE#0");
		put("IRON", 				"IRON_INGOT");
		put("GOLD", 				"GOLD_INGOT");
		put("DIAMOND", 				"DIAMOND");
		put("NETHERITE", 			"NETHERITE_INGOT");
		put("pickaxe", 				"IRON_PICKAXE");
		put("axe", 					"IRON_AXE");
		put("shovel", 				"IRON_SHOVEL");
		put("hoe",					"IRON_HOE");
		put("Default basic block",	"STONE#0");
		put("Stairs", 				"STONE_STAIRS");
		put("Slab", 				"STONE_SLAB#3");
		put("Fence", 				"OAK_FENCE");
		put("Wall",					"COBBLESTONE_WALL#0");
		put("Leaves", 				"LEAVES#0");
		put("TrapDoor", 			"TRAPDOOR");
		put("Pane", 				"GLASS_PANE");
		put("Door",					"OAK_DOOR");
		put("FenceGate", 			"OAK_FENCE_GATE");
		put("EndRod",				"END_ROD");
		put("PressurePlate",		"STONE_PRESSURE_PLATE");
		put("Button", 				"STONE_BUTTON");
		put("FlowerPot",			"POTTED_DANDELION");
		put("Sign",					"SIGN");
		put("HangingSign",			"OAK_HANGING_SIGN_ITEM");
		put("WARM", 				"GRASS");
		put("DESERT", 				"SAND#0");
		put("COOL", 				"ICE");
		put("ICY", 					"SNOW");
		put("Normal world gen", 	"GRASS");
		put("Nether like gen", 		"NETHERRACK");
		put("End like gen", 		"END_STONE");
		put("Surface", 				"GRASS");
		put("Nether", 				"NETHERRACK");
		put("eat", 					"BREAD");
		put("drink",				"POTIONITEM");
		put("block",				"GRASS");
		put("bow",					"BOW");
		put("crossbow",				"CROSSBOW");
		put("none",					"BARRIER");
		put("spear",				"ARROW");
		put("Not specified", 		"BLANK");
	}};
	//@formatter:on

}
