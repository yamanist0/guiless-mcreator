package net.mcreator.workspace.resources;

import net.mcreator.generator.GeneratorConfiguration;

import java.util.Arrays;

public enum TextureType {

	BLOCK("block", "blocks"),
	ITEM("item", "items"),
	ARMOR("armor", "armor"),
	ENTITY("entity", "entities"),
	SCREEN("screen", "screens"),
	OTHER("other", "other"),
	PARTICLE("particle", "particles"),
	EFFECT("effect", "effects"),
	PARTICLES("particle", "particles"),
	ENVIRONMENT("environment", "environment");

	private final String typeName;
	private final String pluralName;

	TextureType(String typeName, String pluralName) {
		this.typeName = typeName;
		this.pluralName = pluralName;
	}

	public String getTypeName() {
		return typeName;
	}

	public String getPluralName() {
		return pluralName;
	}

	public String getID() {
		return typeName;
	}

	@Override
	public String toString() {
		return typeName;
	}

	public static TextureType[] getValuesForGenerator(GeneratorConfiguration configuration) {
		return values();
	}
}
