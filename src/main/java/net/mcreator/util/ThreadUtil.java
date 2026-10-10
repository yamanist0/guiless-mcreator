package net.mcreator.util;

public class ThreadUtil {

	public static void runOnSwingThread(Runnable runnable) {
		runnable.run();
	}

	public static void runOnSwingThreadAndWait(Runnable runnable) {
		runnable.run();
	}
}
