package com.datmt.agent;

import com.google.gson.Gson;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * This class contains the logic that will be "injected" into the target methods.
 * We use Byte Buddy's Advice annotations to run code at the beginning and end
 * of the original method.
 */
public class MethodLoggingAdvice {

    // Using a ThreadLocal Deque (stack) to handle nested method calls correctly.
    public static final ThreadLocal<Deque<Long>> startTimeStack = ThreadLocal.withInitial(ArrayDeque::new);

    // Gson instance for JSON conversion. Make it static for efficiency.
    public static final Gson gson = new Gson();

    // The file to write to. This will be set by the agent.
    public static String LOG_FILE = "method_calls.jsonl"; // Default value

    /**
     * Initializes the advice class with agent arguments.
     * @param logFilePath The path to the log file, passed from agentArgs.
     */
    public static void init(String logFilePath) {
        if (logFilePath != null && !logFilePath.isEmpty()) {
            LOG_FILE = logFilePath;
        }
    }

    /**
     * Gets the currently configured log file path.
     * @return The log file path.
     */
    public static String getLogFile() {
        return LOG_FILE;
    }

    /**
     * This method is executed *before* the original method body.
     *
     * @param method The intercepted method (java.lang.reflect.Method)
     * @param args   An array of all arguments passed to the method
     */
    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.Origin java.lang.reflect.Method method,
            @Advice.AllArguments Object[] args
    ) {
        // Push the start time onto the stack for this thread
        try {
            startTimeStack.get().push(System.nanoTime());
        } catch (Exception e) {
            // Safety catch
            System.err.println("[MethodLoggerAgent] Error in onEnter: " + e.getMessage());
        }
    }

    /**
     * This method is executed *after* the original method body, whether it
     * returns normally or throws an exception.
     *
     * @param method    The intercepted method
     * @param args      An array of all arguments
     * @param returned  The value returned from the method. Null if void or constructor.
     * @param thrown    The exception thrown, or null if no exception.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Origin java.lang.reflect.Method method,
            @Advice.AllArguments Object[] args,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned, // <-- THE CORRECT FIX IS HERE
            @Advice.Thrown Throwable thrown
    ) {
        try {
            // Pop the start time from the stack
            Deque<Long> stack = startTimeStack.get();
            Long startTime = stack.poll(); // poll() returns null if empty, pop() throws exception

            long durationNanos = 0;
            if (startTime != null) {
                durationNanos = System.nanoTime() - startTime;
            }

            if (stack.isEmpty()) {
                // Clean up the ThreadLocal for this thread if the stack is empty
                startTimeStack.remove();
            }

            // 1. Collect parameter names and values
            Map<String, Object> params = new HashMap<>();
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                String paramName = parameters[i].getName(); // Can still be "arg0", "arg1" if -parameters flag not set
                Object paramValue = (i < args.length) ? args[i] : null;
                // Use safe serialization
                params.put(paramName, safeSerialize(paramValue));
            }

            // 2. Determine return type and value
            String returnType = method.getReturnType().getName();
            Object returnValue;

            if (thrown != null) {
                returnType = "EXCEPTION";
                returnValue = thrown;
            } else {
                returnValue = returned;
            }

            // 3. Create the data object to be logged
            Map<String, Object> logEntry = new HashMap<>();
            logEntry.put("time", Instant.now().toString());

            // Safely get the package name to avoid NPE for default package
            Package pkg = method.getDeclaringClass().getPackage();
            logEntry.put("package", (pkg != null) ? pkg.getName() : "default");

            logEntry.put("class", method.getDeclaringClass().getSimpleName());
            logEntry.put("method", method.getName());
            logEntry.put("params", params);
            logEntry.put("returnType", returnType);
            // Use safe serialization for the return value
            logEntry.put("returnData", safeSerialize(returnValue));
            logEntry.put("durationNanos", durationNanos);
            logEntry.put("thread", Thread.currentThread().getName());

            // 4. Serialize to JSON and write to file
            String jsonLog = gson.toJson(logEntry);
            writeLog(jsonLog);

        } catch (Exception e) {
            // Catch all exceptions in advice code to prevent crashing the target app
            System.err.println("[MethodLoggerAgent] Error in onExit: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    /**
     * Safely serializes an object to a String for JSON logging.
     * It tries to use Gson, but falls back to a sanitized toString()
     * if serialization fails (e.g., on internal JDK classes).
     *
     * @param obj The object to serialize.
     * @return A String (for primitive) or JSON String representation.
     */
    public static Object safeSerialize(Object obj) {
        if (obj == null) {
            return "null";
        }
        try {
            // Check for common simple types that Gson handles well
            if (obj instanceof String || obj instanceof Number || obj instanceof Boolean) {
                return obj;
            }
            // Try to serialize with Gson
            return gson.toJson(obj);
        } catch (Throwable t) {
            // If serialization fails (e.g., circular reference, inaccessible fields)
            // fall back to a simple toString().
            try {
                return obj.toString();
            } catch (Throwable t2) {
                return "Error in toString(): " + t2.getMessage();
            }
        }
    }

    /**
     * Writes the log string to a file.
     * NOTE: This is a simple, synchronous implementation.
     * For high-performance applications, you MUST use an asynchronous,
     * buffered logger (like Logback, Log4j, or a custom queue) to avoid
     * severe performance bottlenecks.
     *
     * @param logData The JSON string to write.
     */
    public static synchronized void writeLog(String logData) {
        // We use synchronized to prevent multiple threads from writing at the exact same time
        // and corrupting the file. This is a *major* bottleneck.
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
            writer.write(logData);
            writer.newLine();
        } catch (IOException e) {
            System.err.println("[MethodLoggerAgent] Failed to write log: " + e.getMessage());
        }
    }
}

