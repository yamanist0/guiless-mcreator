package net.mcreator;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IPCServerTest {

	@TempDir Path temporaryDirectory;

	@Test void rejectsMalformedRequestEnvelopes() {
		assertRequestError("[]", "invalid message: request must be a JSON object");
		assertRequestError("null", "invalid message: request must be a JSON object");
		assertRequestError("{}", "invalid message: cmd must be a non-empty string");
		assertRequestError("{\"cmd\":null}", "invalid message: cmd must be a non-empty string");
		assertRequestError("{\"cmd\":\"  \"}", "invalid message: cmd must be a non-empty string");
		assertRequestError("{\"cmd\":7}", "invalid message: cmd must be a non-empty string");
		assertRequestError("{\"cmd\":true}", "invalid message: cmd must be a non-empty string");
		assertRequestError("{\"cmd\":[]}", "invalid message: cmd must be a non-empty string");
		assertRequestError("{\"cmd\":{}}", "invalid message: cmd must be a non-empty string");
		assertRequestError("{\"cmd\":\"ping\",\"args\":[]}", "invalid message: args must be a JSON object");
		assertRequestError("{\"cmd\":\"ping\",\"args\":\"bad\"}", "invalid message: args must be a JSON object");
	}

	@Test void defaultsMissingOrNullArgsToEmptyObject() {
		IPCServer.RequestEnvelope missingArgs = IPCServer.parseRequest("{\"cmd\":\" ping \"}");
		assertNull(missingArgs.error());
		assertEquals("ping", missingArgs.command());
		assertEquals(0, missingArgs.args().size());

		IPCServer.RequestEnvelope nullArgs = IPCServer.parseRequest("{\"cmd\":\"ping\",\"args\":null}");
		assertNull(nullArgs.error());
		assertEquals(0, nullArgs.args().size());
	}

	@Test void rejectsWorkspaceFolderAndModIdMismatch() throws IOException {
		Path workspace = Files.createDirectory(temporaryDirectory.resolve("different-folder"));
		writeWorkspaceMarker(workspace, "actual_mod");

		assertEquals("workspace modid does not match requested modid",
				IPCServer.validateWorkspaceTarget(workspaceArgs(workspace, "requested_mod")));
	}

	@Test void rejectsWorkspaceWithoutMarker() throws IOException {
		Path workspace = Files.createDirectory(temporaryDirectory.resolve("markerless"));

		assertEquals("workspace marker not found: " + workspace.toFile().getCanonicalFile(),
				IPCServer.validateWorkspaceTarget(workspaceArgs(workspace, "sample_mod")));
	}

	@Test void rejectsWorkspaceMarkerWithoutModId() throws IOException {
		Path workspace = Files.createDirectory(temporaryDirectory.resolve("missing-modid"));
		Files.writeString(workspace.resolve("sample_mod.modstudio_ai"), "{\"workspaceSettings\":{}}");

		assertEquals("workspace modid is missing: sample_mod.modstudio_ai",
				IPCServer.validateWorkspaceTarget(workspaceArgs(workspace, "sample_mod")));
	}

	@Test void falseGenerationResultsAreErrors() {
		IOException error = assertThrows(IOException.class,
				() -> GenerationCoordinator.requireSuccess(false, "element generation"));
		assertEquals("element generation failed", error.getMessage());
	}

	@Test void regenerationOrdersElementsBeforeOneFinalBasePass() throws Exception {
		List<String> events = new ArrayList<>();
		int count = GenerationCoordinator.regenerateAndLaunch(List.of(stubElement(), stubElement()),
				actions(events, true, true));

		assertEquals(2, count);
		assertEquals(List.of("element", "verify element", "element", "verify element", "base", "verify base",
				"save", "launch"), events);
	}

	@Test void regenerationStopsBeforeBaseSaveAndLaunchAfterElementFailure() {
		List<String> events = new ArrayList<>();
		assertThrows(IOException.class,
				() -> GenerationCoordinator.regenerateAndLaunch(List.of(stubElement()), actions(events, false, true)));
		assertEquals(List.of("element"), events);
	}

	@Test void regenerationStopsBeforeSaveAndLaunchAfterBaseFailure() {
		List<String> events = new ArrayList<>();
		assertThrows(IOException.class,
				() -> GenerationCoordinator.regenerateAndLaunch(List.of(), actions(events, true, false)));
		assertEquals(List.of("base"), events);
	}

	@Test void regenerationStopsBeforeBaseSaveAndLaunchAfterVerificationFailure() {
		List<String> events = new ArrayList<>();
		GenerationCoordinator.Actions actions = actions(events, true, true);
		assertThrows(IOException.class, () -> GenerationCoordinator.regenerateAndLaunch(List.of(stubElement()),
				failingActions(actions, "verify element")));
		assertEquals(List.of("element", "verify element"), events);
	}

	@Test void regenerationStopsBeforeLaunchAfterSaveFailure() {
		List<String> events = new ArrayList<>();
		GenerationCoordinator.Actions actions = actions(events, true, true);
		assertThrows(IOException.class,
				() -> GenerationCoordinator.regenerateAndLaunch(List.of(), failingActions(actions, "save")));
		assertEquals(List.of("base", "verify base", "save"), events);
	}

	@Test void transactionalRegenerationRestoresWorkspaceAfterFailure() throws Exception {
		Path root = Files.createDirectory(temporaryDirectory.resolve("run-rollback"));
		Path original = Files.writeString(root.resolve("original.txt"), "before");
		List<String> events = new ArrayList<>();
		GenerationCoordinator.Actions actions = new GenerationCoordinator.Actions() {
			@Override public boolean generateElement(net.mcreator.element.GeneratableElement element) throws IOException {
				events.add("element");
				Files.writeString(original, "after");
				Files.writeString(root.resolve("created.txt"), "created");
				return true;
			}

			@Override public void verifyElement(net.mcreator.element.GeneratableElement element) throws IOException {
				events.add("verify element");
				throw new IOException("verification failed");
			}

			@Override public boolean generateBase() {
				events.add("base");
				return true;
			}

			@Override public void verifyBase() {
				events.add("verify base");
			}

			@Override public void save() {
				events.add("save");
			}

			@Override public void launch() {
				events.add("launch");
			}
		};

		assertThrows(IOException.class,
				() -> GenerationCoordinator.regenerateAndLaunchTransactional(root, List.of(stubElement()), actions));
		assertEquals("before", Files.readString(original));
		assertFalse(Files.exists(root.resolve("created.txt")));
		assertEquals(List.of("element", "verify element"), events);
	}

	@Test void transactionalRegenerationAbortsBeforeRestoreAndSuppressesLaterActions() throws Exception {
		Path root = Files.createDirectory(temporaryDirectory.resolve("strict-write-rollback"));
		Path original = Files.writeString(root.resolve("generated.txt"), "before");
		List<String> events = new ArrayList<>();
		GenerationCoordinator.Actions actions = new GenerationCoordinator.Actions() {
			@Override public boolean generateElement(net.mcreator.element.GeneratableElement element) throws IOException {
				events.add("element");
				Files.writeString(original, "partial");
				throw new IOException("element generation failed");
			}

			@Override public void verifyElement(net.mcreator.element.GeneratableElement element) {
				events.add("verify element");
			}

			@Override public boolean generateBase() {
				events.add("base");
				return true;
			}

			@Override public void verifyBase() {
				events.add("verify base");
			}

			@Override public void save() {
				events.add("save");
			}

			@Override public void launch() {
				events.add("launch");
			}

			@Override public void abort() throws IOException {
				events.add("abort " + Files.readString(original));
			}
		};

		assertThrows(IOException.class,
				() -> GenerationCoordinator.regenerateAndLaunchTransactional(root, List.of(stubElement()), actions));
		assertEquals(List.of("element", "abort partial"), events);
		assertEquals("before", Files.readString(original));
	}

	@Test void journalRestoresChangedDeletedAndCreatedPaths() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("journal"));
		Path original = Files.writeString(root.resolve("original.txt"), "before");
		Path deleted = Files.writeString(root.resolve("deleted.txt"), "restore");
		WorkspaceJournal journal = WorkspaceJournal.capture(root);

		Files.writeString(original, "after");
		Files.delete(deleted);
		Files.createDirectories(root.resolve("created"));
		Files.writeString(root.resolve("created/new.txt"), "new");
		journal.restore();

		assertEquals("before", Files.readString(original));
		assertEquals("restore", Files.readString(deleted));
		assertFalse(Files.exists(root.resolve("created")));
	}

	@Test void journalRestoresFileAndDirectoryTypeChanges() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("journal-types"));
		Path originalFile = Files.writeString(root.resolve("file"), "before");
		Path originalDirectory = Files.createDirectory(root.resolve("directory"));
		Files.writeString(originalDirectory.resolve("child"), "child");
		WorkspaceJournal journal = WorkspaceJournal.capture(root);

		Files.delete(originalFile);
		Files.createDirectory(originalFile);
		Files.writeString(originalFile.resolve("created"), "created");
		Files.delete(originalDirectory.resolve("child"));
		Files.delete(originalDirectory);
		Files.writeString(originalDirectory, "replacement");
		journal.restore();

		assertEquals("before", Files.readString(originalFile));
		assertTrue(Files.isDirectory(originalDirectory));
		assertEquals("child", Files.readString(originalDirectory.resolve("child")));
	}

	@Test void sourcePreparationSkipsReadyCaches() throws Exception {
		List<String> events = new ArrayList<>();
		GradleSourcePreparation.ensureReady(sourceActions(events, true, true));
		assertEquals(List.of("ready"), events);
	}

	@Test void sourcePreparationRepairsMissingCaches() throws Exception {
		List<String> events = new ArrayList<>();
		GradleSourcePreparation.ensureReady(sourceActions(events, false, true));
		assertEquals(List.of("missing", "prepare", "ready"), events);
	}

	@Test void sourcePreparationFailsWhenSourcesRemainMissing() {
		List<String> events = new ArrayList<>();
		IOException error = assertThrows(IOException.class,
				() -> GradleSourcePreparation.ensureReady(sourceActions(events, false, false)));
		assertEquals("game source attachments are unavailable after Gradle preparation", error.getMessage());
		assertEquals(List.of("missing", "prepare", "missing"), events);
	}

	@Test void journalLeavesLocalHistoryUnchanged() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("journal-history"));
		Path history = Files.createDirectories(root.resolve(".modstudio_ai/localHistory"));
		Path prior = Files.writeString(history.resolve("prior"), "before");
		WorkspaceJournal journal = WorkspaceJournal.capture(root);

		Files.writeString(prior, "after");
		Files.writeString(history.resolve("created"), "created");
		journal.restore();

		assertEquals("after", Files.readString(prior));
		assertEquals("created", Files.readString(history.resolve("created")));
	}

	private static GradleSourcePreparation.Actions sourceActions(List<String> events, boolean initiallyReady,
			boolean readyAfterPreparation) {
		return new GradleSourcePreparation.Actions() {
			private boolean prepared;

			@Override public boolean sourcesReady() {
				boolean ready = initiallyReady || prepared && readyAfterPreparation;
				events.add(ready ? "ready" : "missing");
				return ready;
			}

			@Override public void prepare() {
				events.add("prepare");
				prepared = true;
			}
		};
	}

	private static net.mcreator.element.GeneratableElement stubElement() {
		return new net.mcreator.element.GeneratableElement(null) {
		};
	}

	private static GenerationCoordinator.Actions actions(List<String> events, boolean elementResult,
			boolean baseResult) {
		return new GenerationCoordinator.Actions() {
			@Override public boolean generateElement(net.mcreator.element.GeneratableElement element) {
				events.add("element");
				return elementResult;
			}

			@Override public void verifyElement(net.mcreator.element.GeneratableElement element) {
				events.add("verify element");
			}

			@Override public boolean generateBase() {
				events.add("base");
				return baseResult;
			}

			@Override public void verifyBase() {
				events.add("verify base");
			}

			@Override public void save() {
				events.add("save");
			}

			@Override public void launch() {
				events.add("launch");
			}
		};
	}

	private static GenerationCoordinator.Actions failingActions(GenerationCoordinator.Actions delegate,
			String failureEvent) {
		return new GenerationCoordinator.Actions() {
			@Override public boolean generateElement(net.mcreator.element.GeneratableElement element) throws Exception {
				return delegate.generateElement(element);
			}

			@Override public void verifyElement(net.mcreator.element.GeneratableElement element) throws Exception {
				delegate.verifyElement(element);
				if (failureEvent.equals("verify element")) throw new IOException("element verification failed");
			}

			@Override public boolean generateBase() throws Exception {
				return delegate.generateBase();
			}

			@Override public void verifyBase() throws Exception {
				delegate.verifyBase();
				if (failureEvent.equals("verify base")) throw new IOException("base verification failed");
			}

			@Override public void save() throws Exception {
				delegate.save();
				if (failureEvent.equals("save")) throw new IOException("save failed");
			}

			@Override public void launch() throws Exception {
				delegate.launch();
			}
		};
	}

	private static void assertRequestError(String request, String expected) {
		assertEquals(expected, IPCServer.parseRequest(request).error());
	}

	private static JsonObject workspaceArgs(Path folder, String modid) {
		JsonObject args = new JsonObject();
		args.addProperty("folder", folder.toString());
		args.addProperty("modid", modid);
		return args;
	}

	private static void writeWorkspaceMarker(Path folder, String modid) throws IOException {
		Files.writeString(folder.resolve(modid + ".modstudio_ai"),
				"{\"workspaceSettings\":{\"modid\":\"" + modid + "\"}}");
	}
}
