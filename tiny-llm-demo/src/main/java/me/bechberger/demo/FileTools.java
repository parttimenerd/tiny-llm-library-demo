package me.bechberger.demo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Sandboxed file tools for the LLM.
 * <p>
 * Read-only, sandboxed to a root directory, hidden files excluded, size-limited.
 * Tools are registered directly with ToolSupport (no annotations).
 */
public class FileTools {

    private final Path sandboxRoot;
    private static final int MAX_DIR_ENTRIES = 100;
    private static final int PAGE_SIZE_BYTES = 4096;
    private static final int MAX_FILE_SIZE_BYTES = 1024 * 1024; // 1MB
    private static final int MAX_MATCHES = 20;
    private static final int MAX_OUTPUT_BYTES = 8192;
    private static final int MAX_RESULTS = 50;
    private static final int MAX_FIND_OUTPUT_BYTES = 16384;
    private static final int MAX_COMMAND_OUTPUT_BYTES = 16384;
    private static final long COMMAND_TIMEOUT_SECONDS = 10;

    public FileTools(Path sandboxRoot) {
        this.sandboxRoot = sandboxRoot.toAbsolutePath().normalize();
    }

    // === ls: List directory contents ===

    /**
     * List directory contents (like Unix {@code ls}).
     * <p>
     * Security:
     * - Sandboxed to sandboxRoot (path traversal blocked)
     * - Hidden files/dirs (starting with '.') excluded
     * - Limited to first 100 entries
     * <p>
     * Output format: {@code filename} or {@code dirname/} (one per line, sorted)
     * <p>
     * Implementation: Validate path → check if directory → list files → filter hidden → sort → format names
     * @param path Relative path from sandbox root (e.g., ".", "src")
     * @return Newline-separated list of entries, or error message
     */
    public String ls(String path) {
        try {
            Path resolved = validatePath(path);
            if (!Files.isDirectory(resolved)) {
                return "Error: not a directory: " + path;
            }
            try (Stream<Path> entries = Files.list(resolved)) {
                var result = entries
                        .filter(p -> !isHidden(p))
                        .limit(MAX_DIR_ENTRIES)
                        .map(p -> {
                            String name = p.getFileName().toString();
                            return Files.isDirectory(p) ? name + "/" : name;
                        })
                        .sorted()
                        .collect(Collectors.joining("\n"));
                return result.isEmpty() ? "(empty directory)" : result;
            }
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error reading directory: " + e.getMessage();
        }
    }

    // === catPaged: Read file contents, paged ===

