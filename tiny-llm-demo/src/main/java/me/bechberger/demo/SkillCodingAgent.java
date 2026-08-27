package me.bechberger.demo;

import me.bechberger.demo.util.Ansi;
import me.bechberger.demo.util.Repl;
import me.bechberger.demo.util.Skills;
import me.bechberger.demo.ToolSupport;
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

    /** name → skill, discovered in onStart. */
    private final Map<String, Skills.Skill> availableSkills = new LinkedHashMap<>();
    /** name → skill content, loaded on activation. */
    private final Map<String, String> activeSkills = new LinkedHashMap<>();

    @Override
    protected void onStart() {
        availableSkills.putAll(Skills.discover(Path.of(root, ".claude", "skills")));
    }

    @Override
    protected String greeting() {
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
    protected void registerCommands(Repl.Builder builder, LLMClient client, FileTools fileTools,
                                    ToolSupport toolSupport, List<Map<String, Object>> messages) {
        super.registerCommands(builder, client, fileTools, toolSupport, messages);
        if (availableSkills.isEmpty()) return;
        builder
                .on("skills", "list available and active (*) skills", args -> printSkills())
                .on("skill", "toggle a skill for this conversation: /skill <name>",
                        args -> System.out.println(args.isBlank() ? "Usage: /skill <name>" : toggle(args)));
    }

    @Override
    protected String buildSystemPrompt() {
        var sb = new StringBuilder();
        if (!availableSkills.isEmpty()) {
            sb.append("## Available Skills\n");
            availableSkills.forEach((name, skill) ->
                    sb.append("- ").append(name)
                      .append(skill.description().isEmpty() ? "" : " — " + skill.description())
                      .append("\n"));
            sb.append("IMPORTANT: Before answering any user request, check whether a skill applies. ");
            sb.append("If it does, you MUST call the skill tool to activate it first, then proceed.\n\n");
        }
        sb.append(super.buildSystemPrompt());
        if (!activeSkills.isEmpty()) {
            sb.append("\n\n## Active Skills — follow their instructions\n");
            activeSkills.forEach((name, content) ->
                    sb.append("\n### ").append(name).append("\n").append(content.strip()).append("\n"));
        }
        return sb.toString();
    }

    /** Tool entry point: load a skill's content into the active set. */
    private String activate(String name) {
        var skill = availableSkills.get(name);
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
        if (availableSkills.isEmpty()) { System.out.println(Ansi.dim("(no skills found in .claude/skills)")); return; }
        availableSkills.forEach((name, skill) -> {
            boolean active = activeSkills.containsKey(name);
            String marker = active ? Ansi.boldGreen("* ") : "  ";
            String desc = skill.description().isEmpty() ? "" : Ansi.dim(" — " + skill.description());
            System.out.println(marker + (active ? Ansi.green(name) : name) + desc);
        });
    }

    public static void main(String[] args) {
        try {
            System.exit(FemtoCli.run(new SkillCodingAgent(), args));
        } catch (NoClassDefFoundError e) {
            // The agent rebuilt the JAR during this session — the running JVM still has the old classes.
            System.out.println("\n[JAR rebuilt — run the command again to use the updated version.]");
            System.exit(0);
        }
    }
}

