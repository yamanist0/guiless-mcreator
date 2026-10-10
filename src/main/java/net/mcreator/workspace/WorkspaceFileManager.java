/*
 * MCreator (https://mcreator.net/)
 * Copyright (C) 2020 Pylo and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.mcreator.workspace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import net.mcreator.io.FileIO;
import net.mcreator.plugin.MCREvent;
import net.mcreator.plugin.events.workspace.WorkspaceSavedEvent;
import net.mcreator.preferences.PreferencesManager;
import net.mcreator.workspace.elements.ModElement;
import net.mcreator.workspace.elements.ModElementManager;
import net.mcreator.workspace.elements.SoundElement;
import net.mcreator.workspace.elements.TagElement;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class WorkspaceFileManager implements Closeable {

	private final Logger LOG;

	public static final Gson gson = new GsonBuilder().setStrictness(Strictness.LENIENT).setPrettyPrinting()
			.registerTypeAdapter(SoundElement.class, new SoundElement.SoundElementDeserializer())
			.registerTypeAdapter(TagElement.class, new TagElement.TagElementDeserializer())
			.registerTypeAdapter(ModElement.class, new ModElement.ModElementDeserializer()).create();

	private DataSavedListener dataSavedListener;

	private final ScheduledExecutorService dataSaveExecutor;
	private ScheduledFuture<?> lastSchedule;

	private final File workspaceFile;
	private final Workspace workspace;
	private final WorkspaceFolderManager folderManager;
	private final ModElementManager modElementManager;

	WorkspaceFileManager(@Nonnull File workspaceFile, @Nonnull Workspace workspace) {
		this.workspaceFile = workspaceFile;
		this.workspace = workspace;

		this.LOG = LogManager.getLogger("Workspace File Manager/" + workspace.toString().toUpperCase());

		this.folderManager = new WorkspaceFolderManager(workspaceFile, workspace);
		this.modElementManager = new ModElementManager(workspace);

		this.dataSaveExecutor = Executors.newScheduledThreadPool(1, runnable -> {
			Thread thread = new Thread(runnable);
			thread.setName("Workspace-File-Manager" );
			thread.setUncaughtExceptionHandler((t, e) -> LOG.error(e));
			return thread;
		});

		// start autosave scheduler
		lastSchedule = dataSaveExecutor.schedule(new SaveTask(this),
				PreferencesManager.PREFERENCES.backups.workspaceAutosaveInterval.get(), TimeUnit.SECONDS);
	}

	public File getWorkspaceFile() {
		return workspaceFile;
	}

	WorkspaceFolderManager getFolderManager() {
		return folderManager;
	}

	public ModElementManager getModElementManager() {
		return modElementManager;
	}

	@Override public void close() {
		try {
			closeStrict();
		} catch (java.io.IOException e) {
			LOG.error("Failed to save workspace while closing", e);
		}
	}

	public void closeStrict() throws java.io.IOException {
		stopAutosave();
		saveWorkspaceDirectlyAndWaitStrict();
	}

	public void closeWithoutSave() {
		stopAutosave();
	}

	private void stopAutosave() {
		if (lastSchedule != null)
			lastSchedule.cancel(true);
		dataSaveExecutor.shutdownNow();
	}

	public void saveWorkspaceDirectlyAndWait() {
		try {
			saveWorkspaceDirectlyAndWaitStrict();
		} catch (java.io.IOException e) {
			LOG.error("Failed to save workspace", e);
		}
	}

	public void saveWorkspaceDirectlyAndWaitStrict() throws java.io.IOException {
		LOG.info("Saving the workspace by direct request" );

		workspace.markDirty();

		if (lastSchedule != null)
			lastSchedule.cancel(true);

		saveWorkspaceIfChangedStrict();
		if (!dataSaveExecutor.isShutdown())
			lastSchedule = dataSaveExecutor.schedule(new SaveTask(this),
					PreferencesManager.PREFERENCES.backups.workspaceAutosaveInterval.get(), TimeUnit.SECONDS);
	}

	private synchronized void saveWorkspaceIfChanged() {
		try {
			saveWorkspaceIfChangedStrict();
		} catch (java.io.IOException e) {
			LOG.error("Failed to save workspace", e);
		}
	}

	private synchronized void saveWorkspaceIfChangedStrict() throws java.io.IOException {
		MCREvent.event(new WorkspaceSavedEvent.CalledSavingMethod(workspace));

		if (!workspace.isDirty())
			return;

		String workspacestring = gson.toJson(workspace);
		if (workspacestring.isEmpty())
			throw new java.io.IOException("workspace serialization produced no data");

		MCREvent.event(new WorkspaceSavedEvent.BeforeSaving(workspace));
		rotateWorkspaceFileBackup();

		File outFile = workspaceFile;
		File tmpFile = new File(folderManager.getWorkspaceFolder(), workspaceFile.getName() + ".lock" );
		java.nio.file.Path parent = tmpFile.toPath().getParent();
		if (parent != null)
			Files.createDirectories(parent);
		Files.writeString(tmpFile.toPath(), workspacestring, java.nio.charset.StandardCharsets.UTF_8);
		try {
			Files.move(tmpFile.toPath(), outFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
		} catch (java.io.IOException atomicFailure) {
			try {
				Files.move(tmpFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			} catch (java.io.IOException moveFailure) {
				moveFailure.addSuppressed(atomicFailure);
				try {
					Files.writeString(outFile.toPath(), workspacestring, java.nio.charset.StandardCharsets.UTF_8);
				} catch (java.io.IOException writeFailure) {
					writeFailure.addSuppressed(moveFailure);
					throw writeFailure;
				} finally {
					Files.deleteIfExists(tmpFile.toPath());
				}
			}
		}

		workspace.markClean();
		if (dataSavedListener != null)
			dataSavedListener.dataSaved();
		MCREvent.event(new WorkspaceSavedEvent.AfterSaving(workspace));
		LOG.debug("Workspace stored on the FS" );
	}

	private void rotateWorkspaceFileBackup() {
		int numberOfBackupsExcludingCurrent = PreferencesManager.PREFERENCES.backups.numberOfBackupsToStore.get() - 1;
		File[] existingBackupsArray = folderManager.getWorkspaceBackupsCacheDir().listFiles();

		if (existingBackupsArray != null && existingBackupsArray.length > 0) { // we already have some backups
			List<File> existingBackups = new ArrayList<>(Arrays.asList(existingBackupsArray));
			existingBackups.sort(Comparator.comparingLong(File::lastModified));
			Collections.reverse(existingBackups);
			long lastBackupTime = existingBackups.getFirst().lastModified();
			if ((System.currentTimeMillis() - lastBackupTime) / (1000 * 60)
					> PreferencesManager.PREFERENCES.backups.automatedBackupInterval.get()) {  // check if we have surpassed backup interval
				if (existingBackupsArray.length
						> numberOfBackupsExcludingCurrent) // only delete old ones if we have more than threshold of backups
					existingBackups.stream().skip(numberOfBackupsExcludingCurrent).forEach(File::delete);
				createNewWorkspaceFileBackup();
			}
		} else { // we don't have any backup yet
			createNewWorkspaceFileBackup();
		}
	}

	private void createNewWorkspaceFileBackup() {
		// if workspace file exists, so we can back it up, we back it up
		if (workspaceFile.isFile()) {
			File backupFile = new File(folderManager.getWorkspaceBackupsCacheDir(),
					workspaceFile.getName() + "-backup_" + new SimpleDateFormat("yyyyMMdd_HHmmss" ).format(new Date()));
			FileIO.copyFile(workspaceFile, backupFile);
		}
	}

	public void setDataSavedListener(DataSavedListener listener) {
		dataSavedListener = listener;
	}

	public interface DataSavedListener {
		void dataSaved();
	}

	private record SaveTask(WorkspaceFileManager fileManager) implements Runnable {

		@Override public void run() {
			fileManager.saveWorkspaceIfChanged();

			if (!fileManager.dataSaveExecutor.isShutdown())
				fileManager.lastSchedule = fileManager.dataSaveExecutor.schedule(new SaveTask(fileManager),
						PreferencesManager.PREFERENCES.backups.workspaceAutosaveInterval.get(), TimeUnit.SECONDS);
		}

	}

}
