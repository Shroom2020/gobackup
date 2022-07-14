// Apologies to anyone reading this code. I didn't properly plan ahead, so the code may reference 'initialDelay' in weird ways.
// The way it was planned at first, initialDelay was supposed to be set in the initialisation function, but it turned out this was a horrible method that turned out to completely skew the initial delay.
// If you find references like this, and you'd like to help out, please change them to something more accurate with how it is currently implemented.

package me.shroom.gobackup;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.zip.ZipUtil;

import java.io.File;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.util.*;

public class Main implements ModInitializer {

	// Load config 'config.properties', if it isn't present create one
	// using the lambda specified as the provider.
	SimpleConfig config = SimpleConfig.of( "goconfig" ).provider( this::provider ).request();

	// Custom config provider, returns the default config content
	// if the custom provider is not specified SimpleConfig will create an empty file instead
	private String provider( String filename ) {
		return "# Configuration file for GoBackup.\n# Descriptions of each field can be found at https://github.com/Shroom2020/gobackup/README.md.\n\n# Backend details\ngofile-api-key=<your-api-key>\ngofile-container-folder=<your-folder-id>\n\n# Functionality\nbackup-type=world\nallow-manual=true\nannounce-backup=true\n\n# Scheduling\ndo-schedule=true\ninterval=3600\nbackup-limit=24\n\n# Console\nshow-debug-messages=false\nshow-progress-messages=true";
	}

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote GoBackup, warnings, and errors.
	public static Logger log = LoggerFactory.getLogger("GoBackup");

	// GSON is used to parse responses from the GoFile API.
	private final Gson gson = new Gson();

	// IDs for the GoFile containers.
	private String automaticFolder = "";
	String manualFolder = "";

	// The MinecraftServer has fields such as the worlds. It's useful.
	private MinecraftServer server;

	// The backups are scheduled using a Timer. It's important that it's kept in scope so that it can be stopped.
	private final Timer timer = new Timer();

	// The GoFile stuff
	private final String GOFILE_API_KEY = config.getOrDefault("gofile-api-key", "<your-api-key>");
	private final String GOFILE_CONTAINER_FOLDER = config.getOrDefault("gofile-container-folder", "<your-folder-id>");

	// Stuff defined in the config
	private int backupLimit, interval;

	// This will be changed if there's an initial delay. If there isn't, it'll exceed the interval unless the user set it to longer than time as computery knows it has existed.
	private long latestBackupTime = 0;

	// Used to format the date for the backup file name.
	SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

	// The CommandDispatcher is used to register and handle commands.


	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		ServerLifecycleEvents.SERVER_STOPPING.register((server) -> timer.cancel());
		sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

		// First thing: Check if the gofile details provided are valid.
		// This can be done by checking the contents of the folder provided in the config, using the API key. It tests both values.
		// The contents are url encoded, taking a `token` and `contentId` as parameters.
		log.info("Checking GoFile API details...");
		// Check if the values are defaults.
		if (GOFILE_API_KEY.equals("<your-api-key>") || GOFILE_CONTAINER_FOLDER.equals("<your-folder-id>")) {
			log.error("GoFile API details are not configured yet. Please configure them in the config file.");
			log.error("Your server is not being backed up.");
			return;
		}

		// Now, submit the request in case the details are there but invalid.
		HttpResponse<String> response = HttpMethods.fetch("https://api.gofile.io/getContent?contentId=" + GOFILE_CONTAINER_FOLDER + "&token=" + GOFILE_API_KEY, "GET", "");
		assert response != null;

		// The response can have a status code of 200 even if the request failed in the case that the folder is not found.
		// Instead of checking the status code, the response body can be checked.
		// The response body contains a JSON object with a "status" field that will be "ok" if successful and contain error information if not.
		// First, the body needs to be parsed into a JSON object.

		JsonObject json = gson.fromJson(response.body(), JsonObject.class);
		assert json != null;
		if (!json.get("status").getAsString().equals("ok")) {
			log.error("GoFile API details are invalid. Please check them in the config file.");
			log.error("Your server is not being backed up.");
			return;
		}

