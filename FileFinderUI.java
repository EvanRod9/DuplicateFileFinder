package duplicateFileFinder;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class DuplicateFileFinderUI {
    private static final String APP_TITLE = "Duplicate File Finder";
    private static final String CANCELLED = "__CANCELLED__";
    private static final DateTimeFormatter REPORT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DuplicateFileFinderUI() {
    }

    public static void main(String[] args) {
        try {
            new DuplicateFileFinderUI().run(args);
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            printUsage();
            System.exit(1);
        } catch (IOException exception) {
            System.err.println("Finder flow failed: " + exception.getMessage());
            System.exit(1);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("Finder flow interrupted.");
            System.exit(1);
        }
    }

    private void run(String[] args) throws IOException, InterruptedException {
        ensureMacOS();
        UiConfig config = UiConfig.parse(args);

        Path root = config.root != null ? config.root : chooseFolderWithFinder();
        if (root == null) {
            return;
        }

        System.out.println("Scanning " + root.toAbsolutePath().normalize() + "...");
        DuplicateFileFinder.ScanResult scanResult = DuplicateFileFinder.scan(
            root,
            config.followSymlinks,
            totalFiles -> {
                if (totalFiles == 1 || totalFiles % 1000 == 0) {
                    System.out.println("Scanned " + totalFiles + " file(s)...");
                }
            }
        );

        if (scanResult.getDuplicateGroups().isEmpty()) {
            showInfoDialog(
                "No duplicates found in:\n" + scanResult.getRoot() + "\n\nScanned " + scanResult.getTotalFiles() + " file(s)."
            );
            return;
        }

        Path reportPath = writeReport(scanResult);
        handlePostScanActions(scanResult, reportPath);
    }

    private void handlePostScanActions(DuplicateFileFinder.ScanResult scanResult, Path reportPath)
        throws IOException, InterruptedException {
        int duplicateGroupCount = scanResult.getDuplicateGroups().size();
        int extraCopyCount = countExtraCopies(scanResult.getDuplicateGroups());
        long reclaimableBytes = countReclaimableBytes(scanResult.getDuplicateGroups());

        String summary = "Found " + duplicateGroupCount + " duplicate group(s) with "
            + extraCopyCount + " extra file(s).\n\n"
            + "Scanned folder:\n" + scanResult.getRoot() + "\n\n"
            + "Space that could be reclaimed:\n" + formatBytes(reclaimableBytes) + "\n\n"
            + "A review report was saved to:\n" + reportPath;

        while (true) {
            String action = chooseAction(
                summary,
                Arrays.asList(
                    "Open review report",
                    "Reveal report in Finder",
                    "Open scanned folder in Finder",
                    "Move extra copies to Trash",
                    "Done"
                ),
                "Open review report"
            );

            if (action == null || "Done".equals(action)) {
                return;
            }

            switch (action) {
                case "Open review report":
                    openPath(reportPath);
                    break;
                case "Reveal report in Finder":
                    revealInFinder(reportPath);
                    break;
                case "Open scanned folder in Finder":
                    openPath(scanResult.getRoot());
                    break;
                case "Move extra copies to Trash":
                    if (confirmMoveToTrash(extraCopyCount, duplicateGroupCount)) {
                        int moved = moveExtraCopiesToTrash(scanResult.getDuplicateGroups());
                        showInfoDialog(
                            "Moved " + moved + " extra file(s) to Trash.\n\n"
                                + "The first file in each duplicate group was kept."
                        );
                        return;
                    }
                    break;
                default:
                    return;
            }
        }
    }

    private static void ensureMacOS() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("mac")) {
            throw new IllegalArgumentException("DuplicateFileFinderUI uses Finder and only runs on macOS.");
        }
    }

    private static Path chooseFolderWithFinder() throws IOException, InterruptedException {
        AppleScriptResult result = runAppleScript(
            "set selectedFolder to choose folder with prompt \"Choose a folder to scan for duplicates:\"",
            "return POSIX path of selectedFolder"
        );

        if (result.cancelled) {
            return null;
        }
        if (!result.isSuccess()) {
            throw new IOException(result.errorMessage());
        }

        String folder = result.stdout.trim();
        if (folder.isEmpty()) {
            return null;
        }
        return Paths.get(folder);
    }

    private static boolean confirmMoveToTrash(int extraCopyCount, int duplicateGroupCount)
        throws IOException, InterruptedException {
        AppleScriptResult result = runAppleScript(
            "display dialog "
                + appleScriptString(
                    "Move " + extraCopyCount + " extra file(s) from " + duplicateGroupCount + " duplicate group(s) to Trash?\n\n"
                        + "The first file in each group will be kept."
                )
                + " with title "
                + appleScriptString(APP_TITLE)
                + " buttons {\"Cancel\", \"Move to Trash\"} default button \"Move to Trash\" cancel button \"Cancel\"",
            "return button returned of result"
        );

        if (result.cancelled) {
            return false;
        }
        if (!result.isSuccess()) {
            throw new IOException(result.errorMessage());
        }
        return "Move to Trash".equals(result.stdout.trim());
    }

    private static void showInfoDialog(String message) throws IOException, InterruptedException {
        AppleScriptResult result = runAppleScript(
            "display dialog "
                + appleScriptString(message)
                + " with title "
                + appleScriptString(APP_TITLE)
                + " buttons {\"OK\"} default button \"OK\""
        );

        if (!result.isSuccess() && !result.cancelled) {
            throw new IOException(result.errorMessage());
        }
    }

    private static String chooseAction(String prompt, List<String> options, String defaultOption)
        throws IOException, InterruptedException {
        String optionList = options.stream()
            .map(DuplicateFileFinderUI::appleScriptString)
            .collect(Collectors.joining(", "));
        AppleScriptResult result = runAppleScript(
            "set userChoice to choose from list {"
                + optionList
                + "} with title "
                + appleScriptString(APP_TITLE)
                + " with prompt "
                + appleScriptString(prompt)
                + " default items {"
                + appleScriptString(defaultOption)
                + "} OK button name \"Choose\" cancel button name \"Done\"",
            "if userChoice is false then return " + appleScriptString(CANCELLED),
            "return item 1 of userChoice"
        );

        if (result.cancelled) {
            return null;
        }
        if (!result.isSuccess()) {
            throw new IOException(result.errorMessage());
        }

        String choice = result.stdout.trim();
        if (CANCELLED.equals(choice)) {
            return null;
        }
        return choice;
    }

    private static int moveExtraCopiesToTrash(List<DuplicateFileFinder.DuplicateGroup> groups)
        throws IOException, InterruptedException {
        int moved = 0;
        for (DuplicateFileFinder.DuplicateGroup group : groups) {
            List<Path> files = group.getFiles();
            for (int index = 1; index < files.size(); index++) {
                if (moveOneFileToTrash(files.get(index))) {
                    moved++;
                }
            }
        }
        return moved;
    }

    private static boolean moveOneFileToTrash(Path file) throws IOException, InterruptedException {
        if (!Desktop.isDesktopSupported()) {
            throw new IOException("Desktop integration is not available for moving files to the Trash.");
        }

        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            throw new IOException("This Java runtime does not support moving files to the Trash.");
        }

        boolean moved = desktop.moveToTrash(file.toFile());
        if (!moved) {
            throw new IOException("Could not move to Trash: " + file);
        }
        return moved;
    }

    private static void openPath(Path path) throws IOException, InterruptedException {
        runCommandOrThrow(new ProcessBuilder("open", path.toString()), "Could not open " + path);
    }

    private static void revealInFinder(Path path) throws IOException, InterruptedException {
        runCommandOrThrow(new ProcessBuilder("open", "-R", path.toString()), "Could not reveal " + path + " in Finder");
    }

    private static Path writeReport(DuplicateFileFinder.ScanResult scanResult) throws IOException {
        Path reportPath = Files.createTempFile("duplicate-file-finder-", ".html");
        String html = buildHtmlReport(scanResult);
        Files.writeString(reportPath, html, StandardCharsets.UTF_8);
        return reportPath;
    }

    private static String buildHtmlReport(DuplicateFileFinder.ScanResult scanResult) {
        List<DuplicateFileFinder.DuplicateGroup> groups = scanResult.getDuplicateGroups();
        int extraCopyCount = countExtraCopies(groups);
        long reclaimableBytes = countReclaimableBytes(groups);

        StringBuilder builder = new StringBuilder();
        builder.append("<!doctype html>\n");
        builder.append("<html lang=\"en\">\n");
        builder.append("<head>\n");
        builder.append("<meta charset=\"utf-8\">\n");
        builder.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        builder.append("<title>Duplicate File Finder Report</title>\n");
        builder.append("<style>\n");
        builder.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;margin:32px;background:#f6f7f4;color:#1f2933;}\n");
        builder.append(".card{background:#fff;border:1px solid #d8dee5;border-radius:16px;padding:24px;margin-bottom:20px;box-shadow:0 10px 30px rgba(15,23,42,.06);}\n");
        builder.append("h1,h2{margin-top:0;color:#102a43;}\n");
        builder.append(".meta{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px;margin-top:18px;}\n");
        builder.append(".stat{background:#f0f4f8;border-radius:12px;padding:14px;}\n");
        builder.append(".label{font-size:12px;text-transform:uppercase;letter-spacing:.08em;color:#486581;}\n");
        builder.append(".value{font-size:20px;font-weight:700;margin-top:4px;}\n");
        builder.append("ol{padding-left:22px;}\n");
        builder.append("li{margin:10px 0;word-break:break-word;}\n");
        builder.append(".keep{color:#0b6e4f;font-weight:700;}\n");
        builder.append(".duplicate{color:#b54708;font-weight:700;}\n");
        builder.append("code{background:#f0f4f8;padding:2px 6px;border-radius:6px;}\n");
        builder.append("</style>\n");
        builder.append("</head>\n");
        builder.append("<body>\n");
        builder.append("<div class=\"card\">\n");
        builder.append("<h1>Duplicate File Finder Report</h1>\n");
        builder.append("<p>Scanned <code>")
            .append(escapeHtml(scanResult.getRoot().toString()))
            .append("</code> on ")
            .append(escapeHtml(REPORT_TIMESTAMP.format(LocalDateTime.now())))
            .append(".</p>\n");
        builder.append("<div class=\"meta\">\n");
        appendStat(builder, "Files scanned", Long.toString(scanResult.getTotalFiles()));
        appendStat(builder, "Duplicate groups", Integer.toString(groups.size()));
        appendStat(builder, "Extra copies", Integer.toString(extraCopyCount));
        appendStat(builder, "Reclaimable space", formatBytes(reclaimableBytes));
        builder.append("</div>\n");
        builder.append("</div>\n");

        int index = 1;
        for (DuplicateFileFinder.DuplicateGroup group : groups) {
            builder.append("<div class=\"card\">\n");
            builder.append("<h2>Group ").append(index).append("</h2>\n");
            builder.append("<p>Each file is <strong>")
                .append(escapeHtml(formatBytes(group.getSizeInBytes())))
                .append("</strong>. The first file is the one that will be kept.</p>\n");
            builder.append("<ol>\n");

            List<Path> files = group.getFiles();
            for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
                Path file = files.get(fileIndex);
                String badge = fileIndex == 0 ? "KEEP" : "EXTRA COPY";
                String badgeClass = fileIndex == 0 ? "keep" : "duplicate";

                builder.append("<li><span class=\"")
                    .append(badgeClass)
                    .append("\">")
                    .append(badge)
                    .append("</span><br><code>")
                    .append(escapeHtml(file.toString()))
                    .append("</code></li>\n");
            }

            builder.append("</ol>\n");
            builder.append("</div>\n");
            index++;
        }

        builder.append("</body>\n");
        builder.append("</html>\n");
        return builder.toString();
    }

    private static void appendStat(StringBuilder builder, String label, String value) {
        builder.append("<div class=\"stat\"><div class=\"label\">")
            .append(escapeHtml(label))
            .append("</div><div class=\"value\">")
            .append(escapeHtml(value))
            .append("</div></div>\n");
    }

    private static int countExtraCopies(List<DuplicateFileFinder.DuplicateGroup> groups) {
        int total = 0;
        for (DuplicateFileFinder.DuplicateGroup group : groups) {
            total += group.getDuplicateCount();
        }
        return total;
    }

    private static long countReclaimableBytes(List<DuplicateFileFinder.DuplicateGroup> groups) {
        long total = 0L;
        for (DuplicateFileFinder.DuplicateGroup group : groups) {
            total += group.getSizeInBytes() * group.getDuplicateCount();
        }
        return total;
    }

    private static String formatBytes(long bytes) {
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;

        while (value >= 1024.0 && unitIndex < units.length - 1) {
            value /= 1024.0;
            unitIndex++;
        }

        if (unitIndex == 0) {
            return bytes + " " + units[unitIndex];
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex]);
    }

    private static AppleScriptResult runAppleScript(String... scriptLines) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("osascript");
        for (String line : scriptLines) {
            command.add("-e");
            command.add(line);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = processBuilder.start();
        String stdout = readStream(process.getInputStream());
        String stderr = readStream(process.getErrorStream());
        int exitCode = process.waitFor();

        boolean cancelled = stderr.contains("User canceled");
        return new AppleScriptResult(exitCode, stdout, stderr, cancelled);
    }

    private static void runCommandOrThrow(ProcessBuilder processBuilder, String failureMessage)
        throws IOException, InterruptedException {
        Process process = processBuilder.start();
        String stdout = readStream(process.getInputStream());
        String stderr = readStream(process.getErrorStream());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String details = stderr.isBlank() ? stdout.trim() : stderr.trim();
            throw new IOException(failureMessage + (details.isEmpty() ? "" : ": " + details));
        }
    }

    private static String readStream(java.io.InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
            return builder.toString();
        }
    }

    private static String appleScriptString(String value) {
        String escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "")
            .replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }

    private static String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private static void printUsage() {
        System.out.println("Usage: java DuplicateFileFinderUI [--follow-symlinks] [folder]");
        System.out.println("If no folder is provided, Finder will prompt you to choose one.");
    }

    private static final class UiConfig {
        private final boolean followSymlinks;
        private final Path root;

        private UiConfig(boolean followSymlinks, Path root) {
            this.followSymlinks = followSymlinks;
            this.root = root;
        }

        private static UiConfig parse(String[] args) {
            boolean followSymlinks = false;
            Path root = null;

            for (String arg : args) {
                switch (arg) {
                    case "--follow-symlinks":
                        followSymlinks = true;
                        break;
                    case "--help":
                    case "-h":
                        printUsage();
                        System.exit(0);
                        break;
                    default:
                        if (arg.startsWith("--")) {
                            throw new IllegalArgumentException("Unknown option: " + arg);
                        }
                        if (root != null) {
                            throw new IllegalArgumentException("Only one folder can be provided.");
                        }
                        root = Paths.get(arg);
                        break;
                }
            }

            return new UiConfig(followSymlinks, root);
        }
    }

    private static final class AppleScriptResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;
        private final boolean cancelled;

        private AppleScriptResult(int exitCode, String stdout, String stderr, boolean cancelled) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.cancelled = cancelled;
        }

        private boolean isSuccess() {
            return exitCode == 0;
        }

        private String errorMessage() {
            if (!stderr.isBlank()) {
                return stderr.trim();
            }
            if (!stdout.isBlank()) {
                return stdout.trim();
            }
            return "Unknown AppleScript error";
        }
    }
}
