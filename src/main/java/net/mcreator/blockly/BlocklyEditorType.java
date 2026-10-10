package net.mcreator.blockly;

public enum BlocklyEditorType {

    PROCEDURE("procedure", "event_trigger"),
    AI_TASK("aitasks", "aitasks_container"),
    FEATURE("feature", "feature"),
    JSON_TRIGGER("jsontrigger", "jsontrigger"),
    COMMAND_ARG("commandarg", "command_arg"),
    SCRIPT("script", "script"),
    ENCHANTMENT_EFFECTS("enchantmenteffects", "enchantment_effects"),
    ENCHANTMENT("enchantment", "enchantment"),
    COMMAND("command", "command"),
    EFFECT("effect", "effect"),
    BEDROCK_SCRIPT("bedrockscript", "bedrock_script"),
    BEDROCK_AI_TASK("bedrockaitask", "bedrock_ai_task");

    private final String registryName;
    private final String startBlockName;

    BlocklyEditorType(String registryName, String startBlockName) {
        this.registryName = registryName;
        this.startBlockName = startBlockName;
    }

    public String registryName() {
        return registryName;
    }

    public String startBlockName() {
        return startBlockName;
    }

    @Override
    public String toString() {
        return registryName;
    }
}