		// Now that that's been checked, we need to find the 'automatic' and 'manual' folders (depending on what's configured).
		// We already checked the container folder in the request above, so all we need to do is searched through the response body.
		// The response body is formatted like this:
		// {
		//  ...
		//  "data: {
		//  	...
		// 		"contents": {
		//  		...
		// 			"id": {
		// 				name: "automatic",
		// 				type: "folder",
		// 				...
		// 			},
		///			...
		// 		}
		// 	}
		// }

		// We need to get the contents of the main folder so that the rest of the code can loop through it.
		JsonObject contents = json.get("data").getAsJsonObject().get("contents").getAsJsonObject();

		for (String key : contents.keySet()) {
			JsonObject folder = contents.get(key).getAsJsonObject();
			if (folder.get("name").getAsString().equals("automatic") && folder.get("type").getAsString().equals("folder")) {
				infoIfProgressEnabled("Found automatic backup folder.");
				automaticFolder = key;
			}
			if (folder.get("name").getAsString().equals("manual") && folder.get("type").getAsString().equals("folder")) {
				infoIfProgressEnabled("Found manual backup folder.");
				manualFolder = key;
			}
			if (!manualFolder.equals("") && !automaticFolder.equals("")) {
				break;
			}
		}

		for (int i = 0; i < 2; i++) {
			String folder = i == 0 ? automaticFolder : manualFolder;
			if (folder.equals("")) {
				String folderName = i == 0 ? "Automatic" : "Manual";
				infoIfProgressEnabled(folderName + " backup folder doesn't exist. Creating...");
				HttpResponse<String> folderDetails = HttpMethods.fetch("https://api.gofile.io/createFolder", "PUT", "token=" + GOFILE_API_KEY + "&parentFolderId=" + GOFILE_CONTAINER_FOLDER + "&folderName=" + folderName.toLowerCase());
				assert folderDetails != null;

				JsonObject folderCreationResponse = gson.fromJson(folderDetails.body(), JsonObject.class);
				assert folderCreationResponse != null;
				if (!folderCreationResponse.get("status").getAsString().equals("ok")) {
					log.error("Could not create " + folderName.toLowerCase() + " backup folder. Please check your GoFile API details in the config file.");
					log.error("Your server is not being backed up.");
					return;
				}
				// The folder was created, so we can get the ID of the folder.
				if (i == 0) automaticFolder = folderCreationResponse.get("data").getAsJsonObject().get("id").getAsString();
				else manualFolder = folderCreationResponse.get("data").getAsJsonObject().get("id").getAsString();
				infoIfProgressEnabled("Automatic backup folder created!");
			}
		}

		CommandRegistrationCallback.EVENT.register((dispatcher, ignore) -> GoBackupCommand.register(dispatcher, this, ignore, config.getOrDefault("allow-manual", true)));

