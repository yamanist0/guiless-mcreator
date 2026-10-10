package net.mcreator.minecraft.states;

import java.util.ArrayList;
import java.util.List;

public class BlockStatePropertyUtils {

	public static List<Object> getPossiblePropertyValues(PropertyData<?> prop) {
		List<Object> values = new ArrayList<>();
		if (prop instanceof PropertyData.LogicType) {
			values.add(true);
			values.add(false);
		} else if (prop instanceof PropertyData.IntegerType intProp) {
			for (int i = intProp.getMin(); i <= intProp.getMax(); i++) {
				values.add(i);
			}
		} else if (prop instanceof PropertyData.EnumType enumProp) {
			values.addAll(enumProp.getValues());
		}
		return values;
	}
}
