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
    public static class CallContext {
        public final String callId;
        public final String parentCallId;
        public final int depth;
        public final long startTimeNanos;
        public final long threadId;

        public CallContext(String callId, String parentCallId, int depth, long startTimeNanos) {
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
    public static final AtomicLong callIdGenerator = new AtomicLong(0);

    // Flag to track if HTML file has been initialized
    public static volatile boolean htmlInitialized = false;

    // Lock for HTML file initialization
    public static final Object htmlInitLock = new Object();

    // Lock for HTML file writing
    public static final Object htmlWriteLock = new Object();

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

        // 5. Serialize parameters (names only to avoid JSON escaping issues in HTML)
        Map<String, String> params = new HashMap<>();
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            String paramName = parameters[i].getName(); // e.g., "arg0", "arg1"
            // Just store parameter type instead of value to avoid escaping issues
            String paramType = (i < allArgs.length && allArgs[i] != null) ?
                allArgs[i].getClass().getSimpleName() : "null";
            params.put(paramName, paramType);
        }
        logEntry.put("params", params);

        // 6. Handle return value or exception (type only to avoid escaping issues)
        if (thrown != null) {
            logEntry.put("returnType", "EXCEPTION");
            logEntry.put("returnData", thrown.getClass().getSimpleName());
        } else {
            logEntry.put("returnType", method.getReturnType().getSimpleName());
            logEntry.put("returnData", (returned != null) ? returned.getClass().getSimpleName() : "null");
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
    public static void initHtmlFile() {
        synchronized (htmlInitLock) {
            if (htmlInitialized) {
                return;
            }

            try {
                String htmlHeader = generateHtmlHeader();
                String htmlFooter = generateHtmlFooter();
                // Write complete HTML structure
                Files.writeString(HTML_FILE, htmlHeader + htmlFooter,
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
    public static void writeHtmlLog(Map<String, Object> logEntry) {
        // Lazy initialization
        if (!htmlInitialized) {
            initHtmlFile();
        }

        synchronized (htmlWriteLock) {
            try {
                String jsonEntry = GSON.toJson(logEntry);
                String pushStatement = "        methodCalls.push(" + jsonEntry + ");\n";

                // Read current content
                String content = Files.readString(HTML_FILE);

                // Insert before closing script tag
                int insertPos = content.lastIndexOf("    </script>");
                if (insertPos > 0) {
                    String newContent = content.substring(0, insertPos) +
                                      pushStatement +
                                      content.substring(insertPos);
                    Files.writeString(HTML_FILE, newContent);
                }
            } catch (IOException e) {
                System.err.println("[MethodLoggerAgent] ERROR: Failed to write to HTML file: " + e.getMessage());
            }
        }
    }

    /**
     * Closes the HTML file by adding the closing JavaScript and HTML tags.
     */
    public static void closeHtmlFile() {
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
    public static String generateHtmlHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"en\">\n");
        sb.append("<head>\n");
        sb.append("    <meta charset=\"UTF-8\">\n");
        sb.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("    <title>Method Call Tree - Java Logging Agent</title>\n");
        sb.append("    <style>\n");
        sb.append("        * { margin: 0; padding: 0; box-sizing: border-box; }\n");
        sb.append("        body {\n");
        sb.append("            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;\n");
        sb.append("            background: #f5f5f5;\n");
        sb.append("            padding: 20px;\n");
        sb.append("        }\n");
        sb.append("        #header {\n");
        sb.append("            background: white;\n");
        sb.append("            padding: 20px;\n");
        sb.append("            margin-bottom: 20px;\n");
        sb.append("            border-radius: 8px;\n");
        sb.append("            box-shadow: 0 2px 4px rgba(0,0,0,0.1);\n");
        sb.append("        }\n");
        sb.append("        h1 { color: #333; margin-bottom: 10px; }\n");
        sb.append("        .stats { color: #666; font-size: 14px; }\n");
        sb.append("        #tree-container {\n");
        sb.append("            background: white;\n");
        sb.append("            padding: 20px;\n");
        sb.append("            border-radius: 8px;\n");
        sb.append("            box-shadow: 0 2px 4px rgba(0,0,0,0.1);\n");
        sb.append("            overflow-x: auto;\n");
        sb.append("        }\n");
        sb.append("        .tree { list-style: none; }\n");
        sb.append("        .tree ul { list-style: none; margin-left: 30px; }\n");
        sb.append("        .tree li {\n");
        sb.append("            margin: 5px 0;\n");
        sb.append("            position: relative;\n");
        sb.append("        }\n");
        sb.append("        .tree-node {\n");
        sb.append("            padding: 10px;\n");
        sb.append("            border: 1px solid #ddd;\n");
        sb.append("            border-radius: 4px;\n");
        sb.append("            background: #fafafa;\n");
        sb.append("            cursor: pointer;\n");
        sb.append("            transition: background 0.2s;\n");
        sb.append("        }\n");
        sb.append("        .tree-node:hover {\n");
        sb.append("            background: #f0f0f0;\n");
        sb.append("        }\n");
        sb.append("        .tree-node.expanded {\n");
        sb.append("            background: #e8f4f8;\n");
        sb.append("        }\n");
        sb.append("        .method-signature {\n");
        sb.append("            font-weight: bold;\n");
        sb.append("            color: #2c3e50;\n");
        sb.append("            margin-bottom: 5px;\n");
        sb.append("        }\n");
        sb.append("        .method-info {\n");
        sb.append("            font-size: 12px;\n");
        sb.append("            color: #666;\n");
        sb.append("            margin: 3px 0;\n");
        sb.append("        }\n");
        sb.append("        .method-details {\n");
        sb.append("            margin-top: 10px;\n");
        sb.append("            padding: 10px;\n");
        sb.append("            background: white;\n");
        sb.append("            border-left: 3px solid #3498db;\n");
        sb.append("            font-size: 12px;\n");
        sb.append("            display: block;\n");
        sb.append("        }\n");
        sb.append("        .method-details.hidden {\n");
        sb.append("            display: none;\n");
        sb.append("        }\n");
        sb.append("        .param-list, .return-info {\n");
        sb.append("            margin: 5px 0;\n");
        sb.append("        }\n");
        sb.append("        .param-name {\n");
        sb.append("            font-weight: bold;\n");
        sb.append("            color: #e74c3c;\n");
        sb.append("        }\n");
        sb.append("        .duration {\n");
        sb.append("            color: #27ae60;\n");
        sb.append("            font-weight: bold;\n");
        sb.append("        }\n");
        sb.append("        .thread-name {\n");
        sb.append("            color: #9b59b6;\n");
        sb.append("            font-style: italic;\n");
        sb.append("        }\n");
        sb.append("        .toggle-btn {\n");
        sb.append("            display: inline-block;\n");
        sb.append("            width: 20px;\n");
        sb.append("            height: 20px;\n");
        sb.append("            line-height: 20px;\n");
        sb.append("            text-align: center;\n");
        sb.append("            background: #3498db;\n");
        sb.append("            color: white;\n");
        sb.append("            border-radius: 3px;\n");
        sb.append("            margin-right: 5px;\n");
        sb.append("            user-select: none;\n");
        sb.append("        }\n");
        sb.append("        .children-container {\n");
        sb.append("            display: block;\n");
        sb.append("        }\n");
        sb.append("        .children-container.collapsed {\n");
        sb.append("            display: none;\n");
        sb.append("        }\n");
        sb.append("    </style>\n");
        sb.append("</head>\n");
        sb.append("<body>\n");
        sb.append("    <div id=\"header\">\n");
        sb.append("        <h1>Method Call Tree Visualization</h1>\n");
        sb.append("        <div class=\"stats\" id=\"stats\">Loading...</div>\n");
        sb.append("    </div>\n");
        sb.append("    <div id=\"tree-container\">\n");
        sb.append("        <div id=\"tree\"></div>\n");
        sb.append("    </div>\n");
        sb.append("\n");
        sb.append("    <script>\n");
        sb.append("        var methodCalls = [];\n");
        sb.append("        var lastCount = 0;\n");
        sb.append("\n");
        sb.append("        function buildTree() {\n");
        sb.append("            if (methodCalls.length === 0) {\n");
        sb.append("                document.getElementById('tree').innerHTML = '<p>No method calls recorded</p>';\n");
        sb.append("                return;\n");
        sb.append("            }\n");
        sb.append("            if (methodCalls.length === lastCount) return;\n");
        sb.append("            lastCount = methodCalls.length;\n");
        sb.append("\n");
        sb.append("            updateStats();\n");
        sb.append("            const callMap = new Map();\n");
        sb.append("            methodCalls.forEach(call => {\n");
        sb.append("                callMap.set(call.callId, Object.assign({}, call, { children: [] }));\n");
        sb.append("            });\n");
        sb.append("            const roots = [];\n");
        sb.append("            methodCalls.forEach(call => {\n");
        sb.append("                const node = callMap.get(call.callId);\n");
        sb.append("                if (call.parentCallId && callMap.has(call.parentCallId)) {\n");
        sb.append("                    callMap.get(call.parentCallId).children.push(node);\n");
        sb.append("                } else {\n");
        sb.append("                    roots.push(node);\n");
        sb.append("                }\n");
        sb.append("            });\n");
        sb.append("            const treeHtml = roots.map(function(root) { return renderNode(root); }).join('');\n");
        sb.append("            document.getElementById('tree').innerHTML = '<ul class=\"tree\">' + treeHtml + '</ul>';\n");
        sb.append("            attachEventListeners();\n");
        sb.append("        }\n");
        sb.append("\n");
        sb.append("        function renderNode(node) {\n");
        sb.append("            var hasChildren = node.children.length > 0;\n");
        sb.append("            var toggleBtn = hasChildren ? '<span class=\"toggle-btn\">-</span>' : '<span class=\"toggle-btn\" style=\"visibility:hidden\">-</span>';\n");
        sb.append("            var durationMs = (node.durationNanos / 1000000).toFixed(2);\n");
        sb.append("            var params = formatParams(node.params);\n");
        sb.append("            var returnInfo = formatReturn(node.returnType, node.returnData);\n");
        sb.append("            var html = '<li>';\n");
        sb.append("            html += '<div class=\"tree-node expanded\" data-call-id=\"' + node.callId + '\">';\n");
        sb.append("            html += toggleBtn;\n");
        sb.append("            html += '<div class=\"method-signature\">' + escapeHtml(node.package + '.' + node.class + '.' + node.method) + '()</div>';\n");
        sb.append("            html += '<div class=\"method-info\"><span class=\"duration\">' + durationMs + ' ms</span> | ';\n");
        sb.append("            html += '<span class=\"thread-name\">' + escapeHtml(node.threadName) + '</span> | ';\n");
        sb.append("            html += 'Call ID: ' + escapeHtml(node.callId) + '</div>';\n");
        sb.append("            html += '<div class=\"method-details\">';\n");
        sb.append("            html += '<div><strong>Time:</strong> ' + escapeHtml(node.time) + '</div>';\n");
        sb.append("            html += '<div><strong>Callers:</strong> ' + escapeHtml(node.callers) + '</div>';\n");
        sb.append("            html += '<div class=\"param-list\"><strong>Parameters:</strong> ' + params + '</div>';\n");
        sb.append("            html += '<div class=\"return-info\"><strong>Return:</strong> ' + returnInfo + '</div>';\n");
        sb.append("            html += '</div></div>';\n");
        sb.append("            if (hasChildren) {\n");
        sb.append("                html += '<ul class=\"children-container\">';\n");
        sb.append("                for (var i = 0; i < node.children.length; i++) {\n");
        sb.append("                    html += renderNode(node.children[i]);\n");
        sb.append("                }\n");
        sb.append("                html += '</ul>';\n");
        sb.append("            }\n");
        sb.append("            html += '</li>';\n");
        sb.append("            return html;\n");
        sb.append("        }\n");
        sb.append("\n");
        sb.append("        function formatParams(params) {\n");
        sb.append("            if (!params || Object.keys(params).length === 0) return '<em>none</em>';\n");
        sb.append("            return Object.keys(params).map(function(name) {\n");
        sb.append("                return '<span class=\"param-name\">' + escapeHtml(name) + '</span>: ' + escapeHtml(params[name]);\n");
        sb.append("            }).join(', ');\n");
        sb.append("        }\n");
        sb.append("\n");
        sb.append("        function formatReturn(returnType, returnData) {\n");
        sb.append("            if (returnType === 'EXCEPTION') {\n");
        sb.append("                return '<span style=\"color: red;\">Exception: ' + escapeHtml(returnData) + '</span>';\n");
        sb.append("            }\n");
        sb.append("            return '<span>' + escapeHtml(returnType) + ' = ' + escapeHtml(returnData) + '</span>';\n");
        sb.append("        }\n");
        sb.append("\n");
        sb.append("        function escapeHtml(text) {\n");
        sb.append("            var div = document.createElement('div');\n");
        sb.append("            div.textContent = text || '';\n");
        sb.append("            return div.innerHTML;\n");
        sb.append("        }\n");
        sb.append("\n");
        sb.append("        function updateStats() {\n");
        sb.append("            var totalCalls = methodCalls.length;\n");
        sb.append("            var threads = new Set(methodCalls.map(function(c) { return c.threadName; })).size;\n");
        sb.append("            var avgDuration = totalCalls > 0 ? (methodCalls.reduce(function(sum, c) { return sum + c.durationNanos; }, 0) / totalCalls / 1000000).toFixed(2) : 0;\n");
        sb.append("            document.getElementById('stats').innerHTML = 'Total Calls: <strong>' + totalCalls + '</strong> | Threads: <strong>' + threads + '</strong> | Avg Duration: <strong>' + avgDuration + ' ms</strong>';\n");
        sb.append("        }\n");
        sb.append("\n");
        sb.append("        function attachEventListeners() {\n");
        sb.append("            document.querySelectorAll('.tree-node').forEach(function(node) {\n");
        sb.append("                node.addEventListener('click', function(e) {\n");
        sb.append("                    e.stopPropagation();\n");
        sb.append("                    var details = this.querySelector('.method-details');\n");
        sb.append("                    if (details) details.classList.toggle('hidden');\n");
        sb.append("                    var children = this.parentElement.querySelector('.children-container');\n");
        sb.append("                    var toggleBtn = this.querySelector('.toggle-btn');\n");
        sb.append("                    if (children && toggleBtn) {\n");
        sb.append("                        children.classList.toggle('collapsed');\n");
        sb.append("                        toggleBtn.textContent = children.classList.contains('collapsed') ? '+' : '-';\n");
        sb.append("                        this.classList.toggle('expanded');\n");
        sb.append("                    }\n");
        sb.append("                });\n");
        sb.append("            });\n");
        sb.append("        }\n");
        sb.append("\n");
        sb.append("        setInterval(buildTree, 1000);\n");
        sb.append("        window.addEventListener('load', buildTree);\n");
        sb.append("\n");
        sb.append("        // Method calls will be pushed here dynamically\n");
        return sb.toString();
    }

    /**
     * Generates the HTML footer with closing tags.
     *
     * @return The HTML footer string.
     */
    public static String generateHtmlFooter() {
        StringBuilder sb = new StringBuilder();
        sb.append("    </script>\n");
        sb.append("</body>\n");
        sb.append("</html>\n");
        return sb.toString();
    }
}