    /**
     * Read file contents with pagination (like Unix {@code cat} but paged).
     * <p>
     * Security:
     * - Sandboxed to sandboxRoot
     * - Max file size: 1MB
     * - Page size: 4KB
     * <p>
     * Output format: {@code === path (page X of Y) ===\n<content>}
     * <p>
     * Implementation: Validate path → check size → read all bytes → calculate pages → extract page slice
     * @param path Relative file path
     * @param page Zero-based page number
     * @return File content page with header, or error message
     */
    public String catPaged(String path, int page) {
        try {
            Path resolved = validatePath(path);
            if (!Files.isRegularFile(resolved)) {
                return "Error: not a regular file: " + path;
            }
            long fileSize = Files.size(resolved);
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return "Error: file too large (" + fileSize + " bytes, max " + MAX_FILE_SIZE_BYTES + ")";
            }

            byte[] content = Files.readAllBytes(resolved);
            int totalPages = (int) Math.ceil((double) content.length / PAGE_SIZE_BYTES);
            if (totalPages == 0) totalPages = 1;

            if (page < 0 || page >= totalPages) {
                return "Error: page " + page + " out of range (0 to " + (totalPages - 1) + ")";
            }

            int start = page * PAGE_SIZE_BYTES;
            int end = Math.min(start + PAGE_SIZE_BYTES, content.length);
            String pageContent = new String(content, start, end - start, StandardCharsets.UTF_8);

            return "=== " + path + " (page " + page + " of " + totalPages + ") ===\n" + pageContent;
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    // === Security: Validate and resolve path ===

    /**
     * Validate path and resolve it within the sandbox.
     * <p>
     * Security checks:
     * - Reject hidden path segments (starting with '.' except for "." itself)
     * - Resolve to absolute normalized path
     * - Check path is within sandboxRoot (blocks ../ traversal)
     * <p>
     * Implementation: Split and check segments → resolve from sandbox → verify with startsWith check
     * @param path Relative path string
     * @return Resolved Path object within sandbox
     * @throws SecurityException if path violates sandbox rules
     */
    Path validatePath(String path) {
        if (path == null || path.isBlank()) {
            path = ".";
        }

        // Reject any path segment starting with '.'
        for (String segment : path.split("[/\\\\]")) {
            if (segment.startsWith(".") && !segment.equals(".")) {
                throw new SecurityException("Access denied: hidden path segment '" + segment + "'");
            }
        }

        Path resolved = sandboxRoot.resolve(path).toAbsolutePath().normalize();

        // Check canonical path is within sandbox
        try {
            Path canonical = resolved.toRealPath();
            Path sandboxCanonical = sandboxRoot.toRealPath();
            if (!canonical.startsWith(sandboxCanonical)) {
                throw new SecurityException("Access denied: path escapes sandbox");
            }
            return canonical;
        } catch (IOException e) {
            // Path doesn't exist yet — check normalized path
            if (!resolved.startsWith(sandboxRoot)) {
                throw new SecurityException("Access denied: path escapes sandbox");
            }
            return resolved;
        }
    }

    /**
     * Search for text in a file (like Unix {@code grep}).
     */
    public String grep(String query, String path) {
        try {
            Path resolved = validatePath(path);
            if (!Files.isRegularFile(resolved)) {
                return "Error: not a regular file: " + path;
            }
            long fileSize = Files.size(resolved);
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return "Error: file too large (" + fileSize + " bytes, max " + MAX_FILE_SIZE_BYTES + ")";
            }

            var lines = Files.readAllLines(resolved);
            var matches = new ArrayList<String>();
            String queryLower = query.toLowerCase();
            int totalOutputBytes = 0;

            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).toLowerCase().contains(queryLower)) {
                    String match = (i + 1) + ": " + lines.get(i);
                    totalOutputBytes += match.length();
                    if (totalOutputBytes > MAX_OUTPUT_BYTES) {
                        matches.add("... (output truncated at " + MAX_OUTPUT_BYTES + " bytes)");
                        break;
                    }
                    matches.add(match);
                    if (matches.size() >= MAX_MATCHES) {
                        matches.add("... (showing first " + MAX_MATCHES + " matches)");
                        break;
                    }
                }
            }

            if (matches.isEmpty()) {
                return "No matches found for '" + query + "' in " + path;
            }
            return "=== grep '" + query + "' in " + path + " ===\n" + String.join("\n", matches);
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    /**
     * Find all files containing text matching the query.
     */
    public String findFiles(String query) {
        return findFiles(query, false);
    }

    /**
     * Find all files containing text/regex matching the query.
     */
    public String findFiles(String query, boolean useRegex) {
        try {
            Pattern pattern = null;
            if (useRegex) {
                try {
                    pattern = Pattern.compile(query);
                } catch (PatternSyntaxException e) {
                    return "Error: invalid regex: " + e.getMessage();
                }
            }

            var matches = new ArrayList<String>();
            Pattern finalPattern = pattern;

            Files.walk(sandboxRoot)
                    .filter(Files::isRegularFile)
                    .filter(this::isIncludedFile)
                    .forEach(filePath -> {
                        if (matches.size() >= MAX_RESULTS) return;
                        try {
                            String content = Files.readString(filePath);
                            boolean matchesQuery;

                            if (finalPattern != null) {
                                matchesQuery = finalPattern.matcher(content).find();
                            } else {
                                matchesQuery = content.toLowerCase().contains(query.toLowerCase());
                            }

                            if (matchesQuery) {
                                String relativePath = sandboxRoot.relativize(filePath).toString();
                                matches.add(relativePath);
                            }
                        } catch (Exception ignored) {
                        }
                    });

            if (matches.isEmpty()) {
                return "No files found containing '" + query + "'";
            }

            String result = String.join("\n", matches);
            if (result.length() > MAX_FIND_OUTPUT_BYTES) {
                result = result.substring(0, MAX_FIND_OUTPUT_BYTES) + "\n... (truncated at " + MAX_FIND_OUTPUT_BYTES + " bytes)";
            }
            return result;
        } catch (IOException e) {
            return "Error searching files: " + e.getMessage();
        }
    }

    /**
     * Run an arbitrary bash command and return its output.
     * <p>
     * Security:
     * - Prompts user for confirmation before execution (y/n via stdin)
     * - Timeout: 10 seconds
     * - Max output: 16KB
     * - Runs in sandbox root directory
     * <p>
     * Implementation: prompt user → validate response → execute in ProcessBuilder → capture output → truncate if needed
     * @param command Bash command to run (e.g., "find . -name '*.java'")
     * @return Command output (truncated if > 16KB), or error/cancellation message
     */
    /** Timeout for commands run without confirmation via {@link #run(String)} — long enough for builds. */
    private static final long EXEC_TIMEOUT_SECONDS = 60;

    /**
     * Run a bash command in the sandbox root and return its output — no confirmation asked.
     * <p>
     * Intended for autonomous agents to build, test and execute artifacts, e.g.
     * {@code mvn -q package}, {@code java -jar target/app.jar '1+2'}.
     * Timeout: 60 seconds. Max output: 16KB.
     *
     * @param command Bash command to run
     * @return "Exit N:\n" + command output, or error/timeout message
     */
    public String run(String command) {
        return exec(List.of("bash", "-c", command), EXEC_TIMEOUT_SECONDS);
    }

    public String runCommand(String command) {
        // Prompt user for confirmation
        System.out.print("\n⚠️  Run command: " + command + "\n    Confirm? (y/n): ");
        System.out.flush();

        Scanner scanner = new Scanner(System.in);
        String response = scanner.nextLine().trim().toLowerCase();

        if (!response.equals("y") && !response.equals("yes")) {
            return "Command cancelled by user.";
        }
        return exec(List.of("bash", "-c", command), COMMAND_TIMEOUT_SECONDS);
    }

    /**
     * Shared process runner: executes in the sandbox root, merges stdout/stderr
     * (truncated at 16KB), kills after the timeout. Used by run/runMaven/runCommand.
     */
    private String exec(List<String> cmd, long timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(sandboxRoot.toFile());
            pb.redirectErrorStream(true); // Merge stderr with stdout

            Process process = pb.start();

            // Read output concurrently to prevent buffer deadlock
            StringBuilder output = new StringBuilder();
            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            if (output.length() < MAX_COMMAND_OUTPUT_BYTES) {
                                output.append(line).append("\n");
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            });
            outputReader.start();

            boolean completed = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                outputReader.interrupt();
                return "Error: command timed out after " + timeoutSeconds + " seconds";
            }
            outputReader.join(1000);

            String result;
            synchronized (output) {
                result = output.toString().trim();
                if (output.length() >= MAX_COMMAND_OUTPUT_BYTES) {
                    result += "\n... (output truncated at " + MAX_COMMAND_OUTPUT_BYTES + " bytes)";
                }
            }
            return "Exit " + process.exitValue() + ":\n" + (result.isEmpty() ? "(no output)" : result);
        } catch (Exception e) {
            return "Error running " + String.join(" ", cmd) + ": " + e.getMessage();
        }
    }

    private boolean isIncludedFile(Path filePath) {
        String fileName = filePath.getFileName().toString();

        if (fileName.startsWith(".")) return false;

        String pathStr = filePath.toString();
        if (pathStr.contains("/.git/") || pathStr.contains("/target/") ||
                pathStr.contains("/node_modules/") || pathStr.contains("/.gradle/") ||
                pathStr.contains("/build/") || pathStr.contains("/dist/")) {
            return false;
        }

        String[] binaryExtensions = {".jar", ".class", ".so", ".dylib", ".dll", ".o", ".exe", ".bin", ".png", ".jpg", ".jpeg", ".gif", ".zip", ".tar", ".gz"};
        for (String ext : binaryExtensions) {
            if (fileName.endsWith(ext)) return false;
        }

        return true;
    }

    private boolean isHidden(Path p) {
        String name = p.getFileName().toString();
        return name.startsWith(".");
    }

    // === Write tools ===

    /**
     * Write content to a file (creates or overwrites), sandboxed to root.
     */
    public String writeFile(String path, String content) {
        try {
            Path resolved = validatePath(path);
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content, StandardCharsets.UTF_8);
            return "Written: " + path + " (" + content.length() + " chars)";
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    /**
     * Create a new file with content, sandboxed to root.
     * Fails if the file already exists — use {@link #writeFile} to overwrite.
     */
    public String createFile(String path, String content) {
        try {
            Path resolved = validatePath(path);
            if (Files.exists(resolved)) {
                return "Error: file already exists (use write-file to overwrite): " + path;
            }
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content, StandardCharsets.UTF_8);
            return "Created file: " + path + " (" + content.length() + " chars)";
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error creating file: " + e.getMessage();
        }
    }

    /**
     * Create a folder (and any missing parents), sandboxed to root.
     */
    public String createFolder(String path) {
        try {
            Path resolved = validatePath(path);
            Files.createDirectories(resolved);
            return "Created folder: " + path;
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error creating folder: " + e.getMessage();
        }
    }

    /**
     * Delete a file or folder, sandboxed to root.
     * Folders are deleted recursively (all contained files and subfolders).
     * Refuses to delete the sandbox root itself.
     */
    public String delete(String path) {
        try {
            Path resolved = validatePath(path);
            if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
                return "Error: no such file or folder: " + path;
            }
            if (Files.isSameFile(resolved, sandboxRoot)) {
                return "Error: cannot delete sandbox root";
            }
            if (Files.isDirectory(resolved)) {
                // Materialize the tree first, then delete children before parents.
                // Symlinks are never followed (the link itself is deleted).
                try (Stream<Path> walk = Files.walk(resolved)) {
                    List<Path> entries = walk.sorted(Comparator.reverseOrder()).toList();
                    for (Path entry : entries) {
                        Files.delete(entry);
                    }
                }
                return "Deleted folder (and all contents): " + path;
            }
            Files.delete(resolved);
            return "Deleted: " + path;
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error deleting: " + e.getMessage();
        }
    }

    /**
     * Run a Maven command in the sandbox root and return its output.
     * Output is truncated at 16KB. Timeout: 60 seconds.
     */
    public String runMaven(String args) {
        var cmd = new ArrayList<String>();
        cmd.add("mvn");
        cmd.addAll(java.util.Arrays.asList(args.strip().split("\\s+")));
        return exec(cmd, EXEC_TIMEOUT_SECONDS);
    }
}