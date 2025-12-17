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
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This class contains the "advice" logic that will be woven into the target methods.
 * It's responsible for logging method entry, exit, parameters, and return values.
 */
public class MethodLoggingAdvice {

    /**
     * Context information for tracking method calls in a hierarchy.
     */
    static class CallContext {
        final String callId;
        final String parentCallId;
        final int depth;
        final long startTimeNanos;
        final long threadId;

        CallContext(String callId, String parentCallId, int depth, long startTimeNanos) {
            this.callId = callId;
            this.parentCallId = parentCallId;
            this.depth = depth;
            this.startTimeNanos = startTimeNanos;
            this.threadId = Thread.currentThread().getId();
        }
    }

    // A thread-local stack to store call context for nested calls
    public static final ThreadLocal<Deque<CallContext>> callContextStack = ThreadLocal.withInitial(LinkedList::new);

    // Use a thread-safe, static Gson instance
    // Disabling HTML escaping prevents strings like "<" from becoming "\u003c"
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    // The path to the log file, set by the agent premain
    public static Path LOG_FILE = Paths.get("method_calls.jsonl");
    public static Integer callerDepth = 1;

    // HTML output file
    public static Path HTML_FILE = Paths.get("method_calls.html");

    // Sequential call ID generator
    private static final AtomicLong callIdGenerator = new AtomicLong(0);

    // Flag to track if HTML file has been initialized
    private static volatile boolean htmlInitialized = false;

    // Lock for HTML file initialization
    private static final Object htmlInitLock = new Object();

    // Lock for HTML file writing
    private static final Object htmlWriteLock = new Object();

