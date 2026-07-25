package com.max.privatecardbackup;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class SafStore {
    private static final String[] PROJECTION = new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    private final ContentResolver resolver;

    SafStore(Context context) {
        resolver = context.getContentResolver();
    }

    String rootDocumentId(Uri treeUri) {
        return DocumentsContract.getTreeDocumentId(treeUri);
    }

    Uri documentUri(Uri treeUri, String documentId) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
    }

    DocumentInfo findChild(Uri treeUri, String parentDocumentId, String displayName) throws IOException {
        for (DocumentInfo child : listChildren(treeUri, parentDocumentId)) {
            if (displayName.equals(child.displayName)) {
                return child;
            }
        }
        return null;
    }

    DocumentInfo findChildIgnoreCase(Uri treeUri, String parentDocumentId, String displayName)
            throws IOException {
        for (DocumentInfo child : listChildren(treeUri, parentDocumentId)) {
            if (displayName.equalsIgnoreCase(child.displayName)) {
                return child;
            }
        }
        return null;
    }

    List<DocumentInfo> listChildren(Uri treeUri, String parentDocumentId) throws IOException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);
        List<DocumentInfo> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(childrenUri, PROJECTION, null, null, null)) {
            if (cursor == null) {
                throw new IOException("Document provider returned no cursor for " + childrenUri);
            }
            int idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE);
            int modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            while (cursor.moveToNext()) {
                result.add(new DocumentInfo(
                        cursor.getString(idIndex),
                        cursor.getString(nameIndex),
                        cursor.getString(mimeIndex),
                        cursor.isNull(sizeIndex) ? -1L : cursor.getLong(sizeIndex),
                        cursor.isNull(modifiedIndex) ? -1L : cursor.getLong(modifiedIndex)
                ));
            }
        } catch (SecurityException exception) {
            throw new IOException("Folder access permission is missing", exception);
        }
        return result;
    }

    Hashing.Result hashDocument(Uri documentUri) throws IOException {
        try (InputStream input = resolver.openInputStream(documentUri)) {
            if (input == null) {
                throw new FileNotFoundException("Cannot open " + documentUri);
            }
            return Hashing.hash(input);
        }
    }

    Hashing.Result copyDocumentToFile(Uri sourceUri, File destinationPart) throws IOException {
        File parent = destinationPart.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Cannot create destination directory");
        }
        try (InputStream input = resolver.openInputStream(sourceUri);
             FileOutputStream output = new FileOutputStream(destinationPart, false)) {
            if (input == null) {
                throw new FileNotFoundException("Cannot open source backup");
            }
            Hashing.Result result = Hashing.copyAndHash(input, output);
            output.getFD().sync();
            return result;
        }
    }

    Uri copyFileToTree(File source, Uri destinationTree, String finalName, long expectedSize, String expectedSha)
            throws IOException {
        String parentId = rootDocumentId(destinationTree);
        DocumentInfo existing = findChildIgnoreCase(destinationTree, parentId, finalName);
        if (existing != null) {
            Uri existingUri = documentUri(destinationTree, existing.documentId);
            if (hashDocument(existingUri).matches(expectedSize, expectedSha)) {
                return existingUri;
            }
            throw new IOException("SD card already contains a different file named " + finalName);
        }

        String partName = "." + finalName + ".part";
        DocumentInfo stalePart = findChildIgnoreCase(destinationTree, parentId, partName);
        if (stalePart != null) {
            deleteDocument(documentUri(destinationTree, stalePart.documentId));
        }

        Uri parentUri = documentUri(destinationTree, parentId);
        Uri partUri = DocumentsContract.createDocument(resolver, parentUri, "application/octet-stream", partName);
        if (partUri == null) {
            throw new IOException("Cannot create temporary file on SD card");
        }

        boolean keepPart = false;
        try {
            Hashing.Result copied;
            try (FileInputStream input = new FileInputStream(source);
                 ParcelFileDescriptor descriptor = resolver.openFileDescriptor(partUri, "w")) {
                if (descriptor == null) {
                    throw new FileNotFoundException("Cannot open temporary SD file");
                }
                try (FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
                    copied = Hashing.copyAndHash(input, output);
                    output.getFD().sync();
                }
            }
            if (!copied.matches(expectedSize, expectedSha)) {
                throw new IOException("Hash mismatch while writing SD copy");
            }

            // Reopen only after all write handles are closed. Some document providers
            // cannot reliably read or rename a document while it is still open for writing.
            Hashing.Result verified = hashDocument(partUri);
            if (!verified.matches(expectedSize, expectedSha)) {
                throw new IOException("Hash mismatch after reading destination copy");
            }

            // The source must still be byte-for-byte identical before the temporary
            // destination is promoted to its final name.
            Hashing.Result sourceVerified;
            try (FileInputStream input = new FileInputStream(source)) {
                sourceVerified = Hashing.hash(input);
            }
            if (!sourceVerified.matches(expectedSize, expectedSha)) {
                throw new IOException("Source changed while the destination copy was being written");
            }

            Uri renamed = DocumentsContract.renameDocument(resolver, partUri, finalName);
            if (renamed == null) {
                throw new IOException("Cannot finalize SD copy");
            }
            keepPart = true;
            return renamed;
        } finally {
            if (!keepPart) {
                try {
                    deleteDocument(partUri);
                } catch (IOException ignored) {
                    // A later retry removes the same deterministic .part file.
                }
            }
        }
    }

    boolean existsAndMatches(Uri treeUri, String fileName, long expectedSize, String expectedSha) throws IOException {
        DocumentInfo info = findChild(treeUri, rootDocumentId(treeUri), fileName);
        if (info == null || info.isDirectory()) {
            return false;
        }
        return hashDocument(documentUri(treeUri, info.documentId)).matches(expectedSize, expectedSha);
    }

    void deleteRecursively(Uri treeUri, DocumentInfo document) throws IOException {
        Uri uri = documentUri(treeUri, document.documentId);
        if (document.isDirectory()) {
            for (DocumentInfo child : listChildren(treeUri, document.documentId)) {
                deleteRecursively(treeUri, child);
            }
        }
        deleteDocument(uri);
    }

    void deleteDocument(Uri uri) throws IOException {
        try {
            if (!DocumentsContract.deleteDocument(resolver, uri)) {
                throw new IOException("Document provider refused deletion: " + uri);
            }
        } catch (FileNotFoundException notFound) {
            // Deletion is idempotent. A missing file is already in the desired state.
        } catch (SecurityException exception) {
            throw new IOException("Folder write permission is missing", exception);
        }
    }

    static final class DocumentInfo {
        final String documentId;
        final String displayName;
        final String mimeType;
        final long size;
        final long lastModified;

        DocumentInfo(String documentId, String displayName, String mimeType, long size, long lastModified) {
            this.documentId = Objects.requireNonNull(documentId);
            this.displayName = displayName == null ? "" : displayName;
            this.mimeType = mimeType == null ? "" : mimeType;
            this.size = size;
            this.lastModified = lastModified;
        }

        boolean isDirectory() {
            return DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);
        }
    }
}
