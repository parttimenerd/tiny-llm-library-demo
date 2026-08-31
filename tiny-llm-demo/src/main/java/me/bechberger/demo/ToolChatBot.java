package me.bechberger.demo;

import me.bechberger.demo.FileTools;
import me.bechberger.demo.util.Ansi;
import me.bechberger.demo.util.Repl;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Mixin;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.util.femtoschema.Schemas;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * Tool-enabled chatbot with file system access.
 * <p>
 * The tool loop is the key idea: LLM says "call ls" → we call it → we send the result back →
 * LLM continues. {@link ToolSupport#handleToolLoop} manages this while-loop automatically.
 * Tool registration (JSON schemas) is pre-written in {@link FileTools#registerTools} — it's
 * just plumbing; the interesting part is the loop and the conversation structure below.
 */
@Command(name = "tool-chatbot", description = "A chatbot with file system tools", version = "1.0.0")
public class ToolChatBot implements Callable<Integer> {

    @Mixin
    Options options;

    @Option(names = {"-r", "--root"}, description = "Sandbox root directory (default: ${DEFAULT-VALUE})",
            defaultValue = ".")
    String root;

    private static final String SYSTEM_PROMPT =
            "You are a helpful assistant with access to sandboxed file tools. " +
            "Use ls, read-file, grep and find-file to explore the sandbox, " +
            "answer from what you find, and keep replies short.";

    @Override
    public Integer call() {
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(LLMClient.system(SYSTEM_PROMPT));

        var builder = new Repl.Builder("\nYou: ", new Scanner(System.in), messages);
        var client = options.createClient(builder);
        var toolSupport = new ToolSupport();
        var fileTools = new FileTools(Path.of(root));

        // TODO: 1. registerTool("ls", description, Schemas.object().required("path",...).toJsonSchema(), handler)
        // TODO: 2. call handleToolLoop(client, messages), print response
        throw new UnsupportedOperationException("TODO: live code");
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new ToolChatBot(), args));
    }
}
