package duplicateFileFinder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DuplicateFileFinder {
    // Read large files in chunks so hashing works without loading the whole file into memory.
    private static final int BUFFER_SIZE = 1024 * 1024;

    public interface ProgressListener {
        void onFileScanned(long totalFiles);
    }

    private DuplicateFileFinder() {
    }

    public static void main(String[] args) {
        try {
            Config config = Config.parse(args);
            ScanResult scanResult = scan(config.root, config.followSymlinks, null);

            System.out.println("Scanned " + scanResult.getTotalFiles() + " file(s) in " + scanResult.getRoot());
            printDuplicates(scanResult.getDuplicateGroups());

            if (config.delete && !scanResult.getDuplicateGroups().isEmpty()) {
                int deleted = deleteDuplicates(scanResult.getDuplicateGroups());
                System.out.println();
                System.out.println("Deleted " + deleted + " duplicate file(s).");
            } else if (config.delete) {
                System.out.println("Nothing deleted.");
            }
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            printUsage();
            System.exit(1);
        } catch (IOException exception) {
            System.err.println("Scan failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    public static ScanResult scan(Path root, boolean followSymlinks, ProgressListener progressListener) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();

        if (!Files.exists(normalizedRoot)) {
            throw new IllegalArgumentException("Folder does not exist: " + normalizedRoot);
        }
        if (!Files.isDirectory(normalizedRoot)) {
            throw new IllegalArgumentException("Path is not a folder: " + normalizedRoot);
        }

        // First walk the tree and bucket files by size. Files with different sizes cannot match.
        BucketScan bucketScan = scanFiles(normalizedRoot, followSymlinks, progressListener);

        // Only size collisions go through the more expensive content-hash step.
        List<DuplicateGroup> duplicateGroups = findDuplicates(bucketScan.filesBySize);
        return new ScanResult(normalizedRoot, bucketScan.totalFiles, duplicateGroups);
    }

    public static int deleteDuplicates(List<DuplicateGroup> duplicates) {
        int deleted = 0;

        for (DuplicateGroup group : duplicates) {
            // Keep the first sorted path and remove the rest of the confirmed matches.
            Path keep = group.getKeepFile();
            System.out.println();
            System.out.println("Keeping: " + keep);

            List<Path> files = group.getFiles();
            for (int index = 1; index < files.size(); index++) {
                Path duplicate = files.get(index);
                try {
                    Files.delete(duplicate);
                    deleted++;
                    System.out.println("Deleted: " + duplicate);
                } catch (IOException exception) {
                    System.err.println("Failed to delete " + duplicate + ": " + exception.getMessage());
                }
            }
        }

        return deleted;
    }

    private static BucketScan scanFiles(Path root, boolean followSymlinks, ProgressListener progressListener)
        throws IOException {
        Map<Long, List<Path>> filesBySize = new HashMap<>();
        EnumSet<FileVisitOption> options = followSymlinks
            ? EnumSet.of(FileVisitOption.FOLLOW_LINKS)
            : EnumSet.noneOf(FileVisitOption.class);
        // The visitor records every regular file and groups it under its byte size.
        CountingVisitor visitor = new CountingVisitor(filesBySize, progressListener);
        Files.walkFileTree(root, options, Integer.MAX_VALUE, visitor);
        if (progressListener != null) {
            progressListener.onFileScanned(visitor.getTotalFiles());
        }
        return new BucketScan(filesBySize, visitor.getTotalFiles());
    }

    private static List<DuplicateGroup> findDuplicates(Map<Long, List<Path>> filesBySize) {
        List<DuplicateGroup> duplicates = new ArrayList<>();

        for (Map.Entry<Long, List<Path>> entry : filesBySize.entrySet()) {
            long sizeInBytes = entry.getKey();
            List<Path> sizeGroup = entry.getValue();
            if (sizeGroup.size() < 2) {
                continue;
            }

            // Files with the same size still might differ, so hash each candidate to confirm a real duplicate.
            Map<String, List<Path>> filesByHash = new HashMap<>();
            for (Path path : sizeGroup) {
                try {
                    String hash = sha256(path);
                    filesByHash.computeIfAbsent(hash, ignored -> new ArrayList<>()).add(path);
                } catch (IOException exception) {
                    System.err.println("Skipping unreadable file: " + path + " (" + exception.getMessage() + ")");
                }
            }

            for (List<Path> hashGroup : filesByHash.values()) {
                if (hashGroup.size() > 1) {
                    duplicates.add(new DuplicateGroup(hashGroup, sizeInBytes));
                }
            }
        }

        duplicates.sort(Comparator.comparing(group -> group.getKeepFile().toString(), String.CASE_INSENSITIVE_ORDER));
        return duplicates;
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }

        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream inputStream = Files.newInputStream(path)) {
            int bytesRead;
            // Stream the file into the digest a chunk at a time to keep memory usage predictable.
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest()) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private static void printDuplicates(List<DuplicateGroup> duplicates) {
        if (duplicates.isEmpty()) {
            System.out.println("No duplicate files found.");
            return;
        }

        System.out.println("Found " + duplicates.size() + " duplicate group(s):");
        int index = 1;
        for (DuplicateGroup group : duplicates) {
            System.out.println();
            System.out.println("Group " + index + " (" + group.getSizeInBytes() + " bytes each)");
            for (Path path : group.getFiles()) {
                System.out.println("  " + path);
            }
            index++;
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java DuplicateFileFinder [--delete] [--follow-symlinks] [folder]");
        System.out.println("If no folder is provided, the current user's home directory is scanned.");
        System.out.println("For the Finder-based macOS flow, run: java DuplicateFileFinderUI");
    }

    public static final class DuplicateGroup {
        private final List<Path> files;
        private final long sizeInBytes;

        private DuplicateGroup(List<Path> files, long sizeInBytes) {
            List<Path> sortedFiles = new ArrayList<>(files);
            sortedFiles.sort(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER));
            this.files = Collections.unmodifiableList(sortedFiles);
            this.sizeInBytes = sizeInBytes;
        }

        public List<Path> getFiles() {
            return files;
        }

        public Path getKeepFile() {
            return files.get(0);
        }

        public long getSizeInBytes() {
            return sizeInBytes;
        }

        public int getFileCount() {
            return files.size();
        }

        public int getDuplicateCount() {
            return Math.max(0, files.size() - 1);
        }
    }

    public static final class ScanResult {
        private final Path root;
        private final long totalFiles;
        private final List<DuplicateGroup> duplicateGroups;

        private ScanResult(Path root, long totalFiles, List<DuplicateGroup> duplicateGroups) {
            this.root = root;
            this.totalFiles = totalFiles;
            this.duplicateGroups = Collections.unmodifiableList(new ArrayList<>(duplicateGroups));
        }

        public Path getRoot() {
            return root;
        }

        public long getTotalFiles() {
            return totalFiles;
        }

        public List<DuplicateGroup> getDuplicateGroups() {
            return duplicateGroups;
        }
    }

    private static final class Config {
        private final boolean delete;
        private final boolean followSymlinks;
        private final Path root;

        private Config(boolean delete, boolean followSymlinks, Path root) {
            this.delete = delete;
            this.followSymlinks = followSymlinks;
            this.root = root;
        }

        private static Config parse(String[] args) {
            boolean delete = false;
            boolean followSymlinks = false;
            Path root = Paths.get(System.getProperty("user.home"));
            boolean pathProvided = false;

            for (String arg : args) {
                switch (arg) {
                    case "--delete":
                        delete = true;
                        break;
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
                        if (pathProvided) {
                            throw new IllegalArgumentException("Only one folder can be provided.");
                        }
                        // If a folder is supplied, it overrides the default "scan the user's home directory" behavior.
                        root = Paths.get(arg);
                        pathProvided = true;
                        break;
                }
            }

            return new Config(delete, followSymlinks, root);
        }
    }

    private static final class BucketScan {
        private final Map<Long, List<Path>> filesBySize;
        private final long totalFiles;

        private BucketScan(Map<Long, List<Path>> filesBySize, long totalFiles) {
            this.filesBySize = filesBySize;
            this.totalFiles = totalFiles;
        }
    }

    // FileVisitor lets us walk huge directory trees without building an in-memory list of every path first.
    private static final class CountingVisitor implements FileVisitor<Path> {
        private final Map<Long, List<Path>> filesBySize;
        private final ProgressListener progressListener;
        private long totalFiles;

        private CountingVisitor(Map<Long, List<Path>> filesBySize, ProgressListener progressListener) {
            this.filesBySize = filesBySize;
            this.progressListener = progressListener;
            this.totalFiles = 0L;
        }

        private long getTotalFiles() {
            return totalFiles;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (!attrs.isRegularFile()) {
                return FileVisitResult.CONTINUE;
            }

            filesBySize.computeIfAbsent(attrs.size(), ignored -> new ArrayList<>()).add(file);
            totalFiles++;

            if (progressListener != null && (totalFiles == 1 || totalFiles % 250 == 0)) {
                progressListener.onFileScanned(totalFiles);
            }

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            String message = exc == null ? "unknown error" : exc.getMessage();
            System.err.println("Skipping unreadable file: " + file + " (" + message + ")");
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            if (exc != null) {
                System.err.println("Skipping unreadable directory: " + dir + " (" + exc.getMessage() + ")");
            }
            return FileVisitResult.CONTINUE;
        }
    }
}
