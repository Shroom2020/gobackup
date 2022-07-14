package me.shroom.gobackup;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.LiteralText;

public class MultithreadedUpload extends Thread {
    private final Main main;
    private final ServerCommandSource source;
    private final String fileName;
    public MultithreadedUpload(Main main, ServerCommandSource source, String fileName) {
        this.main = main;
        this.source = source;
        this.fileName = fileName;
    }
    @Override public void run() {
        source.sendFeedback(new LiteralText("Starting backup..."), true);
        String result = main.runBackup(main.manualFolder, false, fileName);
        source.sendFeedback(new LiteralText(!result.toLowerCase().equals(fileName) ? result : "Backup complete and saved as manual/" + result + ".zip."), true);
    }
}
