package com.max.privatecardbackup;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

final class BackupProcessor {
    static final String BACKUP_FILE = "PrivateCardBackup.pc";
    static final String BACKUPS_DIRECTORY = "Backups";
    static final String PICTURES_DIRECTORY = "Pictures";

    private final AppPrefs prefs;
    private final SafStore saf;
    private final StabilityTracker stability = new StabilityTracker();

    BackupProcessor(Context context) {
        prefs = new AppPrefs(context);
        saf = new SafStore(context);
    }

    Result scanAndProcess() throws IOException {
        Uri sourceTree = prefs.sourceTree();
        Uri sdTree = prefs.sdTree();
        if (sourceTree == null || sdTree == null) {
            return Result.idle("Initial folder setup is required");
        }

        File documents = internalDocumentsDirectory();
        if ((!documents.isDirectory() && !documents.mkdirs()) || !documents.canWrite()) {
            throw new IOException("Internal Documents directory is not writable");
        }

        String sourceRootId = saf.rootDocumentId(sourceTree);
        SafStore.DocumentInfo backupsDirectory = saf.findChild(sourceTree, sourceRootId, BACKUPS_DIRECTORY);
        if (backupsDirectory == null || !backupsDirectory.isDirectory()) {
            throw new IOException("The selected source folder does not contain Backups");
        }

        AppPrefs.Transaction transaction = prefs.loadTransaction();
        if (transaction == null) {
            SafStore.DocumentInfo source = saf.findChild(sourceTree, backupsDirectory.documentId, BACKUP_FILE);
            if (source == null) {
                stability.reset();
                return Result.idle("Waiting for " + BACKUP_FILE);
            }
            if (source.isDirectory()) {
                throw new IOException(BACKUP_FILE + " is unexpectedly a directory");
            }
            if (!stability.observe(
                    source.documentId,
                    source.size,
                    source.lastModified,
                    SystemClock.elapsedRealtime()
            )) {
                return Result.idle("Backup detected; waiting until writing is complete");
            }

            Uri sourceUri = saf.documentUri(sourceTree, source.documentId);
            Hashing.Result sourceHash = saf.hashDocument(sourceUri);
            if (sourceHash.size <= 0L) {
                stability.reset();
                return Result.idle("Backup is empty; waiting for a valid file");
            }

            String finalName = BackupNaming.nextFileName(documents);
            transaction = new AppPrefs.Transaction(
                    finalName,
                    source.documentId,
                    sourceHash.sha256,
                    sourceHash.size,
                    false,
                    false,
                    false
            );
            prefs.saveTransaction(transaction);
        }

        return continueTransaction(sourceTree, sdTree, sourceRootId, backupsDirectory, documents, transaction);
    }

