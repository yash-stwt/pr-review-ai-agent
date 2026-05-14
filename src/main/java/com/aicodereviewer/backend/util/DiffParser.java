package com.aicodereviewer.backend.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses unified git diff text into structured per-file records
 * with accurate old/new line numbers, language detection, change type,
 * binary flag, hunk grouping, and line mapping.
 */
public final class DiffParser {

    private DiffParser() {}

    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@(.*)$");

    // ── Language detection ─────────────────────────────────────────────────────

    private static final Map<String, String> EXTENSION_TO_LANGUAGE = Map.ofEntries(
            Map.entry("java", "java"),
            Map.entry("js", "javascript"),
            Map.entry("jsx", "jsx"),
            Map.entry("ts", "typescript"),
            Map.entry("tsx", "tsx"),
            Map.entry("py", "python"),
            Map.entry("go", "go"),
            Map.entry("rs", "rust"),
            Map.entry("kt", "kotlin"),
            Map.entry("cs", "csharp"),
            Map.entry("cpp", "cpp"),
            Map.entry("c", "c"),
            Map.entry("rb", "ruby"),
            Map.entry("php", "php"),
            Map.entry("swift", "swift"),
            Map.entry("yaml", "yaml"),
            Map.entry("yml", "yaml"),
            Map.entry("json", "json"),
            Map.entry("xml", "xml"),
            Map.entry("sql", "sql"),
            Map.entry("sh", "bash"),
            Map.entry("md", "markdown")
    );

