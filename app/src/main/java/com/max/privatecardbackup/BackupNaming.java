package com.max.privatecardbackup;

import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BackupNaming {
    private static final Pattern NUMBERED_PC = Pattern.compile("^(\\d+)\\.pc$", Pattern.CASE_INSENSITIVE);

    private BackupNaming() {
    }

    static String nextFileName(File documentsDirectory) {
        long max = 0L;
        File[] files = documentsDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.isFile()) {
                    continue;
                }
                Matcher matcher = NUMBERED_PC.matcher(file.getName());
                if (!matcher.matches()) {
                    continue;
                }
                try {
                    long value = Long.parseLong(matcher.group(1));
                    max = Math.max(max, value);
                } catch (NumberFormatException overflow) {
                    throw new IllegalStateException(
                            "Numeric backup name is too large: " + file.getName(),
                            overflow
                    );
                }
            }
        }
        if (max == Long.MAX_VALUE) {
            throw new IllegalStateException("No next numeric backup name is available");
        }
        return String.format(Locale.ROOT, "%d.pc", max + 1L);
    }
}
