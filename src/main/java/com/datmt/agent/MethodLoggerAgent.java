package com.datmt.agent;


import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;

/**
 * This is the main entry point for our Java agent.
 * The JVM will call the `premain` method before the target application's `main` method.
 */
public class MethodLoggerAgent {

    /**
     * The agent's entry point.
     *
     * @param agentArgs       Agent arguments in "key=value;key2=value2" format.
     * Example: "logfile=/tmp/agent.log;packages=com.example.,org.another."
     * @param instrumentation The Instrumentation API provided by the JVM
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        System.out.println("[MethodLoggerAgent] Starting agent...");

        // Parse agent arguments
        Map<String, String> argsMap = parseAgentArgs(agentArgs);
        String logFile = argsMap.getOrDefault("logfile", "method_calls.jsonl");
        String packagesToLog = argsMap.get("packages");

        // Pass the log file path to our advice class
        MethodLoggingAdvice.init(logFile);

        System.out.println("[MethodLoggerAgent] Logging to file: " + MethodLoggingAdvice.getLogFile());

        // Build the package matcher
        ElementMatcher.Junction<TypeDescription> packageMatcher;
        if (packagesToLog == null || packagesToLog.isEmpty()) {
            System.out.println("[MethodLoggerAgent] No 'packages' argument found. Logging all packages (excluding java, sun, and agent).");
            packageMatcher = ElementMatchers.any();
        } else {
            System.out.println("[MethodLoggerAgent] Logging packages starting with: " + packagesToLog);
            ElementMatcher.Junction<TypeDescription> matcher = ElementMatchers.none();
            for (String pkg : packagesToLog.split(",")) {
                if (!pkg.trim().isEmpty()) {
                    // Use nameStartsWith to support "com.example." wildcard style
                    matcher = matcher.or(ElementMatchers.nameStartsWith(pkg.trim()));
                }
            }
            packageMatcher = matcher;
        }

        new AgentBuilder.Default()
                // We want to re-transform classes (e.g., if they are already loaded)
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .ignore(ElementMatchers.none()) // We don't want to ignore any classes by default

                // Specify which classes to intercept.
                .type(
                        // Apply the package matcher from our args
                        packageMatcher
                                // Always exclude our own agent classes to avoid infinite loops!
                                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("com.datmt.agent")))
                                // Also good to exclude common java/sun/byte-buddy packages to reduce noise
                                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("java.")))
                                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("javax.")))
                                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("sun.")))
                                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("com.sun.")))
                                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("net.bytebuddy.")))
                )
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) ->
                        builder.method(
                                        // Intercept *any* method that is not abstract or a constructor
                                        ElementMatchers.not(ElementMatchers.isAbstract())
                                                .and(ElementMatchers.not(ElementMatchers.isConstructor()))
                                )
                                .intercept(
                                        // And apply our "advice" (the interceptor logic)
                                        Advice.to(MethodLoggingAdvice.class)
                                )
                )
                // Install the transformer into the JVM
                .installOn(instrumentation);

        System.out.println("[MethodLoggerAgent] Agent started successfully.");
    }

    /**
     * Parses the agent arguments string into a key-value map.
     *
     * @param agentArgs The string passed to premain, e.g., "key1=val1;key2=val2"
     * @return A map of the parsed arguments.
     */
    public static Map<String, String> parseAgentArgs(String agentArgs) {
        Map<String, String> argsMap = new HashMap<>();
        if (agentArgs == null || agentArgs.isEmpty()) {
            return argsMap;
        }

        for (String arg : agentArgs.split(";")) {
            String[] parts = arg.split("=", 2);
            if (parts.length == 2) {
                argsMap.put(parts[0].trim(), parts[1].trim());
            }
        }
        return argsMap;
    }
}