    /**
     * Detect programming language from file path extension.
     * Returns "plaintext" if the extension is unknown.
     */
    public static String detectLanguage(String filePath) {
        if (filePath == null || filePath.isBlank()) return "plaintext";
        int dotIndex = filePath.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filePath.length() - 1) return "plaintext";
        String ext = filePath.substring(dotIndex + 1).toLowerCase();
        return EXTENSION_TO_LANGUAGE.getOrDefault(ext, "plaintext");
    }

    // ── Public types ──────────────────────────────────────────────────────────

    public record DiffLine(
            String filePath,
            int oldLineNumber,  // -1 when line was added (no old-side number)
            int newLineNumber,  // -1 when line was removed (no new-side number)
            String type,        // "added" | "removed" | "context"
            String content
    ) {}

    public record Hunk(
            String header,      // raw @@ line content after @@
            int oldStart,
            int newStart,
            List<DiffLine> lines
    ) {}

    public record DiffFile(
            String oldPath,     // a/ path (null for new files)
            String filePath,    // b/ path (canonical)
            String language,    // detected from extension
            String changeType,  // "added" | "deleted" | "modified" | "renamed" | "binary"
            boolean binary,
            int linesAdded,
            int linesRemoved,
            List<Hunk> hunks,
            Map<Integer, Integer> lineMap  // newLineNumber -> oldLineNumber
    ) {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Parse a full unified diff string into a list of enriched per-file structures.
     */
    public static List<DiffFile> parse(String diffText) {
        List<DiffFile> files = new ArrayList<>();
        if (diffText == null || diffText.isBlank()) return files;

        String[] rawLines = diffText.split("\n");

        String currentOldPath = null;
        String currentNewPath = null;
        String currentChangeType = "modified";
        boolean currentBinary = false;
        List<Hunk> currentHunks = null;
        List<DiffLine> currentHunkLines = null;
        String currentHunkHeader = null;
        int currentHunkOldStart = 0;
        int currentHunkNewStart = 0;
        int oldLine = 0;
        int newLine = 0;

        for (String raw : rawLines) {
            // New file block
            Matcher headerMatcher = DIFF_HEADER.matcher(raw);
            if (headerMatcher.matches()) {
                // Flush previous file
                if (currentNewPath != null) {
                    flushHunk(currentHunks, currentHunkLines, currentHunkHeader, currentHunkOldStart, currentHunkNewStart);
                    files.add(buildDiffFile(currentOldPath, currentNewPath, currentChangeType, currentBinary, currentHunks));
                }
                currentOldPath = headerMatcher.group(1);
                currentNewPath = headerMatcher.group(2);
                currentChangeType = "modified";
                currentBinary = false;
                currentHunks = new ArrayList<>();
                currentHunkLines = null;
                currentHunkHeader = null;
                currentHunkOldStart = 0;
                currentHunkNewStart = 0;
                oldLine = 0;
                newLine = 0;
                continue;
            }

            if (currentNewPath == null) continue;

            // Detect change type from metadata lines
            if (raw.startsWith("new file mode")) {
                currentChangeType = "added";
                continue;
            }
            if (raw.startsWith("deleted file mode")) {
                currentChangeType = "deleted";
                continue;
            }
            if (raw.startsWith("rename from") || raw.startsWith("rename to")) {
                currentChangeType = "renamed";
                continue;
            }
            if (raw.startsWith("Binary files")) {
                currentChangeType = "binary";
                currentBinary = true;
                continue;
            }

            // Hunk header — start a new hunk
            Matcher hunkMatcher = HUNK_HEADER.matcher(raw);
            if (hunkMatcher.find()) {
                // Flush previous hunk
                flushHunk(currentHunks, currentHunkLines, currentHunkHeader, currentHunkOldStart, currentHunkNewStart);

                currentHunkOldStart = Integer.parseInt(hunkMatcher.group(1));
                currentHunkNewStart = Integer.parseInt(hunkMatcher.group(2));
                currentHunkHeader = hunkMatcher.group(3) != null ? hunkMatcher.group(3).trim() : "";
                currentHunkLines = new ArrayList<>();
                oldLine = currentHunkOldStart;
                newLine = currentHunkNewStart;
                continue;
            }

            // Skip file metadata lines
            if (raw.startsWith("---") || raw.startsWith("+++")
                    || raw.startsWith("index ") || raw.startsWith("similarity index")
                    || raw.startsWith("copy from") || raw.startsWith("copy to")) {
                continue;
            }

            if (currentHunkLines == null) continue;

            if (raw.startsWith("+")) {
                currentHunkLines.add(new DiffLine(currentNewPath, -1, newLine, "added", raw.substring(1)));
                newLine++;
            } else if (raw.startsWith("-")) {
                currentHunkLines.add(new DiffLine(currentNewPath, oldLine, -1, "removed", raw.substring(1)));
                oldLine++;
            } else if (raw.startsWith(" ")) {
                currentHunkLines.add(new DiffLine(currentNewPath, oldLine, newLine, "context", raw.substring(1)));
                oldLine++;
                newLine++;
            }
            // Lines starting with \ (e.g., "\ No newline at end of file") are skipped
        }

        // Flush last file
        if (currentNewPath != null) {
            flushHunk(currentHunks, currentHunkLines, currentHunkHeader, currentHunkOldStart, currentHunkNewStart);
            files.add(buildDiffFile(currentOldPath, currentNewPath, currentChangeType, currentBinary, currentHunks));
        }

        return files;
    }

    /**
     * Returns a map of filePath → list of new-side line numbers that were added/modified.
     * Used to fall back when an AI finding has no explicit lineNumber.
     * BACKWARD COMPATIBLE — same signature and behavior as before.
     */
    public static Map<String, List<Integer>> getChangedLineNumbers(String diffText) {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        for (DiffFile file : parse(diffText)) {
            List<Integer> lines = new ArrayList<>();
            for (Hunk hunk : file.hunks()) {
                for (DiffLine line : hunk.lines()) {
                    if ("added".equals(line.type()) && line.newLineNumber() > 0) {
                        lines.add(line.newLineNumber());
                    }
                }
            }
            result.put(file.filePath(), lines);
        }
        return result;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void flushHunk(List<Hunk> hunks, List<DiffLine> hunkLines, String header, int oldStart, int newStart) {
        if (hunkLines != null && !hunkLines.isEmpty()) {
            hunks.add(new Hunk(header != null ? header : "", oldStart, newStart, List.copyOf(hunkLines)));
        }
    }

    private static DiffFile buildDiffFile(String oldPath, String newPath, String changeType, boolean binary, List<Hunk> hunks) {
        String language = detectLanguage(newPath);
        int linesAdded = 0;
        int linesRemoved = 0;
        Map<Integer, Integer> lineMap = new LinkedHashMap<>();

        for (Hunk hunk : hunks) {
            for (DiffLine line : hunk.lines()) {
                switch (line.type()) {
                    case "added" -> linesAdded++;
                    case "removed" -> linesRemoved++;
                    case "context" -> {
                        if (line.newLineNumber() > 0 && line.oldLineNumber() > 0) {
                            lineMap.put(line.newLineNumber(), line.oldLineNumber());
                        }
                    }
                }
            }
        }

        return new DiffFile(oldPath, newPath, language, changeType, binary, linesAdded, linesRemoved, List.copyOf(hunks), Map.copyOf(lineMap));
    }
}
