package net.mcreator.element;

import net.mcreator.element.types.*;
import net.mcreator.element.types.bedrock.*;
import net.mcreator.generator.GeneratorFlavor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static net.mcreator.generator.GeneratorFlavor.BaseLanguage.JAVA;
import static net.mcreator.generator.GeneratorFlavor.GamePlatform.BEDROCKEDITION;
import static net.mcreator.generator.GeneratorFlavor.GamePlatform.JAVAEDITION;

public class ModElementTypeLoader {

	private static final List<ModElementType<?>> REGISTRY = new ArrayList<>();

	public static void loadModElements() {
		//@formatter:off
		ModElementType.ADVANCEMENT = register(new ModElementType<>("achievement", 'h', Achievement.class)).coveredOn(GeneratorFlavor.gamePlatform(JAVAEDITION));
		ModElementType.ARMOR = register(new ModElementType<>("armor", 'a', Armor.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.ARMORTRIM = register(new ModElementType<>("armortrim", null, ArmorTrim.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.ATTRIBUTE = register(new ModElementType<>("attribute", null, Attribute.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.BANNERPATTERN = register(new ModElementType<>("bannerpattern", null, BannerPattern.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.BIOME = register(new ModElementType<>("biome", 'o', Biome.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.BLOCK = register(new ModElementType<>("block", 'b', Block.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.COMMAND = register(new ModElementType<>("command", 'c', Command.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.DAMAGETYPE = register(new ModElementType<>("damagetype", null, DamageType.class)).coveredOn(GeneratorFlavor.gamePlatform(JAVAEDITION));
		ModElementType.DIMENSION = register(new ModElementType<>("dimension", 'd', Dimension.class)).coveredOn(GeneratorFlavor.gamePlatform(JAVAEDITION));
		ModElementType.CODE = register(new ModElementType<>("code", null, CustomElement.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.ENCHANTMENT = register(new ModElementType<>("enchantment", 'm', Enchantment.class)).coveredOn(GeneratorFlavor.gamePlatform(JAVAEDITION));
		ModElementType.FEATURE = register(new ModElementType<>("feature", 'f', Feature.class)).coveredOn(GeneratorFlavor.gamePlatform(JAVAEDITION));
		ModElementType.FLUID = register(new ModElementType<>("fluid", null, Fluid.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.FUNCTION = register(new ModElementType<>("function", 'u', Function.class));
		ModElementType.GAMERULE = register(new ModElementType<>("gamerule", null, GameRule.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.GUI = register(new ModElementType<>("gui", 'g', GUI.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.ITEM = register(new ModElementType<>("item", 'i', Item.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.ITEMEXTENSION = register(new ModElementType<>("itemextension", null, ItemExtension.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.KEYBIND = register(new ModElementType<>("keybind", 'k', KeyBinding.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.LIVINGENTITY = register(new ModElementType<>("livingentity", 'e', LivingEntity.class)).coveredOn(GeneratorFlavor.gamePlatform(JAVAEDITION));
		ModElementType.LOOTTABLE = register(new ModElementType<>("loottable", 'l', LootTable.class));
		ModElementType.OVERLAY = register(new ModElementType<>("overlay", 'v', Overlay.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.PAINTING = register(new ModElementType<>("painting", null, Painting.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.PARTICLE = register(new ModElementType<>("particle", 'y', Particle.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.PLANT = register(new ModElementType<>("plant", 'n', Plant.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.POTION = register(new ModElementType<>("potion", 'z', Potion.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.POTIONEFFECT = register(new ModElementType<>("potioneffect", null, PotionEffect.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.PROCEDURE = register(new ModElementType<>("procedure", 'p', Procedure.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.PROJECTILE = register(new ModElementType<>("projectile", 'q', Projectile.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.RECIPE = register(new ModElementType<>("recipe", 'r', Recipe.class));
		ModElementType.SPECIALENTITY = register(new ModElementType<>("specialentity", null, SpecialEntity.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.STRUCTURE = register(new ModElementType<>("structure", 's', Structure.class)).coveredOn(GeneratorFlavor.gamePlatform(JAVAEDITION));
		ModElementType.TAB = register(new ModElementType<>("tab", 'w', Tab.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.TOOL = register(new ModElementType<>("tool", 't', Tool.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.VILLAGERPROFESSION = register(new ModElementType<>("villagerprofession", null, VillagerProfession.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));
		ModElementType.VILLAGERTRADE = register(new ModElementType<>("villagertrade", null, VillagerTrade.class)).coveredOn(GeneratorFlavor.baseLanguage(JAVA));

		// Bedrock-specific METs
		ModElementType.BEBLOCK = register(new ModElementType<>("beblock", "block", 'b', BEBlock.class)).coveredOn(GeneratorFlavor.gamePlatform(BEDROCKEDITION));
		ModElementType.BEBIOME = register(new ModElementType<>("bebiome", "biome", 'o', BEBiome.class)).coveredOn(GeneratorFlavor.gamePlatform(BEDROCKEDITION));
		ModElementType.BEENTITY = register(new ModElementType<>("beentity", "livingentity", 'e', BEEntity.class)).coveredOn(GeneratorFlavor.gamePlatform(BEDROCKEDITION));
		ModElementType.BEITEM = register(new ModElementType<>("beitem", "item", 'i', BEItem.class)).coveredOn(GeneratorFlavor.gamePlatform(BEDROCKEDITION));
		ModElementType.BESCRIPT = register(new ModElementType<>("bescript", "procedure", 's', BEScript.class)).coveredOn(GeneratorFlavor.gamePlatform(BEDROCKEDITION));

		// Unregistered type used to mask legacy removed mod element types
		ModElementType.UNKNOWN = new ModElementType<>("unknown", null, GeneratableElement.Unknown.class);
		//@formatter:on
	}

	public static ModElementType<?> register(ModElementType<?> elementType) {
		REGISTRY.add(elementType);
		return elementType;
	}

	public static ModElementType<?> getModElementType(String typeName) throws IllegalArgumentException {
		if (typeName.equals("mob")) {
			typeName = "livingentity";
		}

		for (ModElementType<?> me : REGISTRY) {
			if (me.getRegistryName().equals(typeName)) {
				return me;
			}
		}

		throw new IllegalArgumentException("Mod element type " + typeName + " is not a registered type");
	}

	public static ModElementType<?> getModElementType(Class<? extends GeneratableElement> elementClass)
			throws IllegalArgumentException {
		for (ModElementType<?> me : REGISTRY) {
			if (me.getModElementStorageClass() == elementClass) {
				return me;
			}
		}
		throw new IllegalArgumentException(
				"Mod element type for class " + elementClass.getName() + " is not a registered type");
	}

	public static Collection<ModElementType<?>> getAllModElementTypes() {
		return Collections.unmodifiableList(REGISTRY);
	}

}
