# Java DDD & Hexagonal Architecture Refactoring Tool

This repository provides two approaches for refactoring legacy Java code to meet Domain-Driven Design (DDD) and Hexagonal Architecture requirements, leveraging OpenAI's GPT.

## Table of Contents
- [Overview](#overview)
- [Approaches](#approaches)
    - [Basic Approach](#basic-approach)
    - [Advanced Agent](#advanced-agent)
- [Components](#components)
    - [HexaDddRefactorTool](#hexadddrefactortool)
    - [DddAutoRefactorTool](#dddautorefactortool)
    - [RefactorMain](#refactormain)
    - [LlmClient](#llmclient)
    - [RefactorConfig.properties](#refactorconfigproperties)
- [Technical Details](#technical-details)
    - [JavaParser & AST](#javaparser--ast)
    - [Heuristic Fixes](#heuristic-fixes)
- [Usage Instructions](#usage-instructions)
    - [Running the Basic Approach](#running-the-basic-approach)
    - [Running the Advanced Agent](#running-the-advanced-agent)
- [Which Method to Use?](#which-method-to-use)
- [License & Disclaimer](#license--disclaimer)

## Overview

This tool helps modernize Java codebases by applying DDD and Hexagonal Architecture principles automatically. It processes Java files, identifies architectural violations, and proposes refactored code using GPT.

## Approaches

### Basic Approach (single-pass per file/chunk)

The basic approach performs a single GPT call per chunk of code:

- Components:
    - `HexaDddRefactorTool`
    - `DddAutoRefactorTool`
    - `RefactorMain`
    - `LlmClient`
    - `RefactorConfig.properties`

Process:
1. Split files into chunks
2. Send each chunk to GPT
3. If GPT's suggestion is valid Java, merge it
4. If parsing fails, apply minimal heuristic fixes
5. Output `_Refactored.java` files

### Advanced Agent (multi-turn parse-check loop)

The advanced approach uses a multi-turn dialogue with GPT:

- Component:
    - `AdvancedRefactorAgent`

Process:
1. Send code chunk to GPT
2. Check if the suggested fix parses correctly
3. If parsing fails, send error back to GPT for correction
4. Repeat until valid or max retries reached
5. Generate final aggregated fix

## Components

### HexaDddRefactorTool

**Purpose**: Core engine that processes Java files, splits them into chunks, and calls GPT.

**Key Functions**:
- `findJavaFiles`: Recursively lists all `.java` files from a source directory
- `processFile`: Reads files, splits into chunks, calls LLM for each chunk
- `handleFinalResponse`: Writes the aggregated suggestions to `_Refactored.java` files

**Caching**:
- Uses SHA-256 hash of file content to avoid redundant GPT calls

### DddAutoRefactorTool

**Purpose**: Extends `HexaDddRefactorTool` with smarter merging of GPT suggestions.

**Key Additional Features**:
- Attempts to parse the GPT's suggestions with JavaParser
- Merges suggestions into the original code's AST
- Removes domain rule violations
- Falls back to heuristic fixes if parsing fails

### RefactorMain

**Purpose**: Command-line entry point for the refactoring tool.

**Usage**:
```bash
mvn exec:java \
  -Dexec.mainClass="com.ddd.refactor.RefactorMain" \
  -Dexec.args="/path/to/src sk-OPENAIKEY /outputDir"
```

### LlmClient

**Purpose**: HTTP client for OpenAI's API.

**Function**:
- Sends requests to `https://api.openai.com/v1/chat/completions`
- Parses JSON responses to extract GPT's suggestions

### RefactorConfig.properties

**Purpose**: Configuration file for the refactoring process.

**Settings**:
```properties
maxParallel=4
maxLinesPerChunk=300
maxPromptRetries=3
cacheEnabled=true
basePrompt=You are an expert in Java, ...
```

## Technical Details

### JavaParser & AST

**JavaParser**: Library that parses Java source code into an Abstract Syntax Tree (AST).

**AST**: Tree-like data structure representing code structure.

**Benefits**:
- Enables structured merging of GPT's suggestions
- Allows precise identification and removal of architectural violations

### Heuristic Fixes

**Definition**: Simple pattern-based fixes applied when GPT's suggestion can't be parsed.

**Examples**:
- Removing direct database calls
- Eliminating domain rule violations in repository classes

**Tradeoffs**:
- Always works for known patterns
- Less nuanced than full code rewrites
- Risk of breaking code if patterns match incorrectly

## Usage Instructions

### Running the Basic Approach

1. Setup:
```bash
# Prepare your code and configuration
mv RefactorConfig.properties src/main/resources/
```

2. Build:
```bash
mvn clean compile
```

3. Run:
```bash
mvn exec:java \
  -Dexec.mainClass="com.ddd.refactor.RefactorMain" \
  -Dexec.args="/path/to/legacy-code/src/main/java sk-OPENAIKEY /desired/outputDir"
```

4. Check output:
```
Discovered 3 .java files...
[VIOLATION] SomeClass.java
Refactored => /desired/outputDir/SomeClass_Refactored.java
```
This code shows a more **advanced** approach to using GPT for DDD/Hexagonal refactoring:

1. **Vector DB or Fallback**:
  - By default, it uses an in-memory vector DB (`InMemoryVectorDb`) with a snippet or two.
  - You can swap in `FallbackVectorDb` if you have no real data.
  - In a real system, connect to Pinecone, Weaviate, Qdrant, or your own vector store.

2. **Concurrency**:
  - We process each “chunk” of a file in parallel with a `ThreadPoolExecutor`.
  - Good for large codebases with many files or large lines.

3. **Multi-Turn Parse Checking**:
  - If GPT’s suggested code can’t parse under JavaParser, we feed the error back to GPT up to `maxRetries`.

4. **Final Fallback**:
  - If we still fail, we embed the chunk in the output as a comment so developers can manually address it.

## Usage

1. `mvn clean package`
2. `java -cp target/my-prod-ddd-agent-1.0-SNAPSHOT.jar \
   com.ddd.refactor.agent.ProductionGradeRefactorAgent \
   sk-OPENAIKEY /path/to/LegacyFile.java 3 300 4`

- The 3rd arg = `maxRetries`
- The 4th arg = `chunkSize`
- The 5th arg = `parallelism`

You’ll see a final JSON with each chunk’s `violation`, `reason`, and `suggestedFix`. If everything works, the aggregator code is appended to `aggregatedFix`.

## Next Steps

- **Replace** `OpenAiEmbedder` with real calls to `https://api.openai.com/v1/embeddings` or a local model.
- Integrate a real vector store or doc search service for domain code.
- Extend fallback logic, add caching, etc.
2. Run:
```bash
java -cp target/advanced-llm-agent-1.0-SNAPSHOT.jar \
     com.ddd.refactor.agent.AdvancedRefactorAgent \
     sk-OPENAIKEY /path/to/LargeFile.java 3 300
```

## Which Method to Use?

**Basic Approach**:
- Simpler integration
- Single pass per chunk
- Good for quick refactoring or partial compliance
- Falls back to heuristics for invalid code

**Advanced Agent**:
- More iterative with parse-error feedback
- Achieves higher-quality Java code
- Uses more GPT tokens due to multiple attempts
- Better for full automation with higher correctness

# Frequently Asked Questions (FAQ)

## Q1: How is this solution optimized for performance and cost?

### Chunkification
- We break large files into smaller segments (e.g., 300 lines), reducing the tokens sent to the LLM each time
- This helps avoid large prompts (which are costlier) and keeps the LLM focused on smaller, more manageable contexts

### Caching
- We compute a hash of each file's contents
- If the file hasn't changed, the tool loads a cached JSON response rather than calling the LLM again
- This dramatically reduces unnecessary API calls and saves both time and cost

### Heuristic Fallback
- If the suggested fix from GPT is unparseable or incomplete, we fall back on simpler string-based or AST-based modifications
- Examples include removing `directDbCall()` or domain checks in a repository
- This ensures we don't keep retrying on the same chunk, limiting repeated GPT usage

### Multi-Turn (Advanced Agent)
- In the more sophisticated approach, we do multiple GPT calls for a single chunk if the first attempt is invalid.Embeds each chunk and queries a Vector DB for relevant DDD framework snippets, so GPT generates accurate AbstractAggregate<ID> code.
- This increases immediate LLM usage costs but can yield higher-quality Java code, reducing developer follow-up time
- Balance the cost of these extra GPT calls against the time saved by having correct code from the outset

## Q2: What are the main costs involved?

### OpenAI/LLM API Costs
- You pay per token for input and output
- The more lines of code or the more times you iterate with GPT, the higher the token usage
- The caching system mitigates repeated usage when files are unchanged

### Developer Time
- Initial setup/configuration, plus prompt tuning for domain-specific rules
- Reviewing the final `_Refactored.java` output (since fully automated merges can still introduce unexpected changes)

### Maintenance
- GPT models may evolve; prompts or code may need to be updated
- Domain rules might change, requiring updated heuristics or prompt instructions

## Q3: Is this the right strategy for my project?
### Reduce technical Debt
Applying DDD principles can significantly reduce technical debt.Once  domain model is consistent, new features become easier to implement, and  system is more resilient to future changes in business rules or infrastructure.
Investing in a DDD-driven refactoring can pay off by enabling faster, safer iteration and long-term maintainability.
### Large/Complex Codebases
- If you have a significant amount of legacy code with recurring domain-logic violations, automated or semi-automated LLM refactoring can save substantial manual effort

### Smaller Projects
- For small or infrequently updated projects, manual refactoring or a single-shot GPT usage may be cheaper than building a full pipeline

### Domain-Driven Design Maturity
- If your team has strict domain rules (aggregates, no direct DB calls in domain, etc.), an LLM-based approach can consistently identify and fix these patterns—enforcing standards at scale

### Risk Tolerance
- Automated merges must be reviewed, especially if the LLM produces partial or incorrect changes
- If your team can handle reviewing `_Refactored.java` outputs and adjusting, then it's viable
- Otherwise, more direct, manual control may be safer

## Q4: What is the overall investment associated with this approach?

### Initial Setup & Integration
- Installing the tools, configuring the prompt, deciding on chunk size, concurrency, and fallback heuristics
- Possibly adding the advanced multi-turn agent if you want iterative parse-check loops

### Ongoing Operational Costs
- GPT usage (token-based billing)
- Developer time to maintain or evolve the refactoring rules as your domain changes

### Return on Investment (ROI)
- If your codebase is large and has many domain logic infractions, the time saved by consistent, automated detection and rewriting can outweigh token costs quickly
- If you rarely refactor or have fewer domain rules, the ROI might be lower

### Long-Term Vision
- Some teams integrate these tools into CI/CD, scanning each new PR for domain violations and automatically suggesting fixes
- This can pay off if you want an ongoing, evergreen enforcement of architecture rules

## Q5: How does JavaParser come into play, and what is an AST?

### JavaParser
- A library that reads Java source strings and builds an Abstract Syntax Tree (AST)
- If the code has invalid syntax or conflicting tokens, it throws a parse exception

### AST (Abstract Syntax Tree)
- A data structure representing the source code in a hierarchical tree: packages, classes, methods, statements, etc.
- Tools like `DddAutoRefactorTool` use it to remove or modify specific methods (e.g., `directDbCall()`) and domain checks referencing "stock"

## Q6: What are "heuristic" fixes?

### Heuristic
- Simple, pattern-based or rule-based modifications that do not rely on GPT
- For instance, removing certain methods with regex or AST scanning for specific if-statement patterns
- If GPT's suggested fix fails to parse, the fallback code ensures minimal compliance (e.g., no direct DB calls, no domain checks in repositories)

### Pros
- Reliable, no extra LLM calls, works even if GPT snippet fails

### Cons
- Potentially incomplete or overly broad
- Might remove lines you didn't intend if the pattern matches incorrectly

## Q7: Can we reduce the parse errors from GPT's output?

### Prompt Refinement
- Emphasize "Only return valid Java code, no commentary or enumerations"
- Ask for ASCII quotes instead of curly quotes
- Force JSON with a `suggestedFix` that starts with `package ...`

### Multi-Turn "Advanced Agent"
- Repeatedly feed GPT the parse error to guide it into producing valid syntax
- This typically yields higher success but uses more GPT tokens

## Q8: Could we integrate these tools into our CI/CD pipeline?

### Absolutely:

#### Pre-Commit or Pull Request Checks
- Run the tool to detect domain rule violations
- If violations are found, automatically generate `_Refactored.java` or post a comment on the PR with GPT's "suggestedFix"

#### Production Hardening
- Possibly adopt the advanced parse-checking approach so you only push code that's parseable or correct for your domain rules

## Q9: Are there potential pitfalls or disclaimers?

### LLM Outputs
- GPT can introduce subtle logic changes or formatting quirks
- A human review is still recommended

### Evolving Domain Rules
- If your domain constraints change, you must update your prompt or fallback heuristics

### Cost
- Large codebases or repeated multi-turn sessions can rack up API usage quickly if not cached or limited

### Regex or Heuristic Overreach
- If your fallback tries to remove `directDbCall()` or `if (stock > …)`, ensure it doesn't catch other similarly named variables or break code in unexpected ways
## License & Disclaimer

**Contact**: ##kiransahoo##.

**Disclaimer**:
- GPT outputs can be unpredictable
- Always review `_Refactored.java` results manually before committing
- The heuristic or auto-merge logic may inadvertently remove or alter needed code

---

DDD Refactoring tools —tailor them for your DDD or hexagonal architecture needs!