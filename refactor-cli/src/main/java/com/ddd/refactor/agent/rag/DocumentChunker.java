package com.ddd.refactor.agent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for chunking documents into smaller pieces for better retrieval and processing.
 */
public class DocumentChunker {

    private static final Logger logger = LoggerFactory.getLogger(DocumentChunker.class);

    /**
     * Chunk a document by splitting based on lines with a specified maximum chunk size.
     *
     * @param content The content to chunk
     * @param maxLinesPerChunk Maximum number of lines per chunk
     * @param overlap Number of lines to overlap between chunks
     * @return A list of content chunks
     */
    public static List<String> chunkByLines(String content, int maxLinesPerChunk, int overlap) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }

        String[] lines = content.split("\\r?\\n");
        List<String> chunks = new ArrayList<>();

        for (int i = 0; i < lines.length; i += (maxLinesPerChunk - overlap)) {
            int end = Math.min(i + maxLinesPerChunk, lines.length);
            StringBuilder chunkBuilder = new StringBuilder();
            for (int j = i; j < end; j++) {
                chunkBuilder.append(lines[j]).append('\n');
            }
            chunks.add(chunkBuilder.toString().trim());
        }

        return chunks;
    }

    /**
     * Chunk Java code by trying to keep classes and methods together.
     *
     * @param javaCode The Java code to chunk
     * @param maxLinesPerChunk Maximum number of lines per chunk
     * @return A list of Java code chunks
     */
    public static List<String> chunkJavaCode(String javaCode, int maxLinesPerChunk) {
        if (javaCode == null || javaCode.isEmpty()) {
            return new ArrayList<>();
        }

        // First, split the file into lines
        String[] lines = javaCode.split("\\r?\\n");

        // Try to identify class and method boundaries
        List<Integer> importEndLines = new ArrayList<>();
        List<Integer> classStartLines = new ArrayList<>();
        List<Integer> methodStartLines = new ArrayList<>();

        Pattern importPattern = Pattern.compile("^\\s*import\\s+.*?;\\s*$");
        Pattern classPattern = Pattern.compile("^\\s*(public|private|protected)?\\s*(abstract|final)?\\s*class\\s+\\w+.*?\\{\\s*$");
        Pattern methodPattern = Pattern.compile("^\\s*(public|private|protected)?\\s*(abstract|static|final)?\\s*\\w+\\s+\\w+\\s*\\(.*?\\).*?\\{?\\s*$");

        // Scan for structural elements
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            Matcher importMatcher = importPattern.matcher(line);
            if (importMatcher.matches() && (i + 1 < lines.length) && !importPattern.matcher(lines[i + 1]).matches()) {
                importEndLines.add(i);
            }

            Matcher classMatcher = classPattern.matcher(line);
            if (classMatcher.matches()) {
                classStartLines.add(i);
            }

            Matcher methodMatcher = methodPattern.matcher(line);
            if (methodMatcher.matches()) {
                methodStartLines.add(i);
            }
        }

        List<String> chunks = new ArrayList<>();

        // If we found structural elements, use them to guide chunking
        if (!importEndLines.isEmpty() || !classStartLines.isEmpty() || !methodStartLines.isEmpty()) {
            int startLine = 0;

            // First chunk: package declaration and imports
            if (!importEndLines.isEmpty()) {
                int endLine = importEndLines.get(importEndLines.size() - 1) + 1;
                StringBuilder chunk = new StringBuilder();
                for (int i = startLine; i < endLine && i < lines.length; i++) {
                    chunk.append(lines[i]).append('\n');
                }
                chunks.add(chunk.toString().trim());
                startLine = endLine;
            }

            // Process classes and methods
            for (int i = 0; i < classStartLines.size(); i++) {
                int classStart = classStartLines.get(i);
                int nextClassStart = (i + 1 < classStartLines.size()) ? classStartLines.get(i + 1) : lines.length;

                // If class is small enough, add it as a single chunk
                if (nextClassStart - classStart <= maxLinesPerChunk) {
                    StringBuilder chunk = new StringBuilder();
                    for (int line = classStart; line < nextClassStart; line++) {
                        chunk.append(lines[line]).append('\n');
                    }
                    chunks.add(chunk.toString().trim());
                } else {
                    // Class is large, try to split by methods
                    List<Integer> methodsInClass = new ArrayList<>();
                    for (int methodLine : methodStartLines) {
                        if (methodLine > classStart && methodLine < nextClassStart) {
                            methodsInClass.add(methodLine);
                        }
                    }

                    if (!methodsInClass.isEmpty()) {
                        // Add class declaration as a chunk
                        int methodStart = methodsInClass.get(0);
                        StringBuilder chunk = new StringBuilder();
                        for (int line = classStart; line < methodStart; line++) {
                            chunk.append(lines[line]).append('\n');
                        }
                        chunks.add(chunk.toString().trim());

                        // Process methods
                        for (int j = 0; j < methodsInClass.size(); j++) {
                            int currentMethodStart = methodsInClass.get(j);
                            int nextMethodStart = (j + 1 < methodsInClass.size()) ?
                                    methodsInClass.get(j + 1) : nextClassStart;

                            chunk = new StringBuilder();
                            for (int line = currentMethodStart; line < nextMethodStart; line++) {
                                chunk.append(lines[line]).append('\n');
                            }
                            chunks.add(chunk.toString().trim());
                        }
                    } else {
                        // No methods found, fallback to regular chunking
                        for (int line = classStart; line < nextClassStart; line += maxLinesPerChunk) {
                            int endLine = Math.min(line + maxLinesPerChunk, nextClassStart);
                            StringBuilder chunk = new StringBuilder();
                            for (int l = line; l < endLine; l++) {
                                chunk.append(lines[l]).append('\n');
                            }
                            chunks.add(chunk.toString().trim());
                        }
                    }
                }
            }
        } else {
            // Fallback to regular line-based chunking if no structure detected
            return chunkByLines(javaCode, maxLinesPerChunk, 0);
        }

        return chunks;
    }

    /**
     * Chunk content into smaller pieces by paragraphs for better retrieval.
     *
     * @param content The content to chunk
     * @param targetChunkSize The target size for each chunk (in characters)
     * @param overlap The amount of overlap between chunks (in characters)
     * @return A list of content chunks
     */
    public static List<String> chunkByParagraphs(String content, int targetChunkSize, int overlap) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }

        // Split by paragraphs (empty lines)
        String[] paragraphs = content.split("\\n\\s*\\n");

        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            // If adding this paragraph would exceed the target chunk size and we already have content
            if (currentChunk.length() > 0 &&
                    currentChunk.length() + paragraph.length() + 2 > targetChunkSize) {

                // Store the current chunk
                chunks.add(currentChunk.toString().trim());

                // Start a new chunk with overlap
                if (overlap > 0 && currentChunk.length() > overlap) {
                    String overlapText = currentChunk.substring(
                            Math.max(0, currentChunk.length() - overlap));
                    currentChunk = new StringBuilder(overlapText);
                } else {
                    currentChunk = new StringBuilder();
                }
            }

            // Add the paragraph to the current chunk
            if (currentChunk.length() > 0) {
                currentChunk.append("\n\n");
            }
            currentChunk.append(paragraph);
        }

        // Add the last chunk if it has content
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * Choose the appropriate chunking strategy based on content type.
     *
     * @param content The content to chunk
     * @param contentType The type of content (e.g., "java", "text")
     * @param chunkSize The maximum size for each chunk (lines or characters)
     * @param overlap The amount of overlap between chunks
     * @return A list of content chunks
     */
    public static List<String> chunkContent(String content, String contentType, int chunkSize, int overlap) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }

        if ("java".equalsIgnoreCase(contentType)) {
            return chunkJavaCode(content, chunkSize);
        } else if ("text".equalsIgnoreCase(contentType)) {
            return chunkByParagraphs(content, chunkSize, overlap);
        } else {
            // Default to line-based chunking
            return chunkByLines(content, chunkSize, overlap);
        }
    }
}