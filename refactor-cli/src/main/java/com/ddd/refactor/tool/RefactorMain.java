package com.ddd.refactor.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Simple CLI to run the refactoring, using our DddAutoRefactorTool
 * that merges changes into the AST or falls back to heuristics.
 *
 * Usage:
 *   mvn --projects refactor-cli exec:java \
 *     -Dexec.mainClass=com.ddd.refactor.tool.RefactorMain \
 *     -Dexec.args="/path/to/legacy-code/src/main/java sk-OPENAIKEY /desired/outputDir"
 */
public class RefactorMain {

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 2) {
            System.err.println("Usage: RefactorMain <sourceDir> <openAiApiKey> [<outputDir>]");
            System.exit(1);
        }

        Path sourceDir = Path.of(args[0]);
        String apiKey = args[1];
        Path outputDir = (args.length >= 3) ? Path.of(args[2]) : Path.of("refactorOutput");

        // Initialize config
        HexaDddRefactorTool.RefactorConfig cfg =
                new HexaDddRefactorTool.RefactorConfig(sourceDir, outputDir, apiKey);

        // Load properties from /resources/RefactorConfig.properties
        Properties props = new Properties();
        try (InputStream in = RefactorMain.class.getResourceAsStream("/RefactorConfig.properties")) {
            if (in == null) {
                throw new IllegalStateException("Could not find RefactorConfig.properties on classpath.");
            }
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config from resource: RefactorConfig.properties", e);
        }
        String aggregatorMethodsStr = props.getProperty("aggregatorMethodRemovals", "").trim();
        List<String> aggregatorMethodRemovals = aggregatorMethodsStr.isEmpty()
                ? List.of()
                : Arrays.asList(aggregatorMethodsStr.split("\\s*,\\s*"));

        // Apply generic properties to cfg (parallelism, chunk sizes, etc.).
        // This will also set cfg.domainKeywords from the "domainKeywords" property if present.
        HexaDddRefactorTool.loadConfigFromProperties(cfg, props);

        // Create the refactoring tool using our DddAutoRefactorTool subclass
        // and pass in cfg.domainKeywords (which was set by loadConfigFromProperties).
        DddAutoRefactorTool<?> tool = new DddAutoRefactorTool<>(cfg, cfg.domainKeywords,aggregatorMethodRemovals);

        // Run the refactoring
        tool.runRefactoring();
    }
}
