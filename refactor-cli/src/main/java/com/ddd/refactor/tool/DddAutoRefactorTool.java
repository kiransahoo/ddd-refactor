package com.ddd.refactor.tool;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A subclass of HexaDddRefactorTool that attempts to merge
 * the LLM "suggestedFix" into the original code's AST
 * or at least remove known violations heuristically.
 *
 * If parsing fails (In most cases it would for this apporoach), we embed the snippet as a comment in the final file
 * so devs can incorporate it manually.
 * @author kiransahoo
 */
public class DddAutoRefactorTool extends HexaDddRefactorTool {

    public DddAutoRefactorTool(RefactorConfig config) {
        super(config);
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
        CompilationUnit originalCu;
        try {
            originalCu = StaticJavaParser.parse(originalCode);
        } catch (Exception e) {
            System.err.println("Failed to parse original => " + e.getMessage());
            // fallback: do naive approach + embed snippet as comment
            String fallbackCode = applyHeuristicFixes(originalFile);
            fallbackCode = embedSnippetAsComment(fallbackCode, fix);
            writeRefactoredFile(originalFile, fallbackCode);
            return;
        }

        // Step 2) Attempt to parse the entire snippet as one
        boolean snippetApplied = false;
        try {
            CompilationUnit snippetCu = StaticJavaParser.parse(fix);
            tryMergeSnippet(originalCu, snippetCu);
            snippetApplied = true;
        } catch (Exception mainSnippetEx) {
            System.err.println("Snippet parse failure => " + mainSnippetEx.getMessage());
        }

        // Step 3) If snippet not applied, try chunk-based approach
        if (!snippetApplied) {
            String[] snippetBlocks = fix.split("//--- fix for chunk");
            for (String snippetBlock : snippetBlocks) {
                snippetBlock = snippetBlock.trim();
                if (snippetBlock.isEmpty()) continue;
                try {
                    CompilationUnit snippetCu = StaticJavaParser.parse(snippetBlock);
                    tryMergeSnippet(originalCu, snippetCu);
                } catch (Exception innerEx) {
                    System.err.println("Skipping chunk => parse error: " + innerEx.getMessage());
                }
            }
        }

        // Step 4) If we STILL didn't manage to parse anything, embed snippet
        if (!snippetApplied) {
            // We'll do fallback code + snippet as comment
            String fallbackCode = applyHeuristicFixes(originalFile);
            fallbackCode = embedSnippetAsComment(fallbackCode, fix);
            writeRefactoredFile(originalFile, fallbackCode);
            return;
        }

        // Step 5) If snippet was partially or fully merged, do final heuristics
        applyHeuristicChanges(originalCu);

        // Step 6) write out final merged code
        writeRefactoredFile(originalFile, originalCu.toString());
    }

    /**
     * Put the snippet into a block comment at the bottom of the code for dev reference.
     */
    private String embedSnippetAsComment(String baseCode, String snippet) {
        StringBuilder sb = new StringBuilder(baseCode);
        sb.append("\n\n/*\n")
                .append("Unparseable or partial snippet from LLM:\n")
                .append(snippet)
                .append("\n*/\n");
        return sb.toString();
    }

    /**
     * Merges snippetCu into originalCu by matching type names and merging method changes.
     */
    private void tryMergeSnippet(CompilationUnit originalCu, CompilationUnit snippetCu) {
        snippetCu.getTypes().forEach(snippetType -> {
            String snippetName = snippetType.getNameAsString();
            originalCu.getTypes().stream()
                    .filter(origType -> origType.getNameAsString().equals(snippetName))
                    .findFirst()
                    .ifPresent(originalType -> {
                        // remove domain checks, if snippet removed them
                        removeDomainChecks(originalType);

                        // remove directDbCall from aggregator
                        if (snippetName.toLowerCase().contains("aggregate")) {
                            removeDirectDbCall(originalType);
                        }

                        // Add snippet methods that don't exist
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
     * Fallback code that forcibly removes known domain violations from the raw file.
     */
    private String applyHeuristicFixes(Path originalFile) throws IOException {
        String code = Files.readString(originalFile);
        // naive string approach: remove "directDbCall()" method if aggregator
        if (originalFile.getFileName().toString().toLowerCase().contains("aggregate")) {
            code = code.replaceAll("(?s)public void directDbCall\\(\\) \\{.*?\\}",
                    "// removed directDbCall per domain rule");
        }
        // remove domain checks in repository
        if (originalFile.getFileName().toString().toLowerCase().contains("repository")) {
            code = code.replaceAll("if \\(.*?stock.*?\\{.*?\\}",
                    "// removed domain check from repo");
        }
        return code;
    }

    /**
     * Final pass heuristic changes on the in-memory AST.
     */
    private void applyHeuristicChanges(CompilationUnit originalCu) {
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
     * Example method removing a "directDbCall" method from aggregator.
     */
    private void removeDirectDbCall(com.github.javaparser.ast.body.TypeDeclaration<?> typeDecl) {
        typeDecl.getMethods().removeIf(m -> m.getNameAsString().equals("directDbCall"));
    }

    /**
     * Remove domain checks from a repository, e.g. "if(agg.getStock() < 0)" or "if(agg.getStock() > ...)"
     */
    private void removeDomainChecks(com.github.javaparser.ast.body.TypeDeclaration<?> repoType) {
        repoType.getMethods().forEach(m -> {
            m.getBody().ifPresent(body -> {
                body.getStatements().removeIf(st -> {
                    String stString = st.toString().toLowerCase();
                    return stString.contains("if") && stString.contains("stock");
                });
            });
        });
    }

    /**
     * Writes final code to mirrored folder, naming it `_Refactored.java`.
     */
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