    private Result continueTransaction(
            Uri sourceTree,
            Uri sdTree,
            String sourceRootId,
            SafStore.DocumentInfo backupsDirectory,
            File documents,
            AppPrefs.Transaction tx
    ) throws IOException {
        File finalInternal = new File(documents, tx.fileName);
        if (!tx.internalReady) {
            if (finalInternal.isFile()) {
                Hashing.Result existing = hashFile(finalInternal);
                if (!existing.matches(tx.size, tx.sha256)) {
                    throw new IOException("Internal Documents contains a different file named " + tx.fileName);
                }
            } else {
                SafStore.DocumentInfo source = findByDocumentId(
                        saf.listChildren(sourceTree, backupsDirectory.documentId),
                        tx.sourceDocumentId
                );
                if (source == null) {
                    throw new IOException("Source backup disappeared before the internal copy was completed");
                }
                File part = new File(documents, "." + tx.fileName + ".part");
                if (part.exists() && !part.delete()) {
                    throw new IOException("Cannot remove stale internal temporary file");
                }
                Hashing.Result copied = saf.copyDocumentToFile(
                        saf.documentUri(sourceTree, source.documentId),
                        part
                );
                if (!copied.matches(tx.size, tx.sha256)) {
                    part.delete();
                    prefs.clearTransaction();
                    stability.reset();
                    throw new SourceChangedException("Source backup changed while it was being copied; retrying safely");
                }
                Hashing.Result verified = hashFile(part);
                if (!verified.matches(tx.size, tx.sha256)) {
                    part.delete();
                    throw new IOException("Internal copy failed SHA-256 verification");
                }
                if (finalInternal.exists()) {
                    part.delete();
                    throw new IOException("Internal destination appeared during copying: " + tx.fileName);
                }
                if (!part.renameTo(finalInternal)) {
                    part.delete();
                    throw new IOException("Cannot finalize the internal backup copy");
                }
            }
            tx.internalReady = true;
            prefs.saveTransaction(tx);
        }

        Hashing.Result internalHash = hashFile(finalInternal);
        if (!internalHash.matches(tx.size, tx.sha256)) {
            throw new IOException("Verified internal copy later failed integrity checking");
        }

        if (!tx.sdReady) {
            saf.copyFileToTree(finalInternal, sdTree, tx.fileName, tx.size, tx.sha256);
            tx.sdReady = true;
            prefs.saveTransaction(tx);
        } else if (!saf.existsAndMatches(sdTree, tx.fileName, tx.size, tx.sha256)) {
            throw new IOException("Verified SD copy is missing or corrupted");
        }

        if (!tx.sourceDeleted) {
            List<SafStore.DocumentInfo> currentBackupFiles = saf.listChildren(sourceTree, backupsDirectory.documentId);
            SafStore.DocumentInfo originalSource = findByDocumentId(currentBackupFiles, tx.sourceDocumentId);
            SafStore.DocumentInfo currentNamedSource = findByName(currentBackupFiles, BACKUP_FILE);

            if (originalSource == null) {
                if (currentNamedSource != null) {
                    // A new backup replaced the old one. Preserve it and process it as the next transaction.
                    prefs.clearTransaction();
                    stability.reset();
                    return Result.success(tx.fileName + " archived; a newer source backup was preserved");
                }
                tx.sourceDeleted = true;
                prefs.saveTransaction(tx);
            } else {
                Hashing.Result currentSourceHash = saf.hashDocument(
                        saf.documentUri(sourceTree, originalSource.documentId)
                );
                if (!currentSourceHash.matches(tx.size, tx.sha256)) {
                    // Never delete a source that no longer matches the copies we verified.
                    prefs.clearTransaction();
                    stability.reset();
                    return Result.success(tx.fileName + " archived; changed source was preserved for the next run");
                }
                saf.deleteDocument(saf.documentUri(sourceTree, originalSource.documentId));
                tx.sourceDeleted = true;
                prefs.saveTransaction(tx);
            }
        }

        SafStore.DocumentInfo pictures = saf.findChild(sourceTree, sourceRootId, PICTURES_DIRECTORY);
        if (pictures != null) {
            if (!pictures.isDirectory()) {
                throw new IOException("Pictures exists but is not a directory");
            }
            saf.deleteRecursively(sourceTree, pictures);
        }

        prefs.clearTransaction();
        stability.reset();
        return Result.success(tx.fileName + " saved and verified in both Documents folders");
    }

    private static SafStore.DocumentInfo findByDocumentId(
            List<SafStore.DocumentInfo> documents,
            String documentId
    ) {
        for (SafStore.DocumentInfo document : documents) {
            if (documentId.equals(document.documentId)) {
                return document;
            }
        }
        return null;
    }

    private static SafStore.DocumentInfo findByName(
            List<SafStore.DocumentInfo> documents,
            String displayName
    ) {
        for (SafStore.DocumentInfo document : documents) {
            if (displayName.equals(document.displayName)) {
                return document;
            }
        }
        return null;
    }


    @SuppressWarnings("deprecation")
    private static File internalDocumentsDirectory() {
        // targetSdk 29 + requestLegacyExternalStorage is intentional for Android 11.
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
    }

    private static Hashing.Result hashFile(File file) throws IOException {
        if (!file.isFile()) {
            throw new FileNotFoundException(file.getAbsolutePath());
        }
        try (FileInputStream input = new FileInputStream(file)) {
            return Hashing.hash(input);
        }
    }

    static final class SourceChangedException extends IOException {
        private static final long serialVersionUID = 1L;

        SourceChangedException(String message) {
            super(message);
        }
    }

    static final class Result {
        final boolean success;
        final String message;

        private Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        static Result idle(String message) {
            return new Result(false, message);
        }

        static Result success(String message) {
            return new Result(true, message);
        }
    }
}
