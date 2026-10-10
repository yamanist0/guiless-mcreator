package net.mcreator.element;

import net.mcreator.generator.GeneratorFlavor;
import net.mcreator.init.L10N;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class ModElementType<GE extends GeneratableElement> {

	private final String registryName;
	private final Class<? extends GE> modElementStorageClass;

	private final String readableName;
	private final String description;
	@Nullable private final Character shortcut;

	private final String iconID;

	@Nullable private List<GeneratorFlavor> coveredFlavors;

	public ModElementType(String registryName, @Nullable Character shortcut,
			Class<? extends GE> modElementStorageClass) {
		this(registryName, registryName, shortcut, modElementStorageClass);
	}

	public ModElementType(String registryName, String iconSufix, @Nullable Character shortcut,
			Class<? extends GE> modElementStorageClass) {
		this.registryName = registryName;
		this.shortcut = shortcut;
		this.modElementStorageClass = modElementStorageClass;

		this.readableName = L10N.t("modelement." + registryName.toLowerCase(Locale.ENGLISH));
		this.description = L10N.t("modelement." + registryName.toLowerCase(Locale.ENGLISH) + ".description");

		this.iconID = "mod_types." + iconSufix;
	}

	public String getRegistryName() {
		return registryName;
	}

	public String getPluralName() {
		if (this == LIVINGENTITY)
			return "livingentities";
		else if (this == SPECIALENTITY)
			return "specialentities";
		else if (this == BEENTITY)
			return "beentities";

		return registryName.toLowerCase(Locale.ENGLISH) + "s";
	}

	@Nullable public Character getShortcut() {
		return shortcut;
	}

	public String getReadableName() {
		return readableName;
	}

	public String getDescription() {
		return description;
	}

	public String getIconID() {
		return iconID;
	}

	public Class<? extends GE> getModElementStorageClass() {
		return modElementStorageClass;
	}

	public ModElementType<GE> coveredOn(GeneratorFlavor... supportedFlavors) {
		this.coveredFlavors = List.of(supportedFlavors);
		return this;
	}

	public List<GeneratorFlavor> getCoveredFlavors() {
		return coveredFlavors != null ?
				coveredFlavors :
				Arrays.stream(GeneratorFlavor.values()).filter(e -> !GeneratorFlavor.SPECIAL_FLAVORS.contains(e))
						.toList();
	}

	@Override public String toString() {
		return this.registryName;
	}

	@Override public boolean equals(Object element) {
		return element instanceof ModElementType && registryName.equals(
				((ModElementType<?>) element).getRegistryName());
	}

	@Override public int hashCode() {
		return registryName.hashCode();
	}

	public static ModElementType<?> ADVANCEMENT;
	public static ModElementType<?> ARMOR;
	public static ModElementType<?> ARMORTRIM;
	public static ModElementType<?> ATTRIBUTE;
	public static ModElementType<?> BANNERPATTERN;
	public static ModElementType<?> BIOME;
	public static ModElementType<?> BLOCK;
	public static ModElementType<?> COMMAND;
	public static ModElementType<?> DAMAGETYPE;
	public static ModElementType<?> DIMENSION;
	public static ModElementType<?> CODE;
	public static ModElementType<?> ENCHANTMENT;
	public static ModElementType<?> FEATURE;
	public static ModElementType<?> FLUID;
	public static ModElementType<?> FUNCTION;
	public static ModElementType<?> GAMERULE;
	public static ModElementType<?> GUI;
	public static ModElementType<?> ITEM;
	public static ModElementType<?> ITEMEXTENSION;
	public static ModElementType<?> KEYBIND;
	public static ModElementType<?> LIVINGENTITY;
	public static ModElementType<?> LOOTTABLE;
	public static ModElementType<?> OVERLAY;
	public static ModElementType<?> PAINTING;
	public static ModElementType<?> PARTICLE;
	public static ModElementType<?> PLANT;
	public static ModElementType<?> POTION;
	public static ModElementType<?> POTIONEFFECT;
	public static ModElementType<?> PROCEDURE;
	public static ModElementType<?> PROJECTILE;
	public static ModElementType<?> RECIPE;
	public static ModElementType<?> SPECIALENTITY;
	public static ModElementType<?> STRUCTURE;
	public static ModElementType<?> TAB;
	public static ModElementType<?> TOOL;
	public static ModElementType<?> VILLAGERPROFESSION;
	public static ModElementType<?> VILLAGERTRADE;

	// Bedrock-specific METs
	public static ModElementType<?> BEBLOCK;
	public static ModElementType<?> BEBIOME;
	public static ModElementType<?> BEENTITY;
	public static ModElementType<?> BEITEM;
	public static ModElementType<?> BESCRIPT;

	public static ModElementType<?> UNKNOWN;

}
