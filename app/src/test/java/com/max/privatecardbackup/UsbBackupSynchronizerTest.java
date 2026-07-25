package com.max.privatecardbackup;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UsbBackupSynchronizerTest {
    @Test
    public void copiesMissingAndVerifiesExistingWithoutOverwritingConflicts() throws Exception {
        File documents = Files.createTempDirectory("pcbk-usb-source-").toFile();
        File usb = Files.createTempDirectory("pcbk-usb-destination-").toFile();
        write(new File(documents, "1.pc"), "one");
        write(new File(documents, "3.PC"), "three");
        write(new File(documents, "ignored.txt"), "ignore");
        write(new File(usb, "1.pc"), "one");

        UsbBackupSynchronizer synchronizer = new UsbBackupSynchronizer();
        UsbBackupSynchronizer.Summary first = synchronizer.synchronize(
                documents,
                new FileDestination(usb),
                null
        );
        assertEquals(2, first.total);
        assertEquals(1, first.copied);
        assertEquals(1, first.verified);
        assertFalse(first.hasConflicts());
        assertArrayEquals(read(new File(documents, "3.PC")), read(new File(usb, "3.PC")));

        write(new File(usb, "3.PC"), "corrupted");
        write(new File(documents, "4.pc"), "four");
        byte[] conflictingBefore = read(new File(usb, "3.PC"));

        UsbBackupSynchronizer.Summary second = synchronizer.synchronize(
                documents,
                new FileDestination(usb),
                null
        );
        assertEquals(3, second.total);
        assertEquals(1, second.copied);
        assertEquals(1, second.verified);
        assertEquals(Arrays.asList("3.PC"), second.conflicts);
        assertArrayEquals(conflictingBefore, read(new File(usb, "3.PC")));
        assertArrayEquals(read(new File(documents, "4.pc")), read(new File(usb, "4.pc")));
    }

    @Test
    public void interruptedDestinationWriteNeverChangesSources() throws Exception {
        File documents = Files.createTempDirectory("pcbk-usb-source-").toFile();
        File source = new File(documents, "7.pc");
        write(source, "valuable backup");
        byte[] before = read(source);

        boolean failed = false;
        try {
            new UsbBackupSynchronizer().synchronize(
                    documents,
                    new UsbBackupSynchronizer.Destination() {
                        @Override
                        public Hashing.Result hashExisting(String fileName) {
                            return null;
                        }

                        @Override
                        public void copyVerified(
                                File ignored,
                                String fileName,
                                long expectedSize,
                                String expectedSha256
                        ) throws IOException {
                            throw new IOException("simulated unplug");
                        }
                    },
                    null
            );
        } catch (IOException expected) {
            failed = true;
        }
        assertTrue(failed);
        assertArrayEquals(before, read(source));
    }

    private static final class FileDestination implements UsbBackupSynchronizer.Destination {
        private final File directory;

        FileDestination(File directory) {
            this.directory = directory;
        }

        @Override
        public Hashing.Result hashExisting(String fileName) throws IOException {
            File file = new File(directory, fileName);
            if (!file.exists()) {
                return null;
            }
            try (FileInputStream input = new FileInputStream(file)) {
                return Hashing.hash(input);
            }
        }

        @Override
        public void copyVerified(File source, String fileName, long expectedSize, String expectedSha256)
                throws IOException {
            File part = new File(directory, "." + fileName + ".part");
            File destination = new File(directory, fileName);
            if (part.exists() && !part.delete()) {
                throw new IOException("cannot remove stale part");
            }
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(part)) {
                Hashing.Result copied = Hashing.copyAndHash(input, output);
                output.getFD().sync();
                if (!copied.matches(expectedSize, expectedSha256)) {
                    throw new IOException("copy mismatch");
                }
            }
            try (FileInputStream input = new FileInputStream(part)) {
                if (!Hashing.hash(input).matches(expectedSize, expectedSha256)) {
                    throw new IOException("reread mismatch");
                }
            }
            if (!part.renameTo(destination)) {
                throw new IOException("rename failed");
            }
        }
    }

    private static void write(File file, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static byte[] read(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }
}
