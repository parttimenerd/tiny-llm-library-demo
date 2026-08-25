package me.bechberger.demo;

import me.bechberger.demo.util.Repl;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CodingAgent extended with skills: small instruction files the agent loads on demand.
 * <p>
 * Skills live in {@code .claude/skills/<name>/SKILL.md} below the project root. They are
 * discovered at startup and listed in the system prompt; activation happens via the
 * {@code skill} tool (LLM) or the {@code /skill} command (user). Active skill contents are
 * appended by {@link #buildSystemPrompt()} — which the base class re-syncs into the
 * conversation before every LLM call, so (de)activation takes effect immediately.
 */
@Command(name = "skill-agent", description = "Coding agent with .claude skills support", version = "1.0.0")
public class SkillCodingAgent extends CodingAgent {

    /** A discovered skill: path + one-line description from the SKILL.md frontmatter. */
    record Skill(Path path, String description) {}

    /** name → skill, discovered in onStart. */
    private final Map<String, Skill> availableSkills = new LinkedHashMap<>();
    /** name → skill content, loaded on activation. */
    private final Map<String, String> activeSkills = new LinkedHashMap<>();

    @Override
    protected void onStart() {
        Path skillsDir = Path.of(root, ".claude", "skills");
        if (!Files.isDirectory(skillsDir)) return;
        try (var dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path md = dir.resolve("SKILL.md");
                if (Files.isRegularFile(md)) {
                    availableSkills.put(dir.getFileName().toString(),
                            new Skill(md, descriptionOf(md)));
                }
            });
        } catch (IOException ignored) {
        }
    }

    /**
     * One-line preview of a skill, so we can list it without activating it:
     * the {@code description:} line from the {@code ---} frontmatter if present,
     * else the first non-heading, non-blank line of the body.
     */
    private static String descriptionOf(Path md) {
        List<String> lines;
        try {
            lines = Files.readAllLines(md); // SKILL.md files are tiny — eager read is fine
        } catch (IOException e) {
            return "";
        }
        int bodyStart = 0;
        if (!lines.isEmpty() && lines.getFirst().equals("---")) {
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).equals("---")) {
                    bodyStart = i + 1; // frontmatter ends here, body follows
                    break;
                }
                if (lines.get(i).startsWith("description:")) {
                    return lines.get(i).substring("description:".length()).strip();
                }
            }
        }
        // no frontmatter description — fall back to the first non-heading, non-blank body line
        for (int i = bodyStart; i < lines.size(); i++) {
            var line = lines.get(i).strip();
            if (!line.isEmpty() && !line.startsWith("#")) {
                return line;
            }
        }
        return "";
    }

    @Override
    protected String greeting() {
        // with no skills around, stay invisible - this class behaves exactly like CodingAgent
        return availableSkills.isEmpty() ? super.greeting()
                : super.greeting() + " — " + availableSkills.size() + " skill(s) in .claude/skills";
    }

    @Override
    protected ToolSupport createToolSupport(FileTools fileTools) {
        var ts = super.createToolSupport(fileTools);
        if (availableSkills.isEmpty()) return ts;
        CodingTools.register(ts, "skill",
                "Activate a skill from the Available Skills list to load its instructions",
                args -> activate(CodingTools.str(args, "name")),
                "name", "Skill name from the Available Skills list");
        return ts;
    }

    @Override
    protected void registerCommands(Repl repl, LLMClient client, FileTools fileTools,
                                    List<Map<String, Object>> messages) {
        super.registerCommands(repl, client, fileTools, messages);
        if (availableSkills.isEmpty()) return; // no /skill commands on skill-less projects
        repl.commands()
                .on("skills", "list available and active (*) skills", args -> printSkills())
                .on("skill", "toggle a skill for this conversation: /skill <name>",
                        args -> System.out.println(args.isBlank() ? "Usage: /skill <name>" : toggle(args)));
    }

    @Override
    protected String buildSystemPrompt() {
        var sb = new StringBuilder(super.buildSystemPrompt());
        if (!availableSkills.isEmpty()) {
            sb.append("\n\n## Available Skills\n");
            availableSkills.forEach((name, skill) ->
                    sb.append("- ").append(name)
                      .append(skill.description().isEmpty() ? "" : " — " + skill.description())
                      .append("\n"));
            sb.append("When the task matches one, activate it with the skill tool before starting.");
        }
        if (!activeSkills.isEmpty()) {
            sb.append("\n\n## Active Skills — follow their instructions\n");
            activeSkills.forEach((name, content) ->
                    sb.append("\n### ").append(name).append("\n").append(content.strip()).append("\n"));
        }
        return sb.toString();
    }

    /** Tool entry point: load a skill's content into the active set. */
    private String activate(String name) {
        Skill skill = availableSkills.get(name);
        if (skill == null) return "Unknown skill: " + name + " — available: " + availableSkills.keySet();
        if (activeSkills.containsKey(name)) return "Skill already active: " + name;
        try {
            activeSkills.put(name, Files.readString(skill.path()));
            return "Activated skill: " + name + " — its instructions are now part of your system prompt.";
        } catch (IOException e) {
            return "Error loading skill: " + e.getMessage();
        }
    }

    /** User entry point: /skill toggles activation. */
    private String toggle(String name) {
        return activeSkills.remove(name) != null ? "Deactivated skill: " + name : activate(name);
    }

    private void printSkills() {
        if (availableSkills.isEmpty()) {
            System.out.println("(no skills found in .claude/skills)");
            return;
        }
        System.out.println("Skills:");
        availableSkills.forEach((name, skill) ->
                System.out.println("  " + (activeSkills.containsKey(name) ? "*" : " ") + " " + name
                        + (skill.description().isEmpty() ? "" : " — " + skill.description())));
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new SkillCodingAgent(), args));
    }
}
