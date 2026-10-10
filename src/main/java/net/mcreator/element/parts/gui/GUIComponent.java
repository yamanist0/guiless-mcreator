package net.mcreator.element.parts.gui;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import net.mcreator.element.parts.procedure.RetvalProcedure;
import net.mcreator.init.L10N;
import net.mcreator.util.StringUtils;
import net.mcreator.workspace.Workspace;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.*;
import java.awt.image.ImageObserver;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@JsonAdapter(GUIComponent.GSONAdapter.class) public abstract class GUIComponent implements Comparable<GUIComponent> {

	public static final int CANVAS_W = 176;
	public static final int CANVAS_H = 166;

	@Nullable public AnchorPoint anchorPoint;
	public int x;
	public int y;

	public boolean locked = false;
	public transient UUID uuid;

	private static final Map<String, Class<? extends GUIComponent>> typeMappings = new HashMap<>() {{
		put("tooltip", Tooltip.class);
		put("entitymodel", EntityModel.class);
		put("textfield", TextField.class);
		put("label", Label.class);
		put("slider", Slider.class);
		put("checkbox", Checkbox.class);
		put("imagebutton", ImageButton.class);
		put("button", Button.class);
		put("image", Image.class);
		put("sprite", Sprite.class);
		put("inputslot", InputSlot.class);
		put("outputslot", OutputSlot.class);
	}};

	private static final Map<Class<? extends GUIComponent>, String> typeMappingsReverse = typeMappings.entrySet()
			.stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

	GUIComponent() {
		uuid = UUID.randomUUID();
	}

	public GUIComponent(int x, int y) {
		this();
		this.x = x;
		this.y = y;
	}

	public abstract String getName();

	public abstract void paintComponent(int cx, int cy, ImageObserver observer, Graphics2D g);

	public abstract int getWidth(Workspace workspace);

	public abstract int getHeight(Workspace workspace);

	public abstract int getWeight();

	public boolean isSizeKnown() {
		return true;
	}

	public final int getX() {
		return x;
	}

	public final int getY() {
		return y;
	}

	public final int gx(int width) {
		double mx = (CANVAS_W - width) / 2.0;
		return (int) Math.floor(x - mx);
	}

	public final int gy(int height) {
		double my = (CANVAS_H - height) / 2.0;
		return (int) Math.floor(y - my);
	}

	@Nullable public AnchorPoint getAnchorPoint() {
		return anchorPoint;
	}

	@Override public final int compareTo(@Nonnull GUIComponent o) {
		return o.getWeight() - getWeight();
	}

	@Override public final int hashCode() {
		return uuid.hashCode();
	}

	@Override public final boolean equals(Object obj) {
		return (obj instanceof GUIComponent) && ((GUIComponent) obj).uuid.equals(this.uuid);
	}

	@Override public String toString() {
		return getName();
	}

	public static void registerCustomComponent(Class<? extends GUIComponent> type) {
		String id = StringUtils.camelToSnake(type.getSimpleName()).toLowerCase();
		typeMappings.put(id, type);
		typeMappingsReverse.put(type, id);
	}

	public static Map<String, Class<? extends GUIComponent>> getTypeMappings() {
		return Collections.unmodifiableMap(typeMappings);
	}

	public static class GSONAdapter implements JsonSerializer<GUIComponent>, JsonDeserializer<GUIComponent> {

		private static final Gson gson;

		static {
			GsonBuilder gsonBuilder = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting()
					.setStrictness(Strictness.LENIENT);

			RetvalProcedure.GSON_ADAPTERS.forEach(gsonBuilder::registerTypeAdapter);

			gson = gsonBuilder.create();
		}

		@Override
		public GUIComponent deserialize(JsonElement jsonElement, Type type,
				JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
			String elementType = jsonElement.getAsJsonObject().get("type").getAsString();

			Class<? extends GUIComponent> typeMapping = typeMappings.get(elementType);
			if (typeMapping == null)
				typeMapping = Unknown.class;

			GUIComponent component = jsonDeserializationContext.deserialize(jsonElement.getAsJsonObject().get("data"),
					typeMapping);
			component.uuid = UUID.randomUUID();
			return component;
		}

		@Override
		public JsonElement serialize(GUIComponent element, Type type,
				JsonSerializationContext jsonSerializationContext) {
			JsonObject root = new JsonObject();

			String typeMapping = typeMappingsReverse.get(element.getClass());
			if (typeMapping == null)
				typeMapping = "unknown";

			root.add("type", new JsonPrimitive(typeMapping));
			root.add("data", gson.toJsonTree(element));
			return root;
		}

	}

	public static final class Unknown extends GUIComponent {

		@Override public String getName() {
			return "unknown_element";
		}

		@Override public void paintComponent(int cx, int cy, ImageObserver observer, Graphics2D g) {

		}

		@Override public int getWidth(Workspace workspace) {
			return 0;
		}

		@Override public int getHeight(Workspace workspace) {
			return 0;
		}

		@Override public int getWeight() {
			return 0;
		}
	}

	public enum AnchorPoint {
		TOP_LEFT("top_left"), TOP_CENTER("top_center"), TOP_RIGHT("top_right"), CENTER_LEFT("center_left"), CENTER(
				"center"), CENTER_RIGHT("center_right"), BOTTOM_LEFT("bottom_left"), BOTTOM_CENTER(
				"bottom_center"), BOTTOM_RIGHT("bottom_right");

		private final String id;

		AnchorPoint(String id) {
			this.id = id;
		}

		@Override public String toString() {
			return L10N.t("dialog.gui.anchor." + id);
		}

		public Point getAnchorPoint(int width, int height) {
			return switch (this) {
				case TOP_LEFT -> new Point(0, 0);
				case TOP_CENTER -> new Point(width / 2, 0);
				case TOP_RIGHT -> new Point(width, 0);
				case CENTER_LEFT -> new Point(0, height / 2);
				case CENTER -> new Point(width / 2, height / 2);
				case CENTER_RIGHT -> new Point(width, height / 2);
				case BOTTOM_LEFT -> new Point(0, height);
				case BOTTOM_CENTER -> new Point(width / 2, height);
				case BOTTOM_RIGHT -> new Point(width, height);
			};
		}

	}

}
