// main entry point for headless mcreator backend
// stripped of all swing/awt ui code

package net.mcreator;

import net.mcreator.io.LoggingSystem;
import net.mcreator.io.OS;
import net.mcreator.io.UserFolderManager;
import net.mcreator.io.WindowsPackage;
import net.mcreator.preferences.PreferencesManager;
import net.mcreator.util.MCreatorVersionNumber;
import net.mcreator.util.TerribleModuleHacks;
import net.mcreator.util.UTF8Forcer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;

public class Launcher {

	public static MCreatorVersionNumber version;

	// We capture the original System.out BEFORE LoggingSystem.init(), because
	// init() wraps System.out into Log4j. IPCServer needs the unwrapped
	// original stdout for its clean JSON output.
	public static final java.io.PrintStream RAW_STDOUT = System.out;

	public static void main(String[] args) {
		// in IPC mode, direct all logs to stderr so stdout stays clean JSON
		if (args.length > 0 && "--ipc".equals(args[0])) {
			System.setProperty("log_console_target", "SYSTEM_ERR");
		}

		LoggingSystem.init();

		TerribleModuleHacks.openAllFor(ClassLoader.getSystemClassLoader().getUnnamedModule());

		UTF8Forcer.forceGlobalUTF8();

		// disable xml parser depth limit for blockly
		System.setProperty("jdk.xml.maxElementDepth", "0");

		final Logger LOG = LogManager.getLogger("Launcher");

		try {
			Properties conf = new Properties();
			conf.load(Launcher.class.getResourceAsStream("/mcreator.conf"));
			version = new MCreatorVersionNumber(conf);
		} catch (IOException e) {
			LOG.error("Failed to read MCreator config", e);
		}

		LOG.info("Starting MCreator {} (headless mode)", version);

		LOG.info("Java version: {}, VM: {}, vendor: {}", Runtime.version(), System.getProperty("java.vm.name"),
				System.getProperty("java.vendor"));
		LOG.info("Current JAVA_HOME for running instance: {}", System.getProperty("java.home"));

		PreferencesManager.init();

		// check architecture
		if (OS.getSystemBits() == OS.BIT32) {
			LOG.error("32-bit platforms are not supported");
			System.exit(-1);
		}

		LOG.info("Installation path: {}", System.getProperty("user.dir"));
		LOG.info("User home of MCreator: {}", UserFolderManager.getFileFromUserFolder("/"));

		WindowsPackage.initIfWindows();

		if (!UserFolderManager.createUserFolderIfNotExists()) {
			LOG.error("Failed to write to user directory: {}", UserFolderManager.getFileFromUserFolder("/"));
			System.exit(-2);
		}

		// IPC mode: if the first argument is --ipc, start the stdin/stdout JSON loop
		// with Tauri. It runs the full HeadlessApplication bootstrap (generators,
		// plugins, etc.) and then hands over to the IPCServer loop.
		if (args.length > 0 && "--ipc".equals(args[0])) {
			LOG.info("Starting in IPC mode (Tauri bridge)");
			HeadlessApplication.createApplication(Arrays.asList(args));
			IPCServer.runLoop();
			return;
		}
		HeadlessApplication.createApplication(Arrays.asList(args));
	}

}
