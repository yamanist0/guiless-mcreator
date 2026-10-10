package net.mcreator.util;

public record ValidationResult(Type type, String message) {
	public enum Type {
		PASSED, WARNING, ERROR
	}

	public static final ValidationResult PASSED = new ValidationResult(Type.PASSED, "");
}
