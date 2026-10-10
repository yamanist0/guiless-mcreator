package net.mcreator.minecraft.states;

public class PropertyDataWithValue<T> {
	public PropertyData<T> property;
	public T value;

	public PropertyDataWithValue() {
	}

	public PropertyDataWithValue(PropertyData<T> property, T value) {
		this.property = property;
		this.value = value;
	}
}
