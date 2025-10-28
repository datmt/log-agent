package com.datmt.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The main agent class that will be loaded by the JVM.
 * It uses a "premain" method as its entry point.
 */
public class MethodLoggerAgent {

    /**
     * JVM hook to dynamically transform classes.
     *
     * @param agentArgs The agent arguments, e.g., "logfile=/path/to/log.jsonl;packages=com.example.;excludePackages=org.unwanted."
     * @param inst      The Instrumentation instance provided by the JVM.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[MethodLoggerAgent] Agent started.");

        Map<String, String> argsMap = parseAgentArgs(agentArgs);
        String logFile = argsMap.getOrDefault("logfile", "method_calls.jsonl");
        String packages = argsMap.getOrDefault("packages", null);
        String excludePackages = argsMap.getOrDefault("excludePackages", null);

        // Initialize the advice class with the log file path
        MethodLoggingAdvice.init(logFile);
        System.out.println("[MethodLoggerAgent] Logging to: " + MethodLoggingAdvice.getLogFile());

        // --- Create the INCLUSION package matcher ---
        ElementMatcher.Junction<net.bytebuddy.description.type.TypeDescription> packageMatcher;
        if (packages != null && !packages.isEmpty()) {
            var matchers =
                    Arrays.stream(packages.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .map(pkg -> {
                                System.out.println("[MethodLoggerAgent] Matching package: " + pkg);
                                return ElementMatchers.nameStartsWith(pkg);
                            })
                            .toList();

            packageMatcher = new ElementMatcher.Junction.Disjunction<>(matchers.toArray(new ElementMatcher[0]));
        } else {
            System.out.println("[MethodLoggerAgent] No 'packages' specified, matching all (with default exclusions).");
            // Match all, but exclude common noisy packages to avoid performance issues
            packageMatcher = ElementMatchers.any();
        }

        // --- Create the EXCLUSION package matcher ---
        var  excludeMatcher = ElementMatchers.none();
        if (excludePackages != null && !excludePackages.isEmpty()) {
            var matchers =
                    Arrays.stream(excludePackages.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .map(pkg -> {
                                System.out.println("[MethodLoggerAgent] Excluding package: " + pkg);
                                return ElementMatchers.nameStartsWith(pkg);
                            })
                            .toList();

            excludeMatcher = new ElementMatcher.Junction.Disjunction<>(matchers.toArray(new ElementMatcher[0]));
        }

        // Build the agent
        new AgentBuilder.Default()
                .with(new AgentBuilder.Listener.StreamWriting(System.out).withTransformationsOnly())
                .type(packageMatcher
                        // --- Apply EXCLUSIONS ---
                        .and(ElementMatchers.not(excludeMatcher)) // <-- New exclusion logic

                        // --- Default Exclusions for stability ---
                        .and(ElementMatchers.not(ElementMatchers.nameStartsWith("com.datmt.agent")))
                        .and(ElementMatchers.not(ElementMatchers.nameStartsWith("net.bytebuddy")))
                        .and(ElementMatchers.not(ElementMatchers.nameStartsWith("java.")))
                        .and(ElementMatchers.not(ElementMatchers.nameStartsWith("javax.")))
                        .and(ElementMatchers.not(ElementMatchers.nameStartsWith("sun.")))
                        .and(ElementMatchers.not(ElementMatchers.nameStartsWith("jdk.")))
                        .and(ElementMatchers.not(ElementMatchers.nameStartsWith("com.sun.")))
                        .and(ElementMatchers.not(ElementMatchers.nameStartsWith("com.google.gson")))

                        // --- CRITICAL SPRING FIX ---
                        // Exclude all CGLIB proxy classes. This is more robust.
                        .and(ElementMatchers.not(ElementMatchers.nameMatches(".*\\$\\$.*")))
                )
                .transform((builder, typeDescription, classLoader, module, transformer) ->
                        builder.method(ElementMatchers.any()) // Transform all methods in the matched classes
                                .intercept(Advice.to(MethodLoggingAdvice.class))
                )
                .installOn(inst);

        System.out.println("[MethodLoggerAgent] Agent installation complete.");
    }

    /**
     * Parses the agent arguments string into a simple key-value map.
     * Arguments are expected in a format like: "key1=value1;key2=value2"
     *
     * @param agentArgs The string from the -javaagent flag.
     * @return A map of arguments.
     */
    public static Map<String, String> parseAgentArgs(String agentArgs) {
        Map<String, String> argsMap = new HashMap<>();
        if (agentArgs != null && !agentArgs.isEmpty()) {
            for (String arg : agentArgs.split(";")) {
                String[] parts = arg.split("=", 2);
                if (parts.length == 2) {
                    argsMap.put(parts[0].trim(), parts[1].trim());
                }
            }
        }
        return argsMap;
    }
}
