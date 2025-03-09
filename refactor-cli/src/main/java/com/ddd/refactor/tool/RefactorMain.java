package com.ddd.refactor.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Simple CLI to run the refactoring, using our DddAutoRefactorTool
 * that merges changes into the AST or falls back to heuristics.
 *
 * Usage:
 *   mvn --projects refactor-cli exec:java \
 *     -Dexec.mainClass=com.ddd.refactor.tool.RefactorMain \
 *     -Dexec.args="/path/to/legacy-code/src/main/java sk-OPENAIKEY /desired/outputDir"
 *     @author kiransahoo
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
        try (InputStream in = RefactorMain.class.getResourceAsStream("/RefactorConfig.properties")) {
            if (in == null) {
                throw new IllegalStateException("Could not find RefactorConfig.properties on classpath.");
            }
            HexaDddRefactorTool.loadConfigFromProperties(cfg, in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config from resource: RefactorConfig.properties", e);
        }

        // Create the tool (using DddAutoRefactorTool to handle merges)
        HexaDddRefactorTool tool = new DddAutoRefactorTool(cfg);

        // Run
        tool.runRefactoring();
    }
}
