package net.mcreator.gradle;

import net.mcreator.preferences.PreferencesManager;

public class GradleErrorDecoder {

	public static GradleResultCode processErrorAndShowMessage(String out, String err) {
		out = out.replace('\u00a0', ' ');
		err = err.replace('\u00a0', ' ');

		if (err.contains("\nExecution failed for task ':reobfJar'")) {
			return GradleResultCode.GRADLE_REOBF_FAILED;
		}

		if (err.contains(" Software caused connection abort: ") && out.contains("\nBUILD FAILED\n")) {
			return GradleResultCode.GRADLE_INTERNET_INTERRUPTED;
		}

		if ((err.contains("Could not GET ") && err.contains("Could not resolve ")) || (
				err.contains(" Network is unreachable: ") && err.contains("Could not resolve ")) || (
				err.contains("Could not HEAD ") && err.contains("Could not resolve "))) {
			return GradleResultCode.GRADLE_NO_INTERNET;
		}

		if ((err.contains("java.io.FileNotFoundException: ") && err.contains("McpMappings.json (")) || (
				err.contains("Could not open proj remapped class cache for ") && err.contains(
						"java.io.FileNotFoundException: "))) {
			return GradleResultCode.GRADLE_CACHEDATA_ERROR;
		}

		if ((err.contains("No cached version of ") && err.contains(" available for offline mode.")) || (
				err.contains(" not found! Maybe you are running in offline mode?") && err.contains(
						"java.io.FileNotFoundException"))) {
			if (PreferencesManager.PREFERENCES.gradle.offline.get())
				return GradleResultCode.GRADLE_CACHEDATA_OUTDATED;
			else
				return GradleResultCode.GRADLE_CACHEDATA_ERROR;
		}

		if (err.contains("java.lang.OutOfMemoryError: Java heap space") || out.contains(
				"java.lang.OutOfMemoryError: Java heap space") || err.contains("Could not reserve enough space for")
				|| out.contains("Could not reserve enough space for") || err.contains("GC overhead limit exceeded") || (
				err.contains("Execution failed for task") && out.contains(
						"Daemon stopping because JVM tenured space is exhausted"))) {
			return GradleResultCode.JAVA_JVM_HEAP_SPACE;
		}

		if (err.contains("Invalid maximum heap size:")) {
			return GradleResultCode.JAVA_XMX_INVALID_VALUE;
		}

		if (err.contains("Invalid initial heap size:") || err.contains(
				"Initial heap size set to a larger value than the maximum heap size")) {
			return GradleResultCode.JAVA_XMS_INVALID_VALUE;
		}

		if ((out.contains("The crash happened outside the Java Virtual Machine in native code") || err.contains(
				"The crash happened outside the Java Virtual Machine in native code")) && (
				out.contains("A fatal error has been detected by the Java Runtime Environment") || err.contains(
						"A fatal error has been detected by the Java Runtime Environment"))) {
			return GradleResultCode.JAVA_JVM_CRASH_ERROR;
		}

		if (out.contains("Task :runClient FAILED") || out.contains("Execution failed for task ':runClient'")) {
			return GradleResultCode.JAVA_RUN_CRASHED;
		}

		if (out.contains("\nBUILD FAILED")) {
			return GradleResultCode.GRADLE_BUILD_FAILED;
		}

		return GradleResultCode.STATUS_OK;
	}

	public static boolean isErrorCausedByCorruptedCaches(String errortext) {
		errortext = errortext.replace('\u00a0', ' ');
		if (!errortext.contains("Could not GET ") && !errortext.contains("Could not HEAD ") && !errortext.contains(
				"Network is unreachable:")) {
			if (errortext.contains("java.util.zip.ZipException: error in opening zip file")) {
				return true;
			}
			if (errortext.contains("UncheckedIOException: Could not load properties for module")) {
				return true;
			}
			return errortext.contains("Could not resolve all files for configuration");
		}
		return false;
	}

	public static boolean doesErrorSuggestRerun(String errortext) {
		errortext = errortext.replace('\u00a0', ' ');
		return errortext.contains("try running the task again.");
	}

	public static boolean isErrorDueToJMXPortIssues(String errortext) {
		errortext = errortext.replace('\u00a0', ' ');
		return errortext.contains("java.rmi.server.ExportException: Port already in use:") && errortext.contains(
				".jmxremote.");
	}

}
