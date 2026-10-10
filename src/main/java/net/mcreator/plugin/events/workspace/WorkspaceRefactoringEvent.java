/*
 * MCreator (https://mcreator.net/)
 * Copyright (C) 2012-2020, Pylo
 * Copyright (C) 2020-2023, Pylo, opensource contributors
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

package net.mcreator.plugin.events.workspace;

import net.mcreator.HeadlessApplication;
import net.mcreator.plugin.MCREvent;
import net.mcreator.workspace.settings.WorkspaceSettingsChange;

public class WorkspaceRefactoringEvent extends MCREvent {
	private final HeadlessApplication application;
	private final WorkspaceSettingsChange change;

	/**
	 * <p>An event triggered each time the application refactors a {@link net.mcreator.workspace.Workspace}.</p>
	 *
	 * @param application <p>The {@link HeadlessApplication} of the workspace</p>
	 * @param change      <p>This variable contains the new workspace settings.</p>
	 */
	public WorkspaceRefactoringEvent(HeadlessApplication application, WorkspaceSettingsChange change) {
		this.application = application;
		this.change = change;
	}

	public HeadlessApplication getApplication() {
		return application;
	}

	public WorkspaceSettingsChange getChange() {
		return change;
	}
}
