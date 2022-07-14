package me.shroom.gobackup;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.LiteralText;

import java.io.File;
import java.io.IOException;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class GoBackupCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, Main mainInstance, boolean ignore, boolean allowManual) {
        dispatcher.register(CommandManager.literal("gobackup")
                .executes(GoBackupCommand::info)
                .then(CommandManager.literal("now")
                        .requires(source -> source.hasPermissionLevel(2))
                        .requires(source -> allowManual)
                        .then(CommandManager.argument("filename", StringArgumentType.string())
                                .executes(context -> {
                                    // Same thing as what happens below, just with a set filename.
                                    // The regex is to make sure the filename doesn't have special characters. GoFile doesn't put many restrictions on the filename, but it's better to idiotproof just in case.
                                    new MultithreadedUpload(mainInstance, context.getSource(), StringArgumentType.getString(context, "filename").replaceAll("[^\\w\\s]","")).start();
                                    return 1;
                                })
                        )
                        .executes(context -> {
                            // gobackup now is practically just a call to Main.backup() except it has some feedback for the player.
                            // A thread is used here so that the server doesn't freeze for the whole time the backup is running.
                            // Not only does that cause noticeable lag, but if it takes too long, it will make the server crash under the assumption that something went wrong.
                            new MultithreadedUpload(mainInstance, context.getSource(), "").start();
                            return 1;
                        })
                )
                .then(CommandManager.literal("disable")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> {
                            // This command creates an empty lock file (.gobackup-disable.lock).
                            try {
                                new File(".gobackup-disable.lock").createNewFile();
                            } catch (SecurityException e) {
                                context.getSource().sendError(new LiteralText("Failed to create lock file! Please check your permissions."));
                                context.getSource().sendError(new LiteralText("GoBackup could not be disabled."));
                                mainInstance.errorIfDebugEnabled(e.getMessage());
                                return -1;
                            } catch (IOException e) {
                                context.getSource().sendError(new LiteralText("Failed to create lock file! There was an unknown error."));
                                context.getSource().sendError(new LiteralText("GoBackup could not be disabled."));
                                mainInstance.errorIfDebugEnabled(e.getMessage());
                                return -1;
                            }
                            context.getSource().sendFeedback(new LiteralText("Backups disabled."), true);
                            return 1;
                        })
                )
                .then(CommandManager.literal("enable")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> {
                            // This command deletes the lock file (.gobackup-disable.lock).
                            try {
                                new File(".gobackup-disable.lock").delete();
                            } catch (SecurityException e) {
                                context.getSource().sendError(new LiteralText("Failed to delete lock file! Please check your permissions."));
                                context.getSource().sendError(new LiteralText("GoBackup could not be disabled."));
                                mainInstance.errorIfDebugEnabled(e.getMessage());
                                return -1;
                            }
                            context.getSource().sendFeedback(new LiteralText("Backups enabled."), true);
                            return 1;
                        })
                )
        );
    }

    public static int info(CommandContext<ServerCommandSource> context) {
        context.getSource().sendFeedback(new LiteralText("Server is running GoBackup.\nCommands:\n/gobackup now - Backs up to manual folder if enabled.\n/gobackup enable - Enables backups, both automatic and manual.\n/gobackup disable - Disables GoBackup."), false);
        return 1;
    }
}
