package com.max.privatecardbackup;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class UsbBackupSynchronizer {
    interface Destination {
        Hashing.Result hashExisting(String fileName) throws IOException;

        void copyVerified(File source, String fileName, long expectedSize, String expectedSha256)
                throws IOException;
    }

    interface ProgressListener {
        void onProgress(int processed, int total, String fileName);
    }

    static final class Summary {
        final int total;
        final int copied;
        final int verified;
        final List<String> conflicts;

        Summary(int total, int copied, int verified, List<String> conflicts) {
            this.total = total;
            this.copied = copied;
            this.verified = verified;
            this.conflicts = Collections.unmodifiableList(new ArrayList<>(conflicts));
        }

        boolean hasConflicts() {
            return !conflicts.isEmpty();
        }

        String message() {
            String base = "USB backup complete: " + copied + " copied, " + verified
                    + " already verified, " + total + " total";
            if (!hasConflicts()) {
                return base;
            }
            return base + "; conflicts not overwritten: " + String.join(", ", conflicts);
        }
    }

    Summary synchronize(File documents, Destination destination, ProgressListener listener)
            throws IOException {
        if (!documents.isDirectory() || !documents.canRead()) {
            throw new IOException("Internal Documents directory is not readable");
        }

        File[] listed = documents.listFiles(file -> file.isFile() && isPcFile(file.getName()));
        if (listed == null) {
            throw new IOException("Cannot list internal Documents directory");
        }

        List<File> sources = new ArrayList<>(Arrays.asList(listed));
        sources.sort(FILE_NAME_COMPARATOR);

        int copied = 0;
        int verified = 0;
        List<String> conflicts = new ArrayList<>();

        for (int index = 0; index < sources.size(); index++) {
            File source = sources.get(index);
            if (listener != null) {
                listener.onProgress(index, sources.size(), source.getName());
            }

            Hashing.Result sourceHash = hashFile(source);
            Hashing.Result existingHash = destination.hashExisting(source.getName());
            if (existingHash != null) {
                if (existingHash.matches(sourceHash.size, sourceHash.sha256)) {
                    verified++;
                } else {
                    conflicts.add(source.getName());
                }
                continue;
            }

            destination.copyVerified(source, source.getName(), sourceHash.size, sourceHash.sha256);
            Hashing.Result sourceAfterCopy = hashFile(source);
            if (!sourceAfterCopy.matches(sourceHash.size, sourceHash.sha256)) {
                throw new IOException("Source file changed during USB backup: " + source.getName());
            }
            copied++;
        }

        if (listener != null) {
            listener.onProgress(sources.size(), sources.size(), "Complete");
        }
        return new Summary(sources.size(), copied, verified, conflicts);
    }

    static boolean isPcFile(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".pc");
    }

    private static Hashing.Result hashFile(File file) throws IOException {
        if (!file.isFile()) {
            throw new FileNotFoundException(file.getAbsolutePath());
        }
        try (FileInputStream input = new FileInputStream(file)) {
            return Hashing.hash(input);
        }
    }

    private static final Comparator<File> FILE_NAME_COMPARATOR = (left, right) -> {
        Long leftNumber = numericStem(left.getName());
        Long rightNumber = numericStem(right.getName());
        if (leftNumber != null && rightNumber != null) {
            int numberOrder = Long.compare(leftNumber, rightNumber);
            if (numberOrder != 0) {
                return numberOrder;
            }
        } else if (leftNumber != null) {
            return -1;
        } else if (rightNumber != null) {
            return 1;
        }
        return left.getName().compareToIgnoreCase(right.getName());
    };

    private static Long numericStem(String name) {
        if (!isPcFile(name)) {
            return null;
        }
        String stem = name.substring(0, name.length() - 3);
        if (stem.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(stem);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
