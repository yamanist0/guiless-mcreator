package net.mcreator.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.mcreator.io.FileIO;
import net.mcreator.io.OS;
import net.mcreator.workspace.Workspace;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

public class BedrockUtils {

	private static final Logger LOG = LogManager.getLogger(BedrockUtils.class);

	public static final String MC_PROCESS = "Minecraft.Windows.exe";

	private static void loadPackAndRun(File bpdev, File rpdev, Workspace workspace) throws Exception {
		FileIO.copyDirectory(workspace.getGenerator().getSourceRoot(),
				new File(bpdev, workspace.getWorkspaceSettings().getModID()));
		FileIO.copyDirectory(workspace.getGenerator().getResourceRoot(),
				new File(rpdev, workspace.getWorkspaceSettings().getModID()));
		Runtime.getRuntime().exec(new String[] { "cmd.exe", "/c", "start", "minecraft://" });
	}

	private static boolean detectAndDeletePack(File bpacksdir, String uuid) {
		try {
			File[] packs = bpacksdir.listFiles();
			for (File pack : packs != null ? packs : new File[0]) {
				File manifest = new File(pack, "manifest.json");
				if (manifest.isFile()) {
					JsonElement jelement = JsonParser.parseString(FileIO.readFileToString(manifest));
					String packUUID = jelement.getAsJsonObject().get("header").getAsJsonObject().get("uuid")
							.getAsString();
					if (packUUID.equalsIgnoreCase(uuid)) {
						FileIO.deleteDir(pack);
						return true;
					}
				}
			}
		} catch (Exception e) {
			LOG.warn("Failed to properly delete existing pack", e);
		}

		return false;
	}

}
