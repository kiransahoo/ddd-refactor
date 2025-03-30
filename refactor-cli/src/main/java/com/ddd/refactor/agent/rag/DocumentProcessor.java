package com.ddd.refactor.agent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A service for processing documents for RAG indexing.
 */
public class DocumentProcessor {

    private static final Logger logger = LoggerFactory.getLogger(DocumentProcessor.class);

    private final RagService ragService;
    private final int chunkSize;
    private final int chunkOverlap;

    /**
     * Constructs a DocumentProcessor with the specified parameters.
     *
     * @param ragService The RAG service to use for indexing
     * @param chunkSize The size of chunks to process
     * @param chunkOverlap The amount of overlap between chunks
     */
    public DocumentProcessor(RagService ragService, int chunkSize, int chunkOverlap) {
        this.ragService = ragService;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    /**
     * Process and index a document from a file.
     *
     * @param filePath The path to the file to process
     * @param metadata Additional metadata for the document
     * @return true if processing was successful, false otherwise
     */
    public boolean processFile(Path filePath, Map<String, Object> metadata) {
        if (ragService == null || !ragService.isEnabled() || !Files.exists(filePath)) {
            return false;
        }

        try {
            String content = Files.readString(filePath);
            String fileName = filePath.getFileName().toString();

            return processDocument(fileName, content, detectContentType(fileName), metadata);
        } catch (IOException e) {
            logger.error("Error reading file {}: {}", filePath, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Process and index a document from text content.
     *
     * @param title The document title
     * @param content The document content
     * @param contentType The content type (e.g., "java", "text")
     * @param metadata Additional metadata for the document
     * @return true if processing was successful, false otherwise
     */
    public boolean processDocument(String title, String content, String contentType, Map<String, Object> metadata) {
        if (ragService == null || !ragService.isEnabled() || content == null || content.isEmpty()) {
            return false;
        }

        try {
            // Chunk the content
            List<String> chunks = DocumentChunker.chunkContent(content, contentType, chunkSize, chunkOverlap);

            // Prepare metadata
            Map<String, Object> enhancedMetadata = new HashMap<>();
            if (metadata != null) {
                enhancedMetadata.putAll(metadata);
            }
            enhancedMetadata.put("title", title);
            enhancedMetadata.put("content_type", contentType);
            enhancedMetadata.put("chunk_count", chunks.size());

            // Index each chunk
            boolean allSuccessful = true;
            for (int i = 0; i < chunks.size(); i++) {
                String chunkId = "doc_" + UUID.nameUUIDFromBytes((title + i).getBytes()).toString();
                String chunkTitle = title + " (Chunk " + (i + 1) + " of " + chunks.size() + ")";

                Map<String, Object> chunkMetadata = new HashMap<>(enhancedMetadata);
                chunkMetadata.put("chunk_index", i + 1);
                chunkMetadata.put("is_chunk", true);

                boolean success = ragService.indexDocument(chunkId, chunkTitle, chunks.get(i), chunkMetadata);
                if (!success) {
                    allSuccessful = false;
                    logger.warn("Failed to index chunk {} of document: {}", i + 1, title);
                }
            }

            if (allSuccessful) {
                logger.info("Successfully processed document: {}", title);
            }

            return allSuccessful;
        } catch (Exception e) {
            logger.error("Error processing document: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Process and index all documents in a directory.
     *
     * @param directoryPath The path to the directory to process
     * @param fileTypes The file types to process (e.g., ".java", ".md")
     * @param recursive Whether to recursively process subdirectories
     * @return The number of documents successfully processed
     */
    public int processDirectory(Path directoryPath, List<String> fileTypes, boolean recursive) {
        if (ragService == null || !ragService.isEnabled() || !Files.exists(directoryPath) || !Files.isDirectory(directoryPath)) {
            return 0;
        }

        final int[] processedCount = {0};

        try {
            FileVisitor<Path> fileVisitor = new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    boolean matchesType = fileTypes.isEmpty() ||
                            fileTypes.stream().anyMatch(type -> fileName.endsWith(type));

                    if (matchesType) {
                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("path", file.toString());
                        metadata.put("source", "directory_scan");

                        if (processFile(file, metadata)) {
                            processedCount[0]++;
                        }
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return recursive || dir.equals(directoryPath) ?
                            FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }
            };

            Files.walkFileTree(directoryPath, fileVisitor);

            logger.info("Processed {} documents from directory: {}", processedCount[0], directoryPath);
            return processedCount[0];
        } catch (IOException e) {
            logger.error("Error processing directory {}: {}", directoryPath, e.getMessage(), e);
            return processedCount[0];
        }
    }

    /**
     * Process and index Java code snippets.
     *
     * @param snippets Map of snippet names to their content
     * @return The number of snippets successfully processed
     */
    public int processCodeSnippets(Map<String, String> snippets) {
        if (ragService == null || !ragService.isEnabled() || snippets == null || snippets.isEmpty()) {
            return 0;
        }

        int processedCount = 0;

        for (Map.Entry<String, String> entry : snippets.entrySet()) {
            String snippetName = entry.getKey();
            String content = entry.getValue();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", "code_snippet");
            metadata.put("language", "java");
            metadata.put("source", "code_snippet");

            if (processDocument(snippetName, content, "java", metadata)) {
                processedCount++;
            }
        }

        logger.info("Processed {}/{} code snippets", processedCount, snippets.size());
        return processedCount;
    }

    /**
     * Detect the content type from a file name.
     *
     * @param fileName The file name
     * @return The detected content type
     */
    private String detectContentType(String fileName) {
        if (fileName.endsWith(".java")) {
            return "java";
        } else if (fileName.endsWith(".md")) {
            return "markdown";
        } else if (fileName.endsWith(".txt") || fileName.endsWith(".text")) {
            return "text";
        } else if (fileName.endsWith(".xml") || fileName.endsWith(".html")) {
            return "markup";
        } else if (fileName.endsWith(".json") || fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            return "data";
        } else if (fileName.endsWith(".properties") || fileName.endsWith(".conf")) {
            return "config";
        } else {
            return "unknown";
        }
    }

    /**
     * Find all files with the specified extensions in a directory.
     *
     * @param directoryPath The path to the directory to search
     * @param extensions The file extensions to include
     * @param recursive Whether to recursively search subdirectories
     * @return A list of matching file paths
     * @throws IOException If an I/O error occurs
     */
    public static List<Path> findFiles(Path directoryPath, List<String> extensions, boolean recursive) throws IOException {
        if (!Files.exists(directoryPath) || !Files.isDirectory(directoryPath)) {
            return Collections.emptyList();
        }

        List<Path> matchingFiles = new ArrayList<>();

        FileVisitor<Path> fileVisitor = new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fileName = file.getFileName().toString();
                boolean matchesExtension = extensions.isEmpty() ||
                        extensions.stream().anyMatch(ext -> fileName.endsWith(ext));

                if (matchesExtension) {
                    matchingFiles.add(file);
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return recursive || dir.equals(directoryPath) ?
                        FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }
        };

        Files.walkFileTree(directoryPath, fileVisitor);

        return matchingFiles;
    }

    /**
     * Process and index all documents in a directory matching the specified pattern.
     *
     * @param directoryPath The path to the directory to process
     * @param pattern The glob pattern to match files
     * @param recursive Whether to recursively process subdirectories
     * @return The number of documents successfully processed
     */
    public int processDirectoryWithPattern(Path directoryPath, String pattern, boolean recursive) {
        if (ragService == null || !ragService.isEnabled() || !Files.exists(directoryPath) || !Files.isDirectory(directoryPath)) {
            return 0;
        }

        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

            List<Path> matchingFiles = findFiles(directoryPath, Collections.emptyList(), recursive).stream()
                    .filter(path -> matcher.matches(path.getFileName()))
                    .collect(Collectors.toList());

            int processedCount = 0;
            for (Path file : matchingFiles) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("path", file.toString());
                metadata.put("source", "pattern_match");
                metadata.put("pattern", pattern);

                if (processFile(file, metadata)) {
                    processedCount++;
                }
            }

            logger.info("Processed {} documents matching pattern {} from directory: {}",
                    processedCount, pattern, directoryPath);
            return processedCount;
        } catch (IOException e) {
            logger.error("Error processing directory {} with pattern {}: {}",
                    directoryPath, pattern, e.getMessage(), e);
            return 0;
        }
    }
}