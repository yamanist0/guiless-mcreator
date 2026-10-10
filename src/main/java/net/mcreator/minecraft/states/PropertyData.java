package net.mcreator.minecraft.states;

import java.util.List;
import java.util.Objects;

public abstract class PropertyData<T> {

	private final String name;

	public PropertyData(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		PropertyData<?> that = (PropertyData<?>) o;
		return Objects.equals(name, that.name);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name);
	}

	@Override
	public String toString() {
		return name;
	}

	public static class LogicType extends PropertyData<Boolean> {
		public LogicType(String name) {
			super(name);
		}
	}

	public static class IntegerType extends PropertyData<Integer> {
		private final int min;
		private final int max;

		public IntegerType(String name, int min, int max) {
			super(name);
			this.min = min;
			this.max = max;
		}

		public int getMin() {
			return min;
		}

		public int getMax() {
			return max;
		}
	}

	public static class EnumType extends PropertyData<String> {
		private final List<String> values;

		public EnumType(String name, List<String> values) {
			super(name);
			this.values = values;
		}

		public List<String> getValues() {
			return values;
		}
	}
}
