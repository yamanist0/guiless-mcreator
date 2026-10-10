package net.mcreator.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class TerribleModuleHacks {

	private static final Logger LOG = LogManager.getLogger("Terrible module hacks");

	public static void openAllFor(Module moduleToOpenFor) {
		ModuleLayer.boot().modules()
				.forEach(module -> module.getPackages().forEach(pn -> addOpens(module, pn, moduleToOpenFor)));
	}

	public static void addOpens(Module where, String pn, Module toadd) {
		try {
			Method method = where.getClass()
					.getDeclaredMethod("implAddExportsOrOpens", String.class, Module.class, boolean.class,
							boolean.class);
			method.setAccessible(true);
			method.invoke(where, pn, toadd, true, true);
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
			LOG.error("Failed to open module: {}", where.getName(), e);
		}
	}

}
