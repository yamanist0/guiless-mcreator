package net.mcreator.minecraft.states;

import com.google.gson.*;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

public class StateMap extends LinkedHashMap<PropertyData<?>, Object> {

	public StateMap() {
	}

	public StateMap(Map<? extends PropertyData<?>, ?> map) {
		super(map);
	}

	public static class GSONAdapter implements JsonSerializer<StateMap>, JsonDeserializer<StateMap> {
		@Override
		public StateMap deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
				throws JsonParseException {
			StateMap map = new StateMap();
			if (json.isJsonObject()) {
				JsonObject obj = json.getAsJsonObject();
				for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
					map.put(new PropertyData.LogicType(entry.getKey()), entry.getValue().getAsString());
				}
			}
			return map;
		}

		@Override
		public JsonElement serialize(StateMap src, Type typeOfSrc, JsonSerializationContext context) {
			JsonObject obj = new JsonObject();
			for (Map.Entry<PropertyData<?>, Object> entry : src.entrySet()) {
				obj.addProperty(entry.getKey().getName(), entry.getValue().toString());
			}
			return obj;
		}
	}
}
