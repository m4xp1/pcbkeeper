package com.max.privatecardbackup;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;

public final class BackupNamingTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void startsAtOneWhenNoNumericPcFilesExist() throws Exception {
        File directory = temporaryFolder.newFolder("documents");
        temporaryFolder.newFile("documents/readme.txt");
        temporaryFolder.newFile("documents/backup.pc");

        assertEquals("1.pc", BackupNaming.nextFileName(directory));
    }

    @Test
    public void usesTheNumberAfterTheLargestNumericStem() throws Exception {
        File directory = temporaryFolder.newFolder("documents");
        temporaryFolder.newFile("documents/1.pc");
        temporaryFolder.newFile("documents/9.PC");
        temporaryFolder.newFile("documents/0007.pc");
        temporaryFolder.newFile("documents/10.pc.tmp");

        assertEquals("10.pc", BackupNaming.nextFileName(directory));
    }

    @Test(expected = IllegalStateException.class)
    public void refusesNumericNamesAboveLongMaxValue() throws Exception {
        File directory = temporaryFolder.newFolder("documents");
        temporaryFolder.newFile("documents/999999999999999999999999999999.pc");

        BackupNaming.nextFileName(directory);
    }

    @Test(expected = IllegalStateException.class)
    public void refusesToOverflowLongMaxValue() throws Exception {
        File directory = temporaryFolder.newFolder("documents");
        temporaryFolder.newFile("documents/9223372036854775807.pc");

        BackupNaming.nextFileName(directory);
    }
}
