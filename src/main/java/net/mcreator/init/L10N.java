// localization utility - headless version, moved from ui.init
// provides text translation without any swing dependencies

package net.mcreator.init;

import net.mcreator.plugin.PluginLoader;
import net.mcreator.preferences.PreferencesManager;
import net.mcreator.util.FilenameUtilsPatched;
import net.mcreator.util.locale.LocaleRegistration;
import net.mcreator.util.locale.UTF8Control;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.MessageFormat;
import java.util.*;
import java.util.stream.Collectors;

public class L10N {

	private static final Logger LOG = LogManager.getLogger("L10N");

	public static final Locale DEFAULT_LOCALE = Locale.of("en", "US");

	// coverage prefix used by generator selector logic
	public static final String COVERAGE_PREFIX = "generator.coverage.";

	private static ResourceBundle rb;
	private static ResourceBundle rb_en;

	private static Map<Locale, LocaleRegistration> supportedLocales;

	private static Locale osLocale = Locale.getDefault();

	private static Locale selectedLocale = null;

	public static void initTranslations() {
		initLocalesImpl();

		selectedLocale = null;

		if (supportedLocales.containsKey(getLocale())) {
			rb = supportedLocales.get(getLocale()).resourceBundle();
		} else {
			LOG.warn("Locale {} is not supported. Falling back to default locale.", getLocale());
			rb = supportedLocales.get(DEFAULT_LOCALE).resourceBundle();
		}

		osLocale = Locale.getDefault();
		LOG.info("Setting default locale to: {}; OS locale: {}", getLocale(), osLocale);
		Locale.setDefault(getLocale());
	}

	private static void initLocalesImpl() {
		if (rb_en != null)
			return;

		rb_en = ResourceBundle.getBundle("lang/texts", Locale.ROOT, PluginLoader.INSTANCE, new UTF8Control());

		double countAll = Collections.list(rb_en.getKeys()).size();

		Set<String> localeFiles = PluginLoader.INSTANCE.getResourcesInPackage("lang");
		supportedLocales = localeFiles.stream().map(FilenameUtilsPatched::getBaseName).filter(e -> e.contains("_"))
				.map(e -> e.split("_")).map(e -> Locale.of(e[1], e[2])).collect(Collectors.toMap(key -> key, value -> {
					ResourceBundle rb = ResourceBundle.getBundle("lang/texts", value, PluginLoader.INSTANCE,
							new UTF8Control());
					return new LocaleRegistration(rb,
							(int) Math.ceil(Collections.list(rb.getKeys()).size() / countAll * 100d), 0);
				}));

		supportedLocales.put(DEFAULT_LOCALE, new LocaleRegistration(rb_en, 100, 100));
	}

	public static Set<Locale> getSupportedLocales() {
		return supportedLocales.keySet();
	}

	public static int getUITextsLocaleSupport(Locale locale) {
		LocaleRegistration localeRegistration = supportedLocales.get(locale);
		if (localeRegistration != null)
			return localeRegistration.uiTextsPercentage();
		return 0;
	}

	public static int getHelpTipsSupport(Locale locale) {
		LocaleRegistration localeRegistration = supportedLocales.get(locale);
		if (localeRegistration != null)
			return localeRegistration.helpTipsPercentage();
		return 0;
	}

	public static Locale getLocale() {
		if (selectedLocale == null)
			selectedLocale = PreferencesManager.PREFERENCES.hidden.language.get();
		return selectedLocale;
	}

	public static Locale getOSLocale() {
		return osLocale;
	}

	public static String getLocaleString() {
		return getLocale().toString();
	}

	public static String getBlocklyLangName() {
		Locale locale = getLocale();

		if (Locale.of("zh", "TW").equals(locale))
			return "zh-hant";
		else if (Locale.of("zh", "CN").equals(locale))
			return "zh-hans";

		return getLocaleString().split("_")[0].replace("iw", "he");
	}

	public static String t(String key, Object... parameters) {
		return t_impl(rb, key, parameters);
	}

	public static String t_en(String key, Object... parameters) {
		return t_impl(rb_en, key, parameters);
	}

	private static String t_impl(ResourceBundle resourceBundle, String key, Object... parameters) {
		if (key == null)
			return null;

		if (resourceBundle.containsKey(key)) {
			return MessageFormat.format(resourceBundle.getString(key), parameters);
		} else if (key.startsWith("blockly.") && (key.endsWith(".tooltip") || key.endsWith(".tip") || key.endsWith(
				".description"))) {
			return null;
		} else if (key.startsWith("blockly.") || key.startsWith("trigger.") || key.startsWith(COVERAGE_PREFIX)) {
			LOG.warn("Missing translation for key: {} in locale: {}", key, getLocale());
			return null;
		} else {
			return key;
		}
	}

}
