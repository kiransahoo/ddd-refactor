package com.ddd.refactor.tool;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.IfStmt;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A subclass of HexaDddRefactorTool that attempts to merge
 * the LLM "suggestedFix" into the original code's AST
 * or at least remove known violations heuristically.
 *
 * If parsing fails (common for partial code), we embed those snippet blocks
 * as a comment in the final file so devs can incorporate them manually.
 */
public class DddAutoRefactorTool<T extends CompilationUnit> extends HexaDddRefactorTool {

    /**
     * A list of domain keywords to look for in "if" statements when removing domain checks.
     * E.g., ["stock", "price", "quantity"] -- adapt as needed.
     */
    private final List<String> domainKeywords;

    public DddAutoRefactorTool(RefactorConfig config, List<String> domainKeywords) {
        super(config);
        this.domainKeywords = domainKeywords != null ? domainKeywords : List.of();
    }

    @Override
    protected void handleFinalResponse(Path originalFile, JSONObject resp) throws IOException {
        boolean violation = resp.optBoolean("violation", false);
        if (!violation) {
            System.out.println("[OK] " + originalFile);
            return;
        }
        System.out.println("[VIOLATION] " + originalFile);

        // The entire snippet from LLM
        String fix = resp.optString("suggestedFix", "");

        // Print it out for debugging
        System.out.println("=== LLM Snippet for " + originalFile.getFileName() + " ===");
        System.out.println(fix);

        if (fix.isBlank()) {
            // If no snippet is provided, fallback to heuristic modifications
            System.out.println("No snippet fix provided => applying fallback domain-logic removal logic...");
            String fallbackCode = applyHeuristicFixes(originalFile);
            writeRefactoredFile(originalFile, fallbackCode);
            return;
        }

        // Step 1) parse original code
        String originalCode = Files.readString(originalFile);
        T originalCu;
        try {
            // Cast because StaticJavaParser.parse(...) returns a concrete CompilationUnit
            originalCu = (T) StaticJavaParser.parse(originalCode);
        } catch (Exception e) {
            System.err.println("Failed to parse original => " + e.getMessage());
            // fallback: do naive approach + embed entire snippet as comment
            String fallbackCode = applyHeuristicFixes(originalFile);
            fallbackCode = embedSnippetAsComment(fallbackCode, fix,
                    "Snippet (entire) unparseable due to original parse fail:");
            writeRefactoredFile(originalFile, fallbackCode);
            return;
        }

        // Step 2) Attempt to parse the entire snippet as one
        boolean snippetApplied = false;
        List<String> failedChunks = new ArrayList<>();

        try {
            T snippetCu = (T) StaticJavaParser.parse(fix);
            tryMergeSnippet(originalCu, snippetCu);
            snippetApplied = true;
        } catch (Exception mainSnippetEx) {
            System.err.println("Snippet parse failure => " + mainSnippetEx.getMessage());
        }

        // Step 3) If snippet not fully applied, do chunk-based approach
        if (!snippetApplied) {
            String[] snippetBlocks = fix.split("//--- fix for chunk");
            for (String snippetBlock : snippetBlocks) {
                String trimmedBlock = snippetBlock.trim();
                if (trimmedBlock.isEmpty()) continue;
                try {
                    T snippetCu = (T) StaticJavaParser.parse(trimmedBlock);
                    tryMergeSnippet(originalCu, snippetCu);
                    snippetApplied = true; // at least one block succeeded
                } catch (Exception innerEx) {
                    System.err.println("Skipping chunk => parse error: " + innerEx.getMessage());
                    // store this chunk for embedding as comment
                    failedChunks.add(trimmedBlock);
                }
            }
        }

        // Step 4) If we STILL didn't manage to parse anything, fallback
        // or if partial merges worked, embed leftover snippet chunks as comments.
        if (!snippetApplied) {
            // fallback code + entire snippet as comment
            String fallbackCode = applyHeuristicFixes(originalFile);
            fallbackCode = embedSnippetAsComment(fallbackCode, fix,
                    "Entire snippet unparseable after chunk attempts:");
            writeRefactoredFile(originalFile, fallbackCode);
            return;
        }

        // If partial merges succeeded, but we have leftover chunk fails,
        // embed those chunks as comments at the bottom:
        if (!failedChunks.isEmpty()) {
            String leftoverComment = String.join("\n\n//--- Chunk parse fail ---\n", failedChunks);
            leftoverComment = "\n\n/*\nUn-merged snippet blocks:\n"
                    + leftoverComment
                    + "\n*/\n";
            // Add as an orphan comment
            originalCu.addOrphanComment(new BlockComment(leftoverComment));
        }

        // Step 5) final heuristics on the AST
        applyHeuristicChanges(originalCu);

        // Step 6) write out final merged code
        writeRefactoredFile(originalFile, originalCu.toString());
    }

    /**
     * Put snippet(s) into a block comment at the bottom of the code for dev reference.
     */
    private String embedSnippetAsComment(String baseCode, String snippet, String headerText) {
        StringBuilder sb = new StringBuilder(baseCode);
        sb.append("\n\n/*\n")
                .append(headerText).append("\n\n")
                .append(snippet)
                .append("\n*/\n");
        return sb.toString();
    }