		// Now that that's been checked, the schedule can be started if it's been enabled.
		log.info("GoFile API details are valid!");
		if (config.getOrDefault("do-schedule", false)) {
			log.info("Attempting to start backup schedule...");

			// Check to make sure interval and backup-limit are there
			// This can be done by making sure they're integers, and using a default of a string if they don't exist
			if (!config.getOrDefault("interval", "t").matches("-?\\d+") || !config.getOrDefault("backup-limit", "t").matches("-?\\d+")) {
				log.error("Interval and backup limit must be valid numbers. Please check them in the config file.");
				log.error("Your server is not being backed up.");
				return;
			}

			// Set a backup limit and interval variable so that we don't have to keep chasing our numbers
			backupLimit = Integer.parseInt(config.getOrDefault("backup-limit", "24"));
			interval = Integer.parseInt(config.getOrDefault("interval", "24"));

			// Now, we need to loop through the contents object and find the 'automatic' folder. If it doesn't exist, it needs to be created.

			// If the automatic folder doesn't exist, we need to create it.


			// Now, we want to check the automatic folder if there are too many backups. If there are, we need to delete the oldest backups in order until the number is at the limit.
			// We need to get the contents of the automatic folder for this.
			HttpResponse<String> automaticResponse = HttpMethods.fetch("https://api.gofile.io/getContent?contentId=" + automaticFolder + "&token=" + GOFILE_API_KEY, "GET", "");
			assert automaticResponse != null;
			JsonObject automaticResponseJson = gson.fromJson(automaticResponse.body(), JsonObject.class);
			assert automaticResponseJson != null;

			ServerLifecycleEvents.SERVER_STARTED.register(this::schedule);

			// If the automatic folder is empty, we can set the first backup delay to 0.
			int children = automaticResponseJson.get("data").getAsJsonObject().get("childs").getAsJsonArray().size();
			if (children == 0) {
				log.info("There are no backups yet! One will be scheduled for when the server has started.");
				return;
			}

			// The folder isn't empty, so we need to check if there are too many backups.
			if (children > backupLimit) {
				log.info("There are too many backups in your automatic folder! Deleting old backups...");
				deleteBackupsOverLimit(automaticResponseJson, backupLimit, children);
			}

			// Now, we need to know what to set the initialDelay to.
			// We need to get the latest backup's timestamp.
			// To get the latest backup's timestamp, we need to loop through the contents object and find the file with the largest timestamp.
			JsonObject automaticContentsJson = automaticResponseJson.get("data").getAsJsonObject().get("contents").getAsJsonObject();
			for (String key : automaticContentsJson.keySet()) {
				JsonObject file = automaticContentsJson.get(key).getAsJsonObject();
				if (file.get("type").getAsString().equals("file")) {
					if (file.get("createTime").getAsLong() > latestBackupTime) {
						latestBackupTime = file.get("createTime").getAsLong();
					}
				}
			}
		}

	}

	private void deleteBackupsOverLimit(JsonObject automaticContentsResponse, int backupLimit, int children) {
		// Using the value in automaticResponseJson.data.contents, we can order the backups by their creation date and delete all the ones that go over the limit.
		// This will need a foreach loop.
		JsonObject automaticContentsJson = automaticContentsResponse.get("data").getAsJsonObject().get("contents").getAsJsonObject();

		// First, let's make an ArrayList that will contain the creation times of the files to delete.
		ArrayList<Long> creationTimes = new ArrayList<>();

		for (String key : automaticContentsJson.keySet()) {
			JsonObject backup = automaticContentsJson.get(key).getAsJsonObject();
			if (backup.get("type").getAsString().equals("file")) {
				// The creation time for this file needs to be added to the ArrayList.
				creationTimes.add(backup.get("createTime").getAsLong());
			}
		}

		// Now, we need to sort the ArrayList.
		Collections.sort(creationTimes);

		// The smallest values in the ArrayList are the oldest backups. We need to shorten it down until we only have the backups that have gone over the limit.
		List<Long> toDeleteList = creationTimes.stream().limit(children - backupLimit).toList();

		// Now that we have toDeleteList, we can now create and expand a (percent-encoded) comma-separated string of the IDs of the backups to delete.
		StringBuilder idListBuilder = new StringBuilder();
		for (String key : automaticContentsJson.keySet()) {
			JsonObject backup = automaticContentsJson.get(key).getAsJsonObject();
			if (backup.get("type").getAsString().equals("file")) {
				// If the backup's creation time is in the toDeleteList, we need to delete it.
				if (toDeleteList.contains(backup.get("createTime").getAsLong())) {
					idListBuilder.append(backup.get("id").getAsString()).append("%2C");
				}
			}
		}

		// We need to remove the last comma from the string - it does work with the comma in practice but this can change in the future, so it's better to just pass an already valid input to the API
		String idsToDelete = idListBuilder.substring(0, idListBuilder.toString().length() - 3);

		// And now, after all that faffing, we can finally delete the backups.
		log.info("Deleting backups that go over the defined limit...");
		HttpResponse<String> deleteResponse = HttpMethods.fetch("https://api.gofile.io/deleteContent", "DELETE", "contentsId=" + idsToDelete + "&token=" + GOFILE_API_KEY);
		assert deleteResponse != null;
		log.info(deleteResponse.body());
	}

	private void schedule(MinecraftServer server) {
		this.server = server;

		// Start the timer.
		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				runBackup(automaticFolder, true, "");
			}
		}, interval - (System.currentTimeMillis() / 1000 - latestBackupTime) * 1000 > 0 ? interval - (System.currentTimeMillis() / 1000 - latestBackupTime) * 1000 : 0, interval * 1000L);
	}

	protected String runBackup(String destinationFolder, boolean doLimitCheck, String fileName) {
		try {
			// This method is called when it's time to do a backup.
			// First, it needs to run /save-off and /save-all.
			// This is done by replicating the behaviour of the commands as seen in the classes for them.
			// Extra advantage of doing this instead of plugging the command into an event is that it won't scream at the user if autosaving is already disabled.
			infoIfProgressEnabled("Starting a server backup...");

			// Before any of the stuff below, we need to check if the lock file exists.
			// If it does, we need to log that backups are disabled and return.
			try {
				if (new File(".gobackup-disable.lock").exists()) {
					log.warn("Backups are disabled! Skipping backup.");
					return "Backups are disabled!";
				}
			} catch (SecurityException e) {
				log.error("Could not check if the lock file exists! Skipping backup.");
				errorIfDebugEnabled(e.getMessage());
				return "Could not check if backups are disabled because of server permissions! Skipping backup.";
			}

			for (ServerWorld serverWorld : server.getWorlds()) {
				if (serverWorld != null && !serverWorld.savingDisabled) serverWorld.savingDisabled = true;
			}
			server.saveAll(true, true, true);
			infoIfProgressEnabled("Disabled autosaving and saved all current changes.");

			// Now, we need to determine what the name of the world folder is.
			String worldFolder = server.getSaveProperties().getLevelName();

			// And now, we need to compress the world folder.
			infoIfProgressEnabled("Compressing world folder...");
			try {
				// The result of mkdir() should be ignored. If it fails, the folder is probably just already there.
				if (new File("backup-send-intermediary/").mkdir()) {
					infoIfProgressEnabled("Created intermediary folder.");
				} else {
					infoIfProgressEnabled("Intermediary folder already exists.");
				}
			} catch (SecurityException e) {
				log.error("The required intermediary folder could not be created because your file permissions are too restrictive. Please change your file permissions to allow the server to create folders in the parent folder.");
				log.error("If you need assistance with this, contact your server administrator or hosting provider and quote this error message.");
				errorIfDebugEnabled("Here's the stack trace:\n" + e.getMessage());
				return "File permissions error";
			}

			// Calling it formattedDate is a slightly patchworky way to go about it now, but more often than not, it will be a date anyway.
			String formattedDate = fileName.equals("") ? sdf.format(new Date()) : fileName;

			ZipUtil.pack(new File(worldFolder), new File("backup-send-intermediary/" + formattedDate + ".zip"));
			infoIfProgressEnabled("Compressed world folder.");

			boolean uploadSuccessful = false;
			int failedAttempts = 0;
			do {
				try {
					// Now, we need to get the GoFile server with the best conditions and load.
					// We can do this by calling api.gofile.io/getServer and reading data.server.
					infoIfProgressEnabled("Fetching the optimal server to upload to...");
					HttpResponse<String> getServerResponse = HttpMethods.fetch("https://api.gofile.io/getServer", "GET", "");
					assert getServerResponse != null;
					infoIfProgressEnabled("Found optimal server. Uploading backup - this may take a while...");
					HttpResponse<String> uploadResponse = HttpMethods.multipartGoFileUpload("backup-send-intermediary/" + formattedDate + ".zip", GOFILE_API_KEY, destinationFolder, gson.fromJson(getServerResponse.body(), JsonObject.class).get("data").getAsJsonObject().get("server").getAsString());
					assert uploadResponse != null;

					// Check if the backup was successful.
					if (gson.fromJson(uploadResponse.body(), JsonObject.class).get("status").getAsString().equals("ok")) {
						infoIfProgressEnabled("Successfully backed up! The file can be found in your GoFile account as " + formattedDate + ".zip.");
						uploadSuccessful = true;
					} else {
						log.error("A backup failed. This is most likely due to your configuration being incorrect. Read the error below for more information:");
						errorIfDebugEnabled("Here's the response for the upload:\n" + uploadResponse.body());
						failedAttempts++;
					}
				} catch (Exception e) {
					// This isn't actually much of an error and just happens sometimes, but it's still worth logging it in case it's happening more often.
					log.error("An error occurred while uploading the backup. This may be caused because the chosen GoFile server is unavailable.");
					errorIfDebugEnabled("Here's the stack trace:\n" + e.getMessage());
					failedAttempts++;
				}
				if (failedAttempts == 3) {
					log.error("The backup failed three times. Depending on the type of error, this may indicate downtime, ratelimiting, a misconfiguration, or a bug earlier in the process.");
					break;
				}
			} while (!uploadSuccessful);

			// Turn autosaving back on.
			infoIfProgressEnabled("Enabling autosaving again now that backup is complete...");
			for (ServerWorld serverWorld : server.getWorlds()) {
				if (serverWorld != null && serverWorld.savingDisabled) serverWorld.savingDisabled = false;
			}
			infoIfProgressEnabled("Autosaving is enabled again!");

			// Now, we need to delete the intermediary file here.
			log.info("Deleting local intermediary file...");
			try {
				if (new File("backup-send-intermediary/" + formattedDate + ".zip").delete())
					infoIfProgressEnabled("Deleted local intermediary file!");
				else
					log.error("Could not delete local intermediary file.");
			} catch (SecurityException e) {
				log.error("The intermediary file could not be deleted because your file permissions are too restrictive. Please change your file permissions to allow the server to delete files in the parent folder and its children.");
				log.error("If you need assistance with this, contact your server administrator or hosting provider and quote this error message.");
				errorIfDebugEnabled("Here's the stack trace:\n" + e);
			}

			if (doLimitCheck) {
				// And finally, we need to check if there are too many backups.
				// If there are, we need to delete the oldest ones until we're under the limit using deleteBackupsOverLimit().
				infoIfProgressEnabled("Checking if there are too many backups...");
				HttpResponse<String> getBackupsResponse = HttpMethods.fetch("https://api.gofile.io/getContent?token=" + GOFILE_API_KEY + "&contentId=" + destinationFolder, "GET", "");
				assert getBackupsResponse != null;
				log.info(getBackupsResponse.body());
				JsonObject getBackupsResponseJson = gson.fromJson(getBackupsResponse.body(), JsonObject.class);
				assert getBackupsResponseJson != null;

				// Make sure the response is ok
				if (!getBackupsResponseJson.get("status").getAsString().equals("ok")) {
					log.error("Failed to get existing backup list. This is most likely due to your configuration being incorrect.");
					errorIfDebugEnabled("Here's the response for the backup check:\n" + getBackupsResponse.body());
					return formattedDate;
				}

				// Now that it's known ok, do the trickery with the array of ids
				int children = getBackupsResponseJson.get("data").getAsJsonObject().get("childs").getAsJsonArray().size();
				if (children > backupLimit) {
					infoIfProgressEnabled("There are too many backups.");
					deleteBackupsOverLimit(gson.fromJson(getBackupsResponse.body(), JsonObject.class), backupLimit, children);
				} else {
					infoIfProgressEnabled("There aren't too many backups yet.");
				}
			}

			return uploadSuccessful ? formattedDate : "Upload failed 3 times. See log for more information.";
		} catch (Exception e) {
			log.error("An unknown error occurred while backing up. This may be for any number of reasons, including an incorrect configuration that passed initial checks, and GoFile downtime.");
			errorIfDebugEnabled("Here's the stack trace for the error:\n" + e);
			log.error("This error shouldn't interfere with other backups, but it might if it happens often or each time. If you're getting this error often, please contact your server administrator or hosting provider and quote this error message along with your configuration.");
			return "Unknown error";
		}
	}

	protected void infoIfProgressEnabled(String message) {
		if (config.getOrDefault("show-progress-messages", true)) log.info(message);
	}
	protected void errorIfDebugEnabled(String message) {
		if (config.getOrDefault("show-debug-messages", false)) log.error(message);
	}
}
