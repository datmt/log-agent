# Java Method Logging Agent

A powerful Java agent that uses bytecode instrumentation to intercept and log method calls in running Java applications. This tool provides deep visibility into your application's behavior by capturing method entry/exit times, parameters, return values, and exceptions in a structured JSON format.

## Features

- = **Method Interception**: Automatically intercepts method calls without modifying source code
- =� **Performance Metrics**: Captures method execution time in nanoseconds
- =� **Detailed Logging**: Logs method parameters, return values, and exceptions
- <� **Selective Monitoring**: Configure which packages to include or exclude
- =� **JSON Output**: Structured JSON Lines format for easy parsing and analysis
- =� **Safe Implementation**: Robust error handling prevents crashes in target applications

## How It Works

The agent uses Java's instrumentation API combined with [Byte Buddy](https://bytebuddy.net/) to dynamically modify bytecode at runtime. When your application starts with the agent attached:

1. The agent loads via the `premain` method
2. Byte Buddy identifies classes matching your configuration
3. Advice code is injected at the beginning and end of matched methods
4. Each method call logs its execution details to a JSON file
5. The logging happens without any changes to your application code

## Quick Start

### Build the Agent

```bash
# Clone or download the project
cd log-agent

# Build the agent JAR with all dependencies
./gradlew build
```

The agent JAR will be created at `build/libs/log-agent-1.0.jar`.

### Use with Your Application

```bash
java -javaagent:build/libs/log-agent-1.0.jar=logfile=method_calls.jsonl -jar your-application.jar
```

**Example with specific package filtering:**
```bash
java -javaagent:/home/dat/data/code/log-agent/build/libs/log-agent-1.0.jar=logfile=/tmp/all.log;packages=com.datmt.rabbit,org.springframework.amqp.rabbit.core -jar your-application.jar
```

## Configuration

The agent accepts configuration via the `-javaagent` argument:

```bash
java -javaagent:log-agent-1.0.jar=option1=value1;option2=value2 -jar your-app.jar
```

### Available Options

| Option | Description | Default | Example |
|--------|-------------|---------|---------|
| `logfile` | Path to the output JSONL file | `method_calls.jsonl` | `logfile=/tmp/my-app-methods.jsonl` |
| `packages` | Comma-separated packages to include | All packages | `packages=com.example.myapp,com.example.service` |
| `excludePackages` | Comma-separated packages to exclude | None | `excludePackages=com.example.unwanted,org.thirdparty` |

### Configuration Examples

**Monitor specific packages only:**
```bash
java -javaagent:log-agent-1.0.jar=packages=com.example.myapp,com.example.service -jar app.jar
```

**Monitor Spring RabbitMQ components:**
```bash
java -javaagent:/home/dat/data/code/log-agent/build/libs/log-agent-1.0.jar=logfile=/tmp/rabbit.log;packages=com.datmt.rabbit,org.springframework.amqp.rabbit.core -jar app.jar
```

**Monitor everything except specific packages:**
```bash
java -javaagent:log-agent-1.0.jar=excludePackages=com.example.logging,org.apache.commons -jar app.jar
```

**Custom log file location:**
```bash
java -javaagent:log-agent-1.0.jar=logfile=/var/log/myapp/methods.jsonl -jar app.jar
```

**Combined configuration with multiple packages:**
```bash
java -javaagent:log-agent-1.0.jar=logfile=app-methods.jsonl;packages=com.example.service,com.example.controller;excludePackages=com.example.tests -jar app.jar
```

## Output Format

The agent generates JSON Lines (JSONL) files where each line is a self-contained JSON object:

```json
{
  "time": "2024-01-15T10:30:45.123Z",
  "package": "com.example.service",
  "class": "UserService",
  "method": "createUser",
  "params": {
    "name": "John Doe",
    "email": "john@example.com",
    "age": 30
  },
  "returnType": "com.example.model.User",
  "returnData": "{\"id\":123,\"name\":\"John Doe\",\"email\":\"john@example.com\"}",
  "durationNanos": 1500000
}
```

