package com.ddd.refactor.tool;

import com.ddd.refactor.agent.EnhancedRefactorAgent;
import com.ddd.refactor.agent.rag.RagFactory;
import com.ddd.refactor.agent.rag.RagService;
import com.ddd.refactor.agent.IDomainContextRetriever;
import com.ddd.refactor.agent.OpenAiEmbedder;
import com.ddd.refactor.agent.InMemoryVectorDb;
import com.ddd.refactor.agent.VectorDbContextRetriever;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enhanced CLI to run the refactoring, using our DddAutoRefactorTool
 * with optional RAG capabilities for better context retrieval.
 *
 * Usage:
 *   mvn --projects refactor-cli exec:java \
 *     -Dexec.mainClass=com.ddd.refactor.tool.RefactorMain \
 *     -Dexec.args="/path/to/legacy-code/src/main/java sk-OPENAIKEY /desired/outputDir"
 */
public class RefactorMain {

    private static final Logger logger = LoggerFactory.getLogger(RefactorMain.class);

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

        // Check if RAG is enabled
        boolean ragEnabled = Boolean.parseBoolean(props.getProperty("rag.enabled", "false"));

        // Create a context retriever, with or without RAG
        IDomainContextRetriever contextRetriever;

        if (ragEnabled) {
            // Try to set up RAG components
            try {
                logger.info("RAG is enabled - initializing RAG components");

                // Create RAG service using factory
                RagService ragService = RagFactory.createRagService(props);

                if (ragService != null) {
                    // Create a RAG-based context retriever
                    boolean useRagForContext = Boolean.parseBoolean(
                            props.getProperty("rag.useForContext", "true"));

                    if (useRagForContext) {
                        contextRetriever = RagFactory.createContextRetriever(ragService);
                        logger.info("Using RAG-based context retriever");

                        // Pre-index domain context files if configured
                        String domainContextPath = props.getProperty("rag.domainContextPath", "");
                        if (!domainContextPath.isEmpty()) {
                            logger.info("Processing domain context from: {}", domainContextPath);
                            // Domain context indexing happens inside the context retriever
                        }
                    } else {
                        // Fall back to the basic context retriever
                        logger.info("RAG is enabled but not used for context retrieval");
                        contextRetriever = createBasicContextRetriever(props);
                    }
                } else {
                    // Failed to create RAG service, fall back to basic retriever
                    logger.warn("Failed to create RAG service, falling back to basic context retriever");
                    contextRetriever = createBasicContextRetriever(props);
                }
            } catch (Exception e) {
                logger.error("Error initializing RAG components: {}", e.getMessage());
                // Fall back to the basic context retriever
                contextRetriever = createBasicContextRetriever(props);
            }
        } else {
            // Use the standard context retriever
            contextRetriever = createBasicContextRetriever(props);
        }

        // Create the refactoring tool using our DddAutoRefactorTool subclass
        // and pass in cfg.domainKeywords (which was set by loadConfigFromProperties).
        DddAutoRefactorTool<?> tool = new DddAutoRefactorTool<>(cfg, cfg.domainKeywords, aggregatorMethodRemovals);

        // If we want to override how the tool processes files with RAG capabilities,
        // we could create an enhanced version here.
        // But for now, we're enhancing the context retriever which affects the LLM prompt.

        // Run the refactoring
        tool.runRefactoring();
    }

    /**
     * Creates a basic context retriever with the default vector DB.
     */
    private static IDomainContextRetriever createBasicContextRetriever(Properties props) {
        // Create the original-style context retriever
        OpenAiEmbedder embedder = new OpenAiEmbedder();
        InMemoryVectorDb vectorDb = new InMemoryVectorDb();

        // Add default snippets
        String aggSnippet = props.getProperty("domain.default.snippet", """
                package com.example.ddd;
                
                public abstract class AbstractAggregate<ID> {
                   protected ID id;
                   public ID getId() { return id; }
                }
                
                @interface CommandHandler {}
                """);

        float[] aggEmb = embedder.embed(aggSnippet);
        vectorDb.addSnippet(new com.ddd.refactor.agent.DomainSnippet("default_agg", aggSnippet, aggEmb));

        return new VectorDbContextRetriever(vectorDb, embedder);
    }
}