    /**
     * Merges snippetCu into originalCu by matching type names and merging method changes.
     */
    private void tryMergeSnippet(T originalCu, T snippetCu) {
        snippetCu.getTypes().forEach(snippetType -> {
            String snippetName = snippetType.getNameAsString();
            originalCu.getTypes().stream()
                    .filter(origType -> origType.getNameAsString().equals(snippetName))
                    .findFirst()
                    .ifPresent(originalType -> {
                        // remove domain checks if snippet also removed them
                        removeDomainChecks(originalType);

                        // remove directDbCall from aggregator if snippet name suggests aggregator
                        if (snippetName.toLowerCase().contains("aggregate")) {
                            removeDirectDbCall(originalType);
                        }

                        // Merge or add snippet methods
                        snippetType.getMethods().forEach(snippetMethod -> {
                            String snippetMethodName = snippetMethod.getNameAsString();
                            boolean hasMethod = originalType.getMethods().stream()
                                    .anyMatch(m -> m.getNameAsString().equals(snippetMethodName));
                            if (!hasMethod) {
                                originalType.addMember(snippetMethod.clone());
                            }
                        });
                    });
        });
    }

    /**
     * Fallback code that forcibly removes known domain violations from the raw file
     * using naive string replacements.
     * This includes removing directDbCall() in aggregator classes
     * and any if-statements referencing the domain keywords in repository classes.
     */
    private String applyHeuristicFixes(Path originalFile) throws IOException {
        String code = Files.readString(originalFile);
        String fileNameLower = originalFile.getFileName().toString().toLowerCase();

        // naive approach: remove "directDbCall()" method if aggregator
        if (fileNameLower.contains("aggregate")) {
            code = code.replaceAll("(?s)public\\s+void\\s+directDbCall\\(\\)\\s*\\{.*?\\}",
                    "// removed directDbCall per domain rule");
        }

        // remove domain checks in repository
        if (fileNameLower.contains("repository")) {
            // Build a regex that matches any of the domain keywords within an if(...)
            if (!domainKeywords.isEmpty()) {
                // e.g. domainKeywords => ["stock","price","quantity"]
                // produce pattern => "(?i)if\s*\(.*?(stock|price|quantity).*?\).*?\{.*?\}"
                String domainRegex = domainKeywords.stream()
                        .map(k -> "\\b" + Pattern.quote(k.toLowerCase()) + "\\b") // ensure we only match whole words
                        .collect(Collectors.joining("|"));

                // (?is) => case-insensitive, dotall (so "." matches newlines)
                String pattern = "(?is)if\\s*\\(.*?(" + domainRegex + ").*?\\).*?\\{.*?\\}";
                code = code.replaceAll(pattern, "// removed domain check from repo");
            }
        }
        return code;
    }

    /**
     * Final pass heuristic changes on the in-memory AST.
     * Removes directDbCall in aggregator, removes domain checks in repository, etc.
     */
    private void applyHeuristicChanges(T originalCu) {
        originalCu.getTypes().forEach(typeDecl -> {
            String typeName = typeDecl.getNameAsString().toLowerCase();
            if (typeName.contains("aggregate")) {
                removeDirectDbCall(typeDecl);
            }
            if (typeName.contains("repository")) {
                removeDomainChecks(typeDecl);
            }
        });
    }

    /**
     * Removes the 'directDbCall()' method if found.
     */
    private void removeDirectDbCall(TypeDeclaration<?> typeDecl) {
        typeDecl.getMethods().removeIf(m -> m.getNameAsString().equals("directDbCall"));
    }

    /**
     * Removes if-statements containing any of the domain keywords in the type's methods.
     */
    private void removeDomainChecks(TypeDeclaration<?> typeDecl) {
        if (domainKeywords.isEmpty()) {
            return; // nothing to remove if no domain keywords
        }

        typeDecl.getMethods().forEach(method -> {
            method.getBody().ifPresent(body -> {
                body.getStatements().removeIf(stmt -> {
                    // Only consider if-statements:
                    if (stmt.isIfStmt()) {
                        IfStmt ifStmt = stmt.asIfStmt();
                        // Convert the condition to a string, lower-case it:
                        String conditionText = ifStmt.getCondition().toString().toLowerCase();
                        // If the condition references ANY of the domain keywords => remove
                        return domainKeywords.stream()
                                .anyMatch(keyword -> conditionText.contains(keyword.toLowerCase()));
                    }
                    return false;
                });
            });
        });
    }

    /**
     * Writes final code to mirrored folder, naming it `_Refactored.java`.
     */
    //@Override
    protected void writeRefactoredFile(Path originalFile, String newCode) throws IOException {
        Path rel = config.sourceDir.relativize(originalFile.getParent());
        Path outDir = config.outputDir.resolve(rel);
        Files.createDirectories(outDir);

        String baseName = originalFile.getFileName().toString().replace(".java", "");
        Path outFile = outDir.resolve(baseName + "_Refactored.java");
        Files.writeString(outFile, newCode, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Refactored => " + outFile);
    }
}
