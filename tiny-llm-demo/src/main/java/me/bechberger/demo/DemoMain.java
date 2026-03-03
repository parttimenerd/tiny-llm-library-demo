package me.bechberger.demo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Small launcher for the shaded demo jar.
 *
 * Usage:
 *   java -jar tiny-llm-demo.jar ToolChatBot --model ...
 *   java -jar tiny-llm-demo.jar solutions.ChatBot --model ...
 */
public final class DemoMain {

    private DemoMain() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || isHelp(args[0])) {
            printUsage();
            return;
        }

        String target = args[0];
        String[] forwardedArgs = Arrays.copyOfRange(args, 1, args.length);

        List<String> candidates = new ArrayList<>();
        candidates.add(toFullyQualifiedClassName(target));

        // Convenience fallback: if the user passed "ChatBot" and the root package doesn't exist,
        // try the solutions package.
        if (!target.contains(".")) {
            candidates.add("me.bechberger.demo.solutions." + target);
        }

        Class<?> clazz = null;
        ClassNotFoundException lastNotFound = null;
        for (String candidate : candidates) {
            try {
                clazz = Class.forName(candidate);
                break;
            } catch (ClassNotFoundException e) {
                lastNotFound = e;
            }
        }

        if (clazz == null) {
            System.err.println("Could not find class: " + target);
            System.err.println("Tried: " + String.join(", ", candidates));
            if (lastNotFound != null) {
                lastNotFound.printStackTrace(System.err);
            }
            System.exit(2);
            return;
        }

        Method mainMethod;
        try {
            mainMethod = clazz.getMethod("main", String[].class);
        } catch (NoSuchMethodException e) {
            System.err.println("Class does not have a main(String[] args): " + clazz.getName());
            e.printStackTrace(System.err);
            System.exit(3);
            return;
        }

        if (!Modifier.isStatic(mainMethod.getModifiers())) {
            System.err.println("main(String[] args) is not static: " + clazz.getName());
            System.exit(3);
            return;
        }

        try {
            // Varargs reflection requires the cast to Object to avoid treating the array as varargs.
            mainMethod.invoke(null, (Object) forwardedArgs);
        } catch (IllegalAccessException e) {
            System.err.println("Cannot access main(String[] args) on: " + clazz.getName());
            e.printStackTrace(System.err);
            System.exit(4);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            Objects.requireNonNullElse(cause, e).printStackTrace(System.err);
            System.exit(5);
        }
    }

    private static boolean isHelp(String arg) {
        return "-h".equals(arg) || "--help".equals(arg) || "help".equalsIgnoreCase(arg);
    }

    private static void printUsage() {
                System.out.print("""
                                Usage: java -jar tiny-llm-demo.jar <ClassName> [args...]

                                Examples:
                                    java -jar tiny-llm-demo.jar ChatBot --model ... --base-url http://localhost:8080
                                    java -jar tiny-llm-demo.jar ToolChatBot --model ... --root .
                                    java -jar tiny-llm-demo.jar solutions.ChatBot --model ...

                                Class name resolution:
                                    - ChatBot               -> me.bechberger.demo.ChatBot
                                    - ToolChatBot           -> me.bechberger.demo.ToolChatBot
                                    - solutions.ChatBot     -> me.bechberger.demo.solutions.ChatBot
                                    - solutions.ToolChatBot -> me.bechberger.demo.solutions.ToolChatBot
                                """);
    }

    private static String toFullyQualifiedClassName(String name) {
        if (name.startsWith("me.")) {
            return name;
        }
        // e.g. "solutions.ChatBot" -> "me.bechberger.demo.solutions.ChatBot"
        // e.g. "ToolChatBot" -> "me.bechberger.demo.ToolChatBot"
        return "me.bechberger.demo." + name;
    }
}