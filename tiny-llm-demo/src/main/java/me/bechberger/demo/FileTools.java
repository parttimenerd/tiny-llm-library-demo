package me.bechberger.demo;

import me.bechberger.util.femtoschema.Schemas;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private static final int MAX_FILE_SIZE_BYTES = 1024 * 1024; // 1MB
    private static final int MAX_MATCHES = 20;
    private static final int MAX_OUTPUT_BYTES = 8192;
    private static final int MAX_RESULTS = 50;
    private static final int MAX_FIND_OUTPUT_BYTES = 16384;
    private static final int MAX_COMMAND_OUTPUT_BYTES = 16384;
    private static final long COMMAND_TIMEOUT_SECONDS = 10;
    private static final long EXEC_TIMEOUT_SECONDS = 120;
    private static final String[] BINARY_EXTENSIONS = {
        ".jar", ".class", ".so", ".dylib", ".dll", ".o", ".exe", ".bin",
        ".png", ".jpg", ".jpeg", ".gif", ".zip", ".tar", ".gz"
    };

    public FileTools(Path sandboxRoot) {
        this.sandboxRoot = sandboxRoot.toAbsolutePath().normalize();
    }

    // === ls: List directory contents ===

    /**
     * List directory contents (like Unix {@code ls}).
     * Hidden files/dirs excluded, sandboxed, limited to {@value #MAX_DIR_ENTRIES} entries.
     * Output: {@code filename} or {@code dirname/} per line, sorted.
     *
     * @param path Relative path from sandbox root (e.g. ".", "src")
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

    // === readFile: Read file contents with optional line range ===

    public String readFile(String path) {
        return readFile(path, -1, -1);
    }

    /**
     * Read a file, optionally restricted to [startLine, endLine] (1-based, inclusive).
     * Pass -1/-1 to read the whole file (capped at 20 000 chars).
     * When a range is given the cap applies to the extracted slice.
     * The header line reports total line count so the caller knows if more pages exist.
     */
    public String readFile(String path, int startLine, int endLine) {
        try {
            Path resolved = validatePath(path);
            if (!Files.isRegularFile(resolved)) {
                return "Error: not a regular file: " + path
                        + " (use paths relative to sandbox root " + sandboxRoot + ", e.g. README.md)";
            }
            List<String> allLines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
            int totalLines = allLines.size();

            boolean ranged = startLine > 0 || endLine > 0;
            int from = ranged ? Math.max(1, startLine) : 1;
            int to   = ranged ? (endLine > 0 ? Math.min(endLine, totalLines) : totalLines) : totalLines;

            List<String> slice = allLines.subList(from - 1, to);
            String text = String.join("\n", slice);
            String header = path + " (lines " + from + "–" + to + " of " + totalLines + "):\n";

            if (text.length() > 20_000) {
                // Hard-truncate but tell the model exactly where it stopped
                String truncated = text.substring(0, 20_000);
                int lastNewline = truncated.lastIndexOf('\n');
                int lastFullLine = from + (int) truncated.substring(0, lastNewline < 0 ? 0 : lastNewline)
                        .chars().filter(c -> c == '\n').count();
                return header + truncated + "\n... (truncated at 20 000 chars; shown lines "
                        + from + "–" + lastFullLine + " of " + totalLines
                        + " — use start_line/end_line to read later sections)";
            }
            return header + text;
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    // === tree: Recursive directory listing ===

    /**
     * Render a compact tree of the sandbox (or a sub-path), like Unix {@code tree}.
     * Hidden files/dirs and build artefacts are excluded.
     * Depth defaults to 3; hard-capped at 5.
     */
    public String tree(String path, int depth) {
        depth = Math.max(1, Math.min(5, depth));
        try {
            Path resolved = validatePath(path);
            if (!Files.isDirectory(resolved)) {
                return "Error: not a directory: " + path;
            }
            var sb = new StringBuilder();
            sb.append(path).append('\n');
            appendTree(sb, resolved, "", depth);
            String result = sb.toString();
            if (result.length() > MAX_FIND_OUTPUT_BYTES) {
                result = result.substring(0, MAX_FIND_OUTPUT_BYTES) + "\n... (truncated)";
            }
            return result;
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error listing directory: " + e.getMessage();
        }
    }

    private void appendTree(StringBuilder sb, Path dir, String prefix, int depth) throws IOException {
        if (depth == 0) return;
        List<Path> entries;
        try (Stream<Path> s = Files.list(dir)) {
            entries = s.filter(p -> !isHidden(p) && isTreeIncluded(p))
                       .sorted(Comparator.comparing(p -> (Files.isDirectory(p) ? "0" : "1") + p.getFileName()))
                       .limit(MAX_DIR_ENTRIES)
                       .toList();
        }
        for (int i = 0; i < entries.size(); i++) {
            Path p = entries.get(i);
            boolean last = i == entries.size() - 1;
            String branch = last ? "└── " : "├── ";
            String name = p.getFileName().toString();
            sb.append(prefix).append(branch).append(Files.isDirectory(p) ? name + "/" : name).append('\n');
            if (Files.isDirectory(p)) {
                appendTree(sb, p, prefix + (last ? "    " : "│   "), depth - 1);
            }
        }
    }

    private boolean isTreeIncluded(Path p) {
        String name = p.getFileName().toString();
        if (name.startsWith(".")) return false;
        if (Files.isDirectory(p)) {
            return !name.equals("target") && !name.equals("node_modules")
                    && !name.equals("build") && !name.equals("dist") && !name.equals(".gradle");
        }
        return true;
    }

    // === Security: Validate and resolve path ===

    /**
     * Validate path and resolve it within the sandbox.
     * Rejects hidden segments and paths that escape the sandbox root via traversal.
     *
     * @throws SecurityException if the path violates sandbox rules
     */
    Path validatePath(String path) {
        if (path == null || path.isBlank()) path = ".";

        Path p = Path.of(path);
        if (p.isAbsolute()) {
            if (!p.normalize().startsWith("/tmp")) {
                throw new SecurityException("Absolute paths are not allowed. "
                        + "Use a path relative to the sandbox root " + sandboxRoot
                        + " — e.g. 'pom.xml' or 'src/main/java/Foo.java'");
            }
            return p.normalize();
        }

        // Relative: reject hidden segments
        for (String segment : path.split("[/\\\\]")) {
            if (segment.startsWith(".") && !segment.equals(".")) {
                throw new SecurityException("Access denied: hidden path segment '" + segment + "'");
            }
        }

        Path resolved = sandboxRoot.resolve(path).toAbsolutePath().normalize();
        // Check canonical path is within sandbox
        try {
            Path canonical = resolved.toRealPath();
            if (!canonical.startsWith(sandboxRoot.toRealPath())) {
                throw new SecurityException("Access denied: path escapes sandbox");
            }
            return canonical;
        } catch (IOException e) {
            if (!resolved.startsWith(sandboxRoot)) {
                throw new SecurityException("Access denied: path escapes sandbox");
            }
            return resolved;
        }
    }

    /**
     * Search for text in a file or recursively in a directory (like Unix {@code grep} / {@code grep -rn}).
     * <p>
     * Matching is case-insensitive on whole lines.
     * Output format for a single file: {@code line: match}.
     * Output format for a directory: {@code relativePath:line: match}.
     * <p>
     * For a directory ("." scans the whole project) all files accepted by the standard
     * inclusion filter are scanned in sorted order; oversized or unreadable (e.g. binary)
     * files are skipped silently. Output is limited to the first {@value #MAX_MATCHES}
     * matches and roughly {@value #MAX_OUTPUT_BYTES} bytes overall.
     *
     * @param query Text to search for (case-insensitive)
     * @param path  File or directory, relative to the sandbox root ("." allowed)
     * @return Header plus matches, a no-matches note, or an error message
     */
    public String grep(String query, String path) {
        try {
            Path resolved = validatePath(path);
            boolean directory = Files.isDirectory(resolved);
            if (!directory) {
                if (!Files.isRegularFile(resolved)) {
                    return "Error: not a regular file: " + path;
                }
                long fileSize = Files.size(resolved);
                if (fileSize > MAX_FILE_SIZE_BYTES) {
                    return "Error: file too large (" + fileSize + " bytes, max " + MAX_FILE_SIZE_BYTES + ")";
                }
            }

            List<Path> filesToScan = new ArrayList<>();
            if (directory) {
                try (Stream<Path> walk = Files.walk(resolved)) {
                    walk.filter(Files::isRegularFile)
                            .filter(this::isIncludedFile)
                            .sorted()
                            .forEach(filesToScan::add);
                }
            } else {
                filesToScan.add(resolved);
            }

            var matches = new ArrayList<String>();
            String queryLower = query.toLowerCase();
            int totalOutputBytes = 0;

            scan:
            for (Path file : filesToScan) {
                if (directory && Files.size(file) > MAX_FILE_SIZE_BYTES) {
                    continue; // skip oversized files in recursive scans
                }
                String prefix = "";
                if (directory) {
                    String relative = resolved.relativize(file).toString().replace('\\', '/');
                    prefix = (path.equals(".") ? "" : path + "/") + relative + ":";
                }
                List<String> lines;
                try {
                    lines = Files.readAllLines(file);
                } catch (IOException e) {
                    if (!directory) throw e; // single file: report as read error, as before
                    continue; // skip unreadable (e.g. binary) files in recursive scans
                } catch (RuntimeException e) {
                    if (!directory) throw e;
                    continue;
                }
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).toLowerCase().contains(queryLower)) {
                        String match = prefix + (i + 1) + ": " + lines.get(i);
                        totalOutputBytes += match.length();
                        if (totalOutputBytes > MAX_OUTPUT_BYTES) {
                            matches.add("... (output truncated at " + MAX_OUTPUT_BYTES + " bytes)");
                            break scan;
                        }
                        matches.add(match);
                        if (matches.size() >= MAX_MATCHES) {
                            matches.add("... (showing first " + MAX_MATCHES + " matches)");
                            break scan;
                        }
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
     * Run a bash command in the sandbox root and return its output - no confirmation asked.
     * <p>
     * Intended for autonomous agents to build, test and execute artifacts, e.g.
     * {@code mvn -q package}, {@code java -jar target/app.jar '1+2'}.
     * Timeout: 60 seconds. Max output: 16KB.
     *
     * @param command Bash command to run
     * @return "Exit N:\n" + command output, or error/timeout message
     */
    public String run(String command) {
        // Block commands that search or operate outside the sandbox (find /, ls /, etc.)
        // These almost always time out and are never useful — the model should use relative paths.
        if (OUTSIDE_SANDBOX_PATTERN.matcher(command).find()) {
            return "Error: command references paths outside the sandbox root. "
                    + "Use relative paths or '.' — e.g. 'find . -name Calculator.java' instead of 'find / ...'";
        }
        String raw = exec(List.of("bash", "-c", command), EXEC_TIMEOUT_SECONDS);
        return postProcessOutput(command, raw);
    }

    /**
     * For Maven/Gradle failures extract the signal (BUILD FAILURE + test errors + last N lines)
     * so the model doesn't need to parse 16KB of verbose output.
     */
    private static String postProcessOutput(String command, String raw) {
        if (!raw.contains("BUILD FAILURE") && !raw.contains("BUILD SUCCESS")) return raw;

        String[] lines = raw.split("\n", -1);
        var signal = new ArrayList<String>();

        // First line = "Exit N:" — keep it
        if (lines.length > 0) signal.add(lines[0]);

        // Extract [ERROR] lines and test failure blocks
        boolean inFailureBlock = false;
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i];
            if (l.startsWith("[ERROR]") || l.contains("FAILURE") || l.contains("Tests run:")
                    || l.contains("BUILD SUCCESS") || l.contains("BUILD FAILURE")) {
                signal.add(l);
                inFailureBlock = true;
            } else if (inFailureBlock && !l.isBlank()) {
                signal.add(l);
            } else {
                inFailureBlock = false;
            }
        }

        // Always include last 15 lines for context
        int tail = Math.max(0, lines.length - 15);
        for (int i = tail; i < lines.length; i++) {
            String l = lines[i];
            if (!signal.contains(l)) signal.add(l);
        }

        String summary = String.join("\n", signal);
        if (summary.length() < raw.length() / 2) {
            // Only substitute if we saved substantial space
            return summary + "\n[full output truncated — " + lines.length + " lines total]";
        }
        return raw;
    }

    private static final java.util.regex.Pattern OUTSIDE_SANDBOX_PATTERN =
            java.util.regex.Pattern.compile(
                    // find/ls/du/stat starting from /, /usr, /home, /Volumes, etc.
                    "\\b(?:find|ls|du|stat|locate)\\s+/(?!tmp\\b)"
                    // command substitution or pipe into something starting with /
                    + "|(?:^|[|;`&])\\s*/(?!tmp/|dev/null\\b)[a-zA-Z]"
            );

    /**
     * Run a bash command after asking the user for confirmation (y/n) -
     * the human-in-the-loop variant used by the interactive chatbots.
     * Timeout: 10 seconds.
     *
     * @param command Bash command to run (e.g., "find . -name '*.java'")
     * @return Command output (truncated if > 16KB), or a cancellation message
     */
    public String runCommand(String command) {
        System.out.print("\n[!] Run command: " + command + "\nConfirm? (y/n): ");
        System.out.flush();
        try {
            var console = System.console();
            String response = console != null
                    ? console.readLine()
                    : new BufferedReader(new InputStreamReader(System.in)).readLine();
            if (response == null) return "Command cancelled (no confirmation input).";
            response = response.trim().toLowerCase();
            if (!response.equals("y") && !response.equals("yes")) return "Command cancelled by user.";
        } catch (IOException e) {
            return "Command cancelled: " + e.getMessage();
        }
        return exec(List.of("bash", "-c", command), COMMAND_TIMEOUT_SECONDS);
    }

    /**
     * Shared process runner: executes in the sandbox root, merges stdout/stderr
     * (truncated at 16KB), kills after the timeout.
     */
    private String exec(List<String> cmd, long timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(sandboxRoot.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // thread-read to prevent blocking if the buffer fills up
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

        for (String ext : BINARY_EXTENSIONS) {
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
            return "Written: " + path + " (" + content.length() + " chars)\n" + previewLines(content, 100);
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    /**
     * Create a new file with content, sandboxed to root.
     * Fails if the file already exists - use {@link #writeFile} to overwrite.
     */
    public String createFile(String path, String content) {
        try {
            Path resolved = validatePath(path);
            if (Files.exists(resolved)) {
                return "Error: file already exists (use write-file to overwrite): " + path;
            }
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content, StandardCharsets.UTF_8);
            return "Created file: " + path + " (" + content.length() + " chars)\n" + previewLines(content, 100);
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error creating file: " + e.getMessage();
        }
    }

    /**
     * Replace exact text in a file - the surgical alternative to {@link #writeFile}
     * for existing files (saves re-sending the whole content).
     * {@code oldText} must occur exactly once, so the edit cannot hit the wrong spot.
     */
    public String editFile(String path, String oldText, String newText) {
        try {
            Path resolved = validatePath(path);
            if (!Files.isRegularFile(resolved)) {
                return "Error: not a regular file: " + path;
            }
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            int first = content.indexOf(oldText);
            if (first < 0) {
                return "Error: text not found in " + path
                        + " - check the current content with read-file or grep first";
            }
            if (content.indexOf(oldText, first + oldText.length()) >= 0) {
                return "Error: text occurs multiple times in " + path
                        + " - include more surrounding lines in 'old' to make it unique";
            }
            Files.writeString(resolved,
                    content.substring(0, first) + newText + content.substring(first + oldText.length()),
                    StandardCharsets.UTF_8);
            return "Edited: " + path + "\n" + lineDiff(oldText, newText);
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error editing file: " + e.getMessage();
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
     * Unified-diff-style output for edit: only changed lines ± 2 lines of context.
     * Uses a simple O(n·m) LCS to find the edit script.
     */
    private static String lineDiff(String oldText, String newText) {
        String[] a = oldText.split("\n", -1);
        String[] b = newText.split("\n", -1);
        int n = a.length, m = b.length;

        // LCS table
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                dp[i][j] = a[i].equals(b[j]) ? dp[i+1][j+1] + 1 : Math.max(dp[i+1][j], dp[i][j+1]);

        // Reconstruct edit ops: ' ' keep, '-' delete, '+' insert
        record Op(char kind, String line) {}
        var ops = new ArrayList<Op>();
        for (int i = 0, j = 0; i < n || j < m; ) {
            if (i < n && j < m && a[i].equals(b[j])) {
                ops.add(new Op(' ', a[i++])); j++;
            } else if (j < m && (i >= n || dp[i][j+1] >= dp[i+1][j])) {
                ops.add(new Op('+', b[j++]));
            } else {
                ops.add(new Op('-', a[i++]));
            }
        }

        // Collect changed line indices
        int CONTEXT = 2;
        boolean[] show = new boolean[ops.size()];
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i).kind() != ' ') {
                for (int k = Math.max(0, i - CONTEXT); k <= Math.min(ops.size() - 1, i + CONTEXT); k++)
                    show[k] = true;
            }
        }

        var sb = new StringBuilder();
        boolean gap = false;
        for (int i = 0; i < ops.size(); i++) {
            if (!show[i]) { gap = true; continue; }
            if (gap) { sb.append("  …\n"); gap = false; }
            sb.append(ops.get(i).kind()).append(' ').append(ops.get(i).line()).append('\n');
        }
        return sb.toString();
    }

    private static String previewLines(String content, int maxLines) {
        String[] lines = content.split("\n", -1);
        int shown = Math.min(lines.length, maxLines);
        var sb = new StringBuilder();
        for (int i = 0; i < shown; i++) sb.append(lines[i]).append("\n");
        if (lines.length > maxLines) sb.append("... (").append(lines.length - maxLines).append(" more lines)");
        return sb.toString();
    }

    /**
     * Register the standard read-only file tools (ls, read-file, grep, find-file) with a ToolSupport.
     */
    public void registerTools(ToolSupport toolSupport) {
        String root = sandboxRoot.toString();
        toolSupport.registerTool("ls", "List directory contents. Paths are relative to sandbox root " + root,
                Schemas.object()
                        .optional("path", Schemas.string().withDescription("Directory path relative to sandbox root " + root + " (default: '.' = sandbox root)"))
                        .toJsonSchema(),
                args -> ls(args.get("path") != null ? (String) args.get("path") : "."));

        toolSupport.registerTool("read-file", "Read a file's full contents (up to 20KB). Paths are relative to sandbox root " + root,
                Schemas.object()
                        .required("path", Schemas.string().withDescription("File path relative to sandbox root " + root + " — e.g. README.md, src/main/java/Foo.java"))
                        .toJsonSchema(),
                args -> readFile((String) args.get("path")));

        toolSupport.registerTool("grep", "Search for text in a file or directory. Paths are relative to sandbox root " + root + ". Omit path to search the whole sandbox.",
                Schemas.object()
                        .required("query", Schemas.string().withDescription("Search query (case-insensitive)"))
                        .optional("path", Schemas.string().withDescription("File or directory relative to sandbox root " + root + " (default: '.' = whole sandbox)"))
                        .toJsonSchema(),
                args -> grep((String) args.get("query"), args.containsKey("path") ? (String) args.get("path") : "."));

        toolSupport.registerTool("find-file", "Find all files containing the given text. Paths are relative to sandbox root " + root,
                Schemas.object()
                        .required("query", Schemas.string().withDescription("Text to search for (literal, case-insensitive)"))
                        .toJsonSchema(),
                args -> findFiles((String) args.get("query")));
    }
}
