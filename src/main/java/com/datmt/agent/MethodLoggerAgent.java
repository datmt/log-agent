package com.datmt.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.File;
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
     * @param agentArgs The agent arguments, e.g., "logfile=/path/to/log.jsonl;packages=com.example.;excludePackages=org.unwanted.;logLevel=PUBLIC"
     * @param inst      The Instrumentation instance provided by the JVM.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            System.out.println("[MethodLoggerAgent] Agent started.");

            Map<String, String> argsMap = parseAgentArgs(agentArgs);
            String logFile = argsMap.getOrDefault("logfile", "method_calls.jsonl");
            String packages = argsMap.getOrDefault("packages", null);
            String excludePackages = argsMap.getOrDefault("excludePackages", null);
            Integer callerDepth = Helpers.fromString(argsMap.getOrDefault("callerDepth", "1"), 1);
            String logLevel = argsMap.getOrDefault("logLevel", "ALL"); // ALL, PUBLIC, PUBLIC_PROTECTED

            // Validate log file path
            validateLogFilePath(logFile);

            // Initialize the advice class with the log file path (must be done before any instrumentation)
            MethodLoggingAdvice.init(logFile, callerDepth);
            System.out.println("[MethodLoggerAgent] Logging to: " + MethodLoggingAdvice.getLogFile());
            System.out.println("[MethodLoggerAgent] Log level: " + logLevel);

            // --- Create the INCLUSION package matcher ---
            ElementMatcher.Junction<net.bytebuddy.description.type.TypeDescription> packageMatcher;
            if (packages != null && !packages.isEmpty()) {
                String[] packageArray = packages.split(",");
                validatePackageNames(packageArray, "packages");

                var matchers =
                        Arrays.stream(packageArray)
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
                packageMatcher = ElementMatchers.any();
            }

            // --- Create the EXCLUSION package matcher ---
            var excludeMatcher = ElementMatchers.none();
            if (excludePackages != null && !excludePackages.isEmpty()) {
                String[] excludeArray = excludePackages.split(",");
                validatePackageNames(excludeArray, "excludePackages");

                var matchers =
                        Arrays.stream(excludeArray)
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .map(pkg -> {
                                    System.out.println("[MethodLoggerAgent] Excluding package: " + pkg);
                                    return ElementMatchers.nameStartsWith(pkg);
                                })
                                .toList();

                excludeMatcher = new ElementMatcher.Junction.Disjunction<>(matchers.toArray(new ElementMatcher[0]));
            }

            // --- Create method visibility matcher based on logLevel ---
            ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> methodMatcher = switch (logLevel.toUpperCase()) {
                case "PUBLIC" -> ElementMatchers.isPublic();
                case "PUBLIC_PROTECTED" -> ElementMatchers.isPublic().or(ElementMatchers.isProtected());
                default -> ElementMatchers.any();
            };

            // Exclude synthetic, bridge methods, and common noise methods
            methodMatcher = methodMatcher
                    .and(ElementMatchers.not(ElementMatchers.isConstructor()))
                    .and(ElementMatchers.not(ElementMatchers.isSynthetic()))
                    .and(ElementMatchers.not(ElementMatchers.isBridge()))
                    .and(ElementMatchers.not(ElementMatchers.isNative()))
                    .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                    .and(ElementMatchers.not(ElementMatchers.isTypeInitializer())); // Exclude static initializers


            // Build the agent with error handling listener
            ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> finalMethodMatcher = methodMatcher;
            new AgentBuilder.Default()
                    .with(new AgentBuilder.Listener.StreamWriting(System.out).withTransformationsOnly())
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .ignore(new AgentBuilder.RawMatcher.ForElementMatchers(
                            ElementMatchers.nameStartsWith("net.bytebuddy.")
                                    .or(ElementMatchers.isSynthetic())
                    ))
                    .type(packageMatcher
                            // --- Apply User-defined EXCLUSIONS ---
                            .and(ElementMatchers.not(excludeMatcher))

                            // --- Default Exclusions for stability ---
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("com.datmt.agent")))
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("net.bytebuddy")))

                            // --- JDK packages ---
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("java.")))
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("javax.")))
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("jdk.")))
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("sun.")))
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("com.sun.")))
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("com.oracle.")))

                            // --- Logging frameworks (CRITICAL to prevent infinite loops) ---
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("org.slf4j")))
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("ch.qos.logback")))
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("org.apache.log4j")))
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("org.apache.logging")))
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("java.util.logging")))

                            // --- Common libraries that should not be instrumented ---
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("com.google.gson")))
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("com.fasterxml.jackson")))
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("org.json")))

                            // --- Spring Framework internals ---
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("org.springframework.cglib")))
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("org.springframework.aop")))

                            // --- Proxy and generated classes (more precise matching) ---
                            .and(ElementMatchers.not(ElementMatchers.nameMatches(".*\\$\\$.*")))
                            .and(ElementMatchers.not(ElementMatchers.nameMatches(".*\\$Proxy.*")))
                            .and(ElementMatchers.not(ElementMatchers.nameMatches(".*\\$ByteBuddy\\$.*")))
                            .and(ElementMatchers.not(ElementMatchers.nameContains("CGLIB$$")))
                            .and(ElementMatchers.not(ElementMatchers.nameContains("EnhancerBy")))

                            // --- Lambda and anonymous classes ---
                            .and(ElementMatchers.not(ElementMatchers.nameMatches(".*\\$\\$Lambda\\$.*")))

                            // --- Kotlin/Scala (if applicable) ---
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("kotlin.")))
                            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("scala.")))
                    )
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(MethodLoggingAdvice.class).on(finalMethodMatcher))
                    )
                    .installOn(inst);

            System.out.println("[MethodLoggerAgent] Agent installation complete.");

        } catch (Exception e) {
            System.err.println("[MethodLoggerAgent] FATAL: Failed to initialize agent: " + e.getMessage());
            System.exit(1); // Fail fast if agent is critical
        }
    }

    /**
     * Validates that the log file path is writable.
     *
     * @param logFile The log file path.
     * @throws IllegalArgumentException if the path is invalid or not writable.
     */
    private static void validateLogFilePath(String logFile) {
        if (logFile == null || logFile.trim().isEmpty()) {
            throw new IllegalArgumentException("Log file path cannot be null or empty");
        }

        File file = new File(logFile);
        File parentDir = file.getParentFile();

        // If parent directory doesn't exist, try to create it
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new IllegalArgumentException("Cannot create directory for log file: " + parentDir.getAbsolutePath());
            }
        }

        // Check if we can write to the location
        if (parentDir != null && !parentDir.canWrite()) {
            throw new IllegalArgumentException("Cannot write to log file directory: " + parentDir.getAbsolutePath());
        }

        System.out.println("[MethodLoggerAgent] Log file validation passed: " + file.getAbsolutePath());
    }

    /**
     * Validates package names to ensure they don't contain invalid characters.
     *
     * @param packages Array of package names to validate.
     * @param argName  The argument name (for error messages).
     * @throws IllegalArgumentException if any package name is invalid.
     */
    private static void validatePackageNames(String[] packages, String argName) {
        for (String pkg : packages) {
            String trimmed = pkg.trim();
            if (!trimmed.isEmpty() && !trimmed.matches("^[a-zA-Z0-9._]+$")) {
                throw new IllegalArgumentException(
                        "Invalid package name in '" + argName + "': " + trimmed +
                                " (only letters, numbers, dots, and underscores allowed)"
                );
            }
        }
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
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    if (!key.isEmpty() && !value.isEmpty()) {
                        argsMap.put(key, value);
                    }
                } else if (parts.length == 1) {
                    System.err.println("[MethodLoggerAgent] Warning: Ignoring malformed argument: " + arg);
                }
            }
        }
        return argsMap;
    }
}