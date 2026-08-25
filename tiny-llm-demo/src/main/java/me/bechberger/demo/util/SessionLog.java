package me.bechberger.demo.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Session transcripts - everything printed to System.out additionally lands in a log file,
 * like Claude Code's session logs. So every demo and every agent run leaves a trail:
 * {@code ~/.tiny-llm-library/sessions/20260826-101530-CodingAgent.log}
 */
public final class SessionLog {

    private SessionLog() {}

    public static Path dir() {
        return Path.of(System.getProperty("user.home"), ".tiny-llm-library", "sessions");
    }

    /** Install the tee and return the log file being written. */
    public static Path start(String agentName) throws IOException {
        Files.createDirectories(dir());
        var file = dir().resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + agentName + ".log");
        var original = System.out; // capture BEFORE swapping
        var fileOut = new PrintStream(Files.newOutputStream(file));
        System.setOut(new PrintStream(new OutputStream() {
            @Override public void write(int b) {
                original.write(b);
                fileOut.write(b);
            }
            @Override public void write(byte[] b, int off, int len) {
                original.write(b, off, len);
                fileOut.write(b, off, len);
            }
            @Override public void flush() {
                original.flush();
                fileOut.flush();
            }
        }, true));
        return file;
    }
}
