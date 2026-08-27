package me.bechberger.demo.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Helpers for discovering and reading skill files from {@code .claude/skills/<name>/SKILL.md}. */
public final class Skills {

    private Skills() {}

    public record Skill(Path path, String description) {}

    /**
     * Scan {@code skillsDir} for subdirectories containing {@code SKILL.md}.
     * Returns an ordered map of name → Skill (empty if the directory doesn't exist).
     */
    public static Map<String, Skill> discover(Path skillsDir) {
        var result = new LinkedHashMap<String, Skill>();
        if (!Files.isDirectory(skillsDir)) return result;
        try (var dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path md = dir.resolve("SKILL.md");
                if (Files.isRegularFile(md))
                    result.put(dir.getFileName().toString(), new Skill(md, descriptionOf(md)));
            });
        } catch (IOException ignored) {}
        return result;
    }

    /**
     * One-line description from a SKILL.md: the {@code description:} frontmatter field if present,
     * else the first non-heading non-blank body line.
     */
    public static String descriptionOf(Path md) {
        List<String> lines;
        try { lines = Files.readAllLines(md); } catch (IOException e) { return ""; }
        int bodyStart = 0;
        if (!lines.isEmpty() && lines.getFirst().equals("---")) {
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).equals("---")) { bodyStart = i + 1; break; }
                if (lines.get(i).startsWith("description:")) {
                    String val = lines.get(i).substring("description:".length()).strip();
                    if (val.equals(">") || val.equals("|")) {
                        // YAML block scalar: collect following indented lines
                        var sb = new StringBuilder();
                        for (int j = i + 1; j < lines.size(); j++) {
                            String l = lines.get(j);
                            if (l.startsWith("  ") || l.startsWith("\t")) {
                                if (sb.length() > 0) sb.append(' ');
                                sb.append(l.strip());
                            } else break;
                        }
                        return sb.toString();
                    }
                    return val;
                }
            }
        }
        for (int i = bodyStart; i < lines.size(); i++) {
            var line = lines.get(i).strip();
            if (!line.isEmpty() && !line.startsWith("#")) return line;
        }
        return "";
    }
}