### Field Descriptions

- `time`: ISO 8601 timestamp of method completion
- `package`: Java package containing the method
- `class`: Simple class name (without package)
- `method`: Method name
- `params`: Map of parameter names and values
- `returnType`: Return type name or "EXCEPTION" if an error occurred
- `returnData`: Serialized return value or exception details
- `durationNanos`: Method execution time in nanoseconds

## Example Usage Scenarios

### Performance Analysis

```bash
# Start your application with the agent
java -javaagent:log-agent-1.0.jar=packages=com.example.performance -jar performance-test.jar

# After running, analyze the logs
cat method_calls.jsonl | jq '.durationNanos' | sort -n | tail -10
```

### Debugging Method Calls

```bash
# Monitor specific service methods
java -javaagent:log-agent-1.0.jar=packages=com.example.debugging;logfile=debug-methods.jsonl -jar app.jar

# View recent method calls
tail -f debug-methods.jsonl | jq '.method + ": " + (.durationNanos / 1000000 | tostring) + "ms"'
```

### API Behavior Analysis

```bash
# Monitor all API endpoints
java -javaagent:log-agent-1.0.jar=packages=com.example.api;logfile=api-calls.jsonl -jar api-server.jar

# Extract API usage patterns
cat api-calls.jsonl | jq -r '.class + "." + .method' | sort | uniq -c | sort -nr
```

## Default Exclusions

To ensure stability and avoid noise, the agent automatically excludes:

- System packages: `java.*`, `javax.*`, `sun.*`, `com.sun.*`
- Agent itself: `com.example.agent`
- Byte Buddy library: `net.bytebuddy.*`
- JSON library: `com.google.gson.*`
- CGLIB proxy classes (contains `CGLIB` or `$$`)

## Building from Source

### Prerequisites

- Java 8 or higher
- Gradle (or use the included wrapper)

### Build Commands

```bash
# Clean and build
./gradlew clean build

# Run tests
./gradlew test

# Build only the JAR
./gradlew jar

# Create distribution
./gradlew assemble
```

### Dependencies

- **Byte Buddy** (1.17.5): Bytecode instrumentation library
- **Gson** (2.10.1): JSON serialization library
- **JUnit** (5.10.0): Testing framework

## Performance Considerations

� **Important**: This agent is designed for debugging and analysis, not for production monitoring.

- **File I/O**: Uses synchronous file writing, which can impact performance
- **Serialization**: JSON serialization adds overhead to each method call
- **Memory**: Thread-local storage is used for tracking nested calls
- **Overhead**: Expect significant performance overhead when monitoring many methods

For production monitoring, consider using dedicated APM tools or implement asynchronous logging.

## Troubleshooting

### Agent Not Loading

- Ensure the JAR file exists and is accessible
- Check Java version compatibility (Java 8+)
- Verify the `-javaagent` syntax is correct

### No Logs Generated

- Check if packages are correctly specified
- Verify file write permissions for the log file location
- Look for agent messages in console output

### Performance Issues

- Reduce the number of monitored packages
- Use `excludePackages` to avoid noisy components
- Consider monitoring only specific classes/methods

### Serialization Errors

- The agent includes safe serialization with fallback mechanisms
- Complex objects may show `toString()` representations instead of full JSON
- Circular references are automatically handled

## Use Cases

- **Performance profiling**: Identify slow methods and bottlenecks
- **Debugging**: Trace method execution and parameter flow
- **API analysis**: Understand usage patterns and call frequencies
- **Testing**: Verify method calls and interactions in complex scenarios
- **Learning**: Explore how frameworks and libraries work internally

## License

This project is provided as-is for educational and debugging purposes.

## Contributing

Feel free to submit issues and enhancement requests!