    /**
     * Initializes the logger with the specified log file path and HTML file path.
     * This is called by the agent's premain method.
     *
     * @param logFile The path to the JSONL log file.
     * @param htmlFile The path to the HTML output file.
     * @param cd The caller depth.
     */
    public static void init(String logFile, String htmlFile, int cd) {
        LOG_FILE = Paths.get(logFile);
        HTML_FILE = Paths.get(htmlFile);
        callerDepth = cd;

        // Register shutdown hook to close HTML file
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            closeHtmlFile();
        }));
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
     * It creates a call context with ID, parent ID, depth, and start time.
     */
    @Advice.OnMethodEnter
    public static void onEnter() {
        Deque<CallContext> stack = callContextStack.get();

        // Generate unique call ID
        String callId = "CALL-" + callIdGenerator.incrementAndGet();

        // Get parent call ID from stack (if exists)
        String parentCallId = null;
        if (!stack.isEmpty()) {
            parentCallId = stack.peek().callId;
        }

        // Calculate depth
        int depth = stack.size();

        // Record start time
        long startTime = System.nanoTime();

        // Create and push context
        CallContext context = new CallContext(callId, parentCallId, depth, startTime);
        stack.push(context);
    }

    /**
     * This method is executed "on method exit" (after the original method's code).
     * It logs everything to both the JSONL and HTML files.
     *
     * @param method   The method that was executed.
     * @param allArgs  All arguments passed to the method.
     * @param returned The value returned by the method.
     * @param thrown   The exception thrown by the method, if any.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Origin Method method,
            @Advice.AllArguments Object[] allArgs,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned, // Handle void methods
            @Advice.Thrown Throwable thrown // Handle exceptions
    ) {
        // 1. Pop context from stack
        Deque<CallContext> stack = callContextStack.get();
        if (stack.isEmpty()) {
            // Should never happen, but handle gracefully
            System.err.println("[MethodLoggerAgent] ERROR: Call stack is empty in onExit");
            return;
        }

        CallContext context = stack.pop();

        // 2. Calculate duration (FIX THE BUG!)
        long durationNanos = System.nanoTime() - context.startTimeNanos;

        // 3. Get the accurate caller by walking the stack trace
        String callers = getCallerMethods(callerDepth);

        // 4. Build the log entry
        Map<String, Object> logEntry = new HashMap<>();
        logEntry.put("callId", context.callId);
        logEntry.put("parentCallId", context.parentCallId);
        logEntry.put("depth", context.depth);
        logEntry.put("threadId", context.threadId);
        logEntry.put("time", Instant.now().toString());
        logEntry.put("callers", callers);

        // Check for null package (e.g., default package)
        Package pkg = method.getDeclaringClass().getPackage();
        logEntry.put("package", pkg != null ? pkg.getName() : "default");

        logEntry.put("class", method.getDeclaringClass().getSimpleName());
        logEntry.put("method", method.getName());
        logEntry.put("threadName", Thread.currentThread().getName());

        // 5. Serialize parameters
        Map<String, String> params = new HashMap<>();
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            String paramName = parameters[i].getName(); // e.g., "arg0", "arg1"
            Object paramValue = (i < allArgs.length) ? allArgs[i] : null;
            params.put(paramName, safeSerialize(paramValue));
        }
        logEntry.put("params", params);

        // 6. Handle return value or exception
        if (thrown != null) {
            logEntry.put("returnType", "EXCEPTION");
            logEntry.put("returnData", safeSerialize(thrown));
        } else {
            logEntry.put("returnType", method.getReturnType().getName());
            logEntry.put("returnData", safeSerialize(returned));
        }

        logEntry.put("durationNanos", durationNanos);

        // 7. Write to both JSONL and HTML files
        writeLog(logEntry);
        writeHtmlLog(logEntry);
    }

    /**
     * Finds the calling method by walking the stack trace.
     * This is slow but accurate, as requested.
     *
     * @return The fully-qualified name of the caller method.
     */
    public static String getCallerMethods(int callerDepth) {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        String agentClassName = MethodLoggingAdvice.class.getName();
        List<String> callers = new ArrayList<>();

        boolean foundInstrumentedMethod = false;

        // Walk the stack to collect callers
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();

            // Skip the agent's advice class frames
            if (className.equals(agentClassName)) {
                continue;
            }

            // Skip Thread.getStackTrace() and this method itself
            if (className.equals(Thread.class.getName()) ||
                    frame.getMethodName().equals("getCallerMethod")) {
                continue;
            }

            if (!foundInstrumentedMethod) {
                // First non-agent frame is the instrumented method itself - skip it
                foundInstrumentedMethod = true;
                continue;
            }

            // Now we're collecting actual callers
            String caller = className + "." + frame.getMethodName() +
                    ":" + frame.getLineNumber();
            callers.add(caller);

            // Stop when we have enough callers
            if (callers.size() >= callerDepth) {
                break;
            }
        }

        if (callers.isEmpty()) {
            return "ENTRYPOINT_OF_THREAD";
        }

        return String.join(" <- ", callers);
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

    /**
     * Initializes the HTML file with header, CSS, and JavaScript.
     */
    private static void initHtmlFile() {
        synchronized (htmlInitLock) {
            if (htmlInitialized) {
                return;
            }

            try {
                String htmlHeader = generateHtmlHeader();
                Files.writeString(HTML_FILE, htmlHeader,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                htmlInitialized = true;
                System.out.println("[MethodLoggerAgent] HTML file initialized: " + HTML_FILE);
            } catch (IOException e) {
                System.err.println("[MethodLoggerAgent] ERROR: Failed to initialize HTML file: " + e.getMessage());
            }
        }
    }

    /**
     * Appends a method call entry to the HTML file.
     *
     * @param logEntry The map containing the log data.
     */
    private static void writeHtmlLog(Map<String, Object> logEntry) {
        // Lazy initialization
        if (!htmlInitialized) {
            initHtmlFile();
        }

        synchronized (htmlWriteLock) {
            try {
                String jsonEntry = GSON.toJson(logEntry);
                String jsCode = "            " + jsonEntry + ",\n";
                Files.writeString(HTML_FILE, jsCode, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("[MethodLoggerAgent] ERROR: Failed to write to HTML file: " + e.getMessage());
            }
        }
    }

    /**
     * Closes the HTML file by adding the closing JavaScript and HTML tags.
     */
    private static void closeHtmlFile() {
        if (!htmlInitialized) {
            return;
        }

        synchronized (htmlWriteLock) {
            try {
                String htmlFooter = generateHtmlFooter();
                Files.writeString(HTML_FILE, htmlFooter, StandardOpenOption.APPEND);
                System.out.println("[MethodLoggerAgent] HTML file closed successfully");
            } catch (IOException e) {
                System.err.println("[MethodLoggerAgent] ERROR: Failed to close HTML file: " + e.getMessage());
            }
        }
    }

    /**
     * Generates the HTML header with CSS and JavaScript.
     *
     * @return The HTML header string.
     */
    private static String generateHtmlHeader() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Method Call Tree - Java Logging Agent</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            background: #f5f5f5;
            padding: 20px;
        }
        #header {
            background: white;
            padding: 20px;
            margin-bottom: 20px;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }
        h1 { color: #333; margin-bottom: 10px; }
        .stats { color: #666; font-size: 14px; }
        #tree-container {
            background: white;
            padding: 20px;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            overflow-x: auto;
        }
        .tree { list-style: none; }
        .tree ul { list-style: none; margin-left: 30px; }
        .tree li {
            margin: 5px 0;
            position: relative;
        }
        .tree-node {
            padding: 10px;
            border: 1px solid #ddd;
            border-radius: 4px;
            background: #fafafa;
            cursor: pointer;
            transition: background 0.2s;
        }
        .tree-node:hover {
            background: #f0f0f0;
        }
        .tree-node.expanded {
            background: #e8f4f8;
        }
        .method-signature {
            font-weight: bold;
            color: #2c3e50;
            margin-bottom: 5px;
        }
        .method-info {
            font-size: 12px;
            color: #666;
            margin: 3px 0;
        }
        .method-details {
            margin-top: 10px;
            padding: 10px;
            background: white;
            border-left: 3px solid #3498db;
            font-size: 12px;
            display: block;
        }
        .method-details.hidden {
            display: none;
        }
        .param-list, .return-info {
            margin: 5px 0;
        }
        .param-name {
            font-weight: bold;
            color: #e74c3c;
        }
        .duration {
            color: #27ae60;
            font-weight: bold;
        }
        .thread-name {
            color: #9b59b6;
            font-style: italic;
        }
        .toggle-btn {
            display: inline-block;
            width: 20px;
            height: 20px;
            line-height: 20px;
            text-align: center;
            background: #3498db;
            color: white;
            border-radius: 3px;
            margin-right: 5px;
            user-select: none;
        }
        .children-container {
            display: block;
        }
        .children-container.collapsed {
            display: none;
        }
    </style>
</head>
<body>
    <div id="header">
        <h1>Method Call Tree Visualization</h1>
        <div class="stats" id="stats">Loading...</div>
    </div>
    <div id="tree-container">
        <div id="tree"></div>
    </div>

    <script>
        // Method calls data array
        var methodCalls = [
""";
    }

    /**
     * Generates the HTML footer with tree-building JavaScript.
     *
     * @return The HTML footer string.
     */
    private static String generateHtmlFooter() {
        return """
        ];

        // Build tree structure from flat method calls
        function buildTree() {
            if (methodCalls.length === 0) {
                document.getElementById('tree').innerHTML = '<p>No method calls recorded</p>';
                return;
            }

            // Update statistics
            updateStats();

            // Create lookup map
            const callMap = new Map();
            methodCalls.forEach(call => {
                callMap.set(call.callId, {
                    ...call,
                    children: []
                });
            });

            // Build parent-child relationships
            const roots = [];
            methodCalls.forEach(call => {
                const node = callMap.get(call.callId);
                if (call.parentCallId && callMap.has(call.parentCallId)) {
                    callMap.get(call.parentCallId).children.push(node);
                } else {
                    roots.push(node);
                }
            });

            // Render tree
            const treeHtml = roots.map(root => renderNode(root)).join('');
            document.getElementById('tree').innerHTML = '<ul class="tree">' + treeHtml + '</ul>';

            // Add event listeners
            attachEventListeners();
        }

        function renderNode(node) {
            const hasChildren = node.children.length > 0;
            const toggleBtn = hasChildren ? '<span class="toggle-btn">-</span>' : '<span class="toggle-btn" style="visibility:hidden">-</span>';

            const durationMs = (node.durationNanos / 1000000).toFixed(2);
            const params = formatParams(node.params);
            const returnInfo = formatReturn(node.returnType, node.returnData);

            let html = '<li>';
            html += '<div class="tree-node expanded" data-call-id="' + node.callId + '">';
            html += toggleBtn;
            html += '<div class="method-signature">' + escapeHtml(node.package + '.' + node.class + '.' + node.method) + '()</div>';
            html += '<div class="method-info"><span class="duration">' + durationMs + ' ms</span> | ';
            html += '<span class="thread-name">' + escapeHtml(node.threadName) + '</span> | ';
            html += 'Call ID: ' + escapeHtml(node.callId) + '</div>';
            html += '<div class="method-details">';
            html += '<div><strong>Time:</strong> ' + escapeHtml(node.time) + '</div>';
            html += '<div><strong>Callers:</strong> ' + escapeHtml(node.callers) + '</div>';
            html += '<div class="param-list"><strong>Parameters:</strong> ' + params + '</div>';
            html += '<div class="return-info"><strong>Return:</strong> ' + returnInfo + '</div>';
            html += '</div>';
            html += '</div>';

            if (hasChildren) {
                html += '<ul class="children-container">';
                node.children.forEach(child => {
                    html += renderNode(child);
                });
                html += '</ul>';
            }

            html += '</li>';
            return html;
        }

        function formatParams(params) {
            if (!params || Object.keys(params).length === 0) {
                return '<em>none</em>';
            }
            return Object.entries(params).map(([name, value]) =>
                '<span class="param-name">' + escapeHtml(name) + '</span>: ' + escapeHtml(value)
            ).join(', ');
        }

        function formatReturn(returnType, returnData) {
            if (returnType === 'EXCEPTION') {
                return '<span style="color: red;">Exception: ' + escapeHtml(returnData) + '</span>';
            }
            return '<span>' + escapeHtml(returnType) + ' = ' + escapeHtml(returnData) + '</span>';
        }

        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text || '';
            return div.innerHTML;
        }

        function updateStats() {
            const totalCalls = methodCalls.length;
            const threads = new Set(methodCalls.map(c => c.threadName)).size;
            const avgDuration = (methodCalls.reduce((sum, c) => sum + c.durationNanos, 0) / totalCalls / 1000000).toFixed(2);

            document.getElementById('stats').innerHTML =
                'Total Calls: <strong>' + totalCalls + '</strong> | ' +
                'Threads: <strong>' + threads + '</strong> | ' +
                'Avg Duration: <strong>' + avgDuration + ' ms</strong>';
        }

        function attachEventListeners() {
            document.querySelectorAll('.tree-node').forEach(node => {
                node.addEventListener('click', function(e) {
                    e.stopPropagation();

                    // Toggle details
                    const details = this.querySelector('.method-details');
                    if (details) {
                        details.classList.toggle('hidden');
                    }

                    // Toggle children
                    const children = this.parentElement.querySelector('.children-container');
                    const toggleBtn = this.querySelector('.toggle-btn');
                    if (children && toggleBtn) {
                        children.classList.toggle('collapsed');
                        toggleBtn.textContent = children.classList.contains('collapsed') ? '+' : '-';
                        this.classList.toggle('expanded');
                    }
                });
            });
        }

        // Build tree on load
        window.addEventListener('load', buildTree);
    </script>
</body>
</html>
""";
    }
}

