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

    /** name → path to SKILL.md, discovered in onStart. */
    private final Map<String, Path> availableSkills = new LinkedHashMap<>();
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
                    availableSkills.put(dir.getFileName().toString(), md);
                }
            });
        } catch (IOException ignored) {
        }
    }

    @Override
    protected String greeting() {
        return super.greeting() + " — " + availableSkills.size() + " skill(s) in .claude/skills";
    }

    @Override
    protected ToolSupport createToolSupport(FileTools fileTools) {
        var ts = super.createToolSupport(fileTools);
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
            availableSkills.keySet().forEach(name -> sb.append("- ").append(name).append("\n"));
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
        Path md = availableSkills.get(name);
        if (md == null) return "Unknown skill: " + name + " — available: " + availableSkills.keySet();
        if (activeSkills.containsKey(name)) return "Skill already active: " + name;
        try {
            activeSkills.put(name, Files.readString(md));
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
        availableSkills.keySet().forEach(name ->
                System.out.println("  " + (activeSkills.containsKey(name) ? "*" : " ") + " " + name));
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new SkillCodingAgent(), args));
    }
}
