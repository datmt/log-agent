package com.datmt.agent;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * This class contains the "advice" logic that will be woven into the target methods.
 * It's responsible for logging method entry, exit, parameters, and return values.
 */
public class MethodLoggingAdvice {

    // A thread-local stack to store method start times for nested calls
    public static final ThreadLocal<Deque<Long>> startTimeStack = ThreadLocal.withInitial(LinkedList::new);

    // Use a thread-safe, static Gson instance
    // Disabling HTML escaping prevents strings like "<" from becoming "\u003c"
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    // The path to the log file, set by the agent premain
    public static Path LOG_FILE = Paths.get("method_calls.jsonl");

    /**
     * Initializes the logger with the specified log file path.
     * This is called by the agent's premain method.
     *
     * @param logFile The path to the log file.
     */
    public static void init(String logFile) {
        LOG_FILE = Paths.get(logFile);
    }

    /**
     * Gets the path to the log file.
     *
     * @return The log file path.
     */
    public static String getLogFile() {
        return LOG_FILE.toString();
    }

    /**
     * This method is executed "on method enter" (before the original method's code).
     * It records the start time.
     */
    @Advice.OnMethodEnter
    public static void onEnter() {
        // 1. Record the start time
        startTimeStack.get().push(System.nanoTime());
    }

    /**
     * This method is executed "on method exit" (after the original method's code).
     * It logs everything to the JSON file.
     *
     * @param method    The method that was executed.
     * @param allArgs   All arguments passed to the method.
     * @param returned  The value returned by the method.
     * @param thrown    The exception thrown by the method, if any.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Origin Method method,
            @Advice.AllArguments Object[] allArgs,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned, // Handle void methods
            @Advice.Thrown Throwable thrown // Handle exceptions
    ) {
        // 1. Calculate duration
        long durationNanos = 0;

        // 2. Get the accurate caller by walking the stack trace
        String caller = getCallerMethod();

        // 3. Build the log entry
        Map<String, Object> logEntry = new HashMap<>();
        logEntry.put("time", Instant.now().toString());
        logEntry.put("caller", caller);

        // Check for null package (e.g., default package)
        Package pkg = method.getDeclaringClass().getPackage();
        logEntry.put("package", pkg != null ? pkg.getName() : "default");

        logEntry.put("class", method.getDeclaringClass().getSimpleName());
        logEntry.put("method", method.getName());
        logEntry.put("threadName", Thread.currentThread().getName());

        // 4. Serialize parameters
        Map<String, String> params = new HashMap<>();
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            String paramName = parameters[i].getName(); // e.g., "arg0", "arg1"
            Object paramValue = (i < allArgs.length) ? allArgs[i] : null;
            params.put(paramName, safeSerialize(paramValue));
        }
        logEntry.put("params", params);

        // 5. Handle return value or exception
        if (thrown != null) {
            logEntry.put("returnType", "EXCEPTION");
            logEntry.put("returnData", safeSerialize(thrown));
        } else {
            logEntry.put("returnType", method.getReturnType().getName());
            logEntry.put("returnData", safeSerialize(returned));
        }

        logEntry.put("durationNanos", durationNanos);

        // 6. Write the log entry to the file
        writeLog(logEntry);
    }

    /**
     * Finds the calling method by walking the stack trace.
     * This is slow but accurate, as requested.
     *
     * @return The fully-qualified name of the caller method.
     */
    public static String getCallerMethod() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        String agentClassName = MethodLoggingAdvice.class.getName();

        // Walk the stack to find the *first* method *outside* of our agent
        for (int i = 1; i < stack.length; i++) {
            StackTraceElement frame = stack[i];
            // 1. Skip all frames from our own advice class
            if (!frame.getClassName().equals(agentClassName)) {
                // 2. The first frame *after* our agent is the instrumented method.
                //    The frame *after that* is the actual caller.
                int callerIndex = i + 1;
                if (callerIndex < stack.length) {
                    StackTraceElement callerFrame = stack[callerIndex];
                    return callerFrame.getClassName() + "." + callerFrame.getMethodName();
                }
                // If there is no frame after, the instrumented method was the entry point
                return "ENTRYPOINT_OF_THREAD";
            }
        }
        return "UNKNOWN_CALLER";
    }


    /**
     * Safely serializes an object to a JSON string.
     * If serialization fails (e.g., due to circular references or inaccessible fields),
     * it falls back to the object's toString() representation.
     *
     * @param obj The object to serialize.
     * @return A JSON string or a fallback string.
     */
    public static String safeSerialize(Object obj) {
        if (obj == null) {
            return "null";
        }
        try {
            // Try to serialize using Gson
            return GSON.toJson(obj);
        } catch (Exception e) {
            // Fallback to toString() if serialization fails
            return obj.toString();
        }
    }

    /**
     * Writes the log entry (as a JSON string) to the log file.
     * This method is synchronized to prevent multiple threads from writing at the same time.
     *
     * @param logEntry The map containing the log data.
     */
    public static synchronized void writeLog(Map<String, Object> logEntry) {
        try {
            String jsonLog = GSON.toJson(logEntry) + "\n";
            Files.writeString(LOG_FILE, jsonLog, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
        } catch (IOException e) {
            System.err.println("[MethodLoggerAgent] ERROR: Failed to write to log file: " + e.getMessage());
        }
    }
}

