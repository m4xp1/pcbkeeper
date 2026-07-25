package com.max.privatecardbackup;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.Objects;

final class AppPrefs {
    private static final String FILE = "backup_keeper";
    private static final String SOURCE_TREE = "source_tree";
    private static final String SD_TREE = "sd_tree";
    private static final String USER_STOPPED = "user_stopped";
    private static final String LAST_SUCCESS = "last_success";
    private static final String LAST_ERROR = "last_error";
    private static final String LAST_USB_BACKUP = "last_usb_backup";

    private static final String TX_NAME = "tx_name";
    private static final String TX_SOURCE_ID = "tx_source_id";
    private static final String TX_SHA256 = "tx_sha256";
    private static final String TX_SIZE = "tx_size";
    private static final String TX_INTERNAL_READY = "tx_internal_ready";
    private static final String TX_SD_READY = "tx_sd_ready";
    private static final String TX_SOURCE_DELETED = "tx_source_deleted";

    private final SharedPreferences prefs;

    AppPrefs(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    Uri sourceTree() {
        return parseUri(prefs.getString(SOURCE_TREE, null));
    }

    void setSourceTree(Uri uri) {
        prefs.edit().putString(SOURCE_TREE, uri.toString()).apply();
    }

    Uri sdTree() {
        return parseUri(prefs.getString(SD_TREE, null));
    }

    void setSdTree(Uri uri) {
        prefs.edit().putString(SD_TREE, uri.toString()).apply();
    }

    boolean isConfigured() {
        return sourceTree() != null && sdTree() != null;
    }

    boolean userStopped() {
        return prefs.getBoolean(USER_STOPPED, true);
    }

    void setUserStopped(boolean stopped) {
        prefs.edit().putBoolean(USER_STOPPED, stopped).apply();
    }

    String lastSuccess() {
        return prefs.getString(LAST_SUCCESS, "Never");
    }

    void setLastSuccess(String value) {
        prefs.edit().putString(LAST_SUCCESS, value).remove(LAST_ERROR).apply();
    }

    String lastError() {
        return prefs.getString(LAST_ERROR, "");
    }

    String lastUsbBackup() {
        return prefs.getString(LAST_USB_BACKUP, "Never");
    }

    void setLastUsbBackup(String value) {
        prefs.edit().putString(LAST_USB_BACKUP, value).apply();
    }

    void setLastError(String value) {
        prefs.edit().putString(LAST_ERROR, value).apply();
    }

    Transaction loadTransaction() {
        String name = prefs.getString(TX_NAME, null);
        String sourceId = prefs.getString(TX_SOURCE_ID, null);
        String sha = prefs.getString(TX_SHA256, null);
        if (name == null || sourceId == null || sha == null) {
            return null;
        }
        return new Transaction(
                name,
                sourceId,
                sha,
                prefs.getLong(TX_SIZE, -1L),
                prefs.getBoolean(TX_INTERNAL_READY, false),
                prefs.getBoolean(TX_SD_READY, false),
                prefs.getBoolean(TX_SOURCE_DELETED, false)
        );
    }

    void saveTransaction(Transaction tx) {
        prefs.edit()
                .putString(TX_NAME, tx.fileName)
                .putString(TX_SOURCE_ID, tx.sourceDocumentId)
                .putString(TX_SHA256, tx.sha256)
                .putLong(TX_SIZE, tx.size)
                .putBoolean(TX_INTERNAL_READY, tx.internalReady)
                .putBoolean(TX_SD_READY, tx.sdReady)
                .putBoolean(TX_SOURCE_DELETED, tx.sourceDeleted)
                .commit();
    }

    void clearTransaction() {
        prefs.edit()
                .remove(TX_NAME)
                .remove(TX_SOURCE_ID)
                .remove(TX_SHA256)
                .remove(TX_SIZE)
                .remove(TX_INTERNAL_READY)
                .remove(TX_SD_READY)
                .remove(TX_SOURCE_DELETED)
                .commit();
    }

    private static Uri parseUri(String value) {
        return value == null || value.isEmpty() ? null : Uri.parse(value);
    }

    static final class Transaction {
        final String fileName;
        final String sourceDocumentId;
        final String sha256;
        final long size;
        boolean internalReady;
        boolean sdReady;
        boolean sourceDeleted;

        Transaction(
                String fileName,
                String sourceDocumentId,
                String sha256,
                long size,
                boolean internalReady,
                boolean sdReady,
                boolean sourceDeleted
        ) {
            this.fileName = Objects.requireNonNull(fileName);
            this.sourceDocumentId = Objects.requireNonNull(sourceDocumentId);
            this.sha256 = Objects.requireNonNull(sha256);
            this.size = size;
            this.internalReady = internalReady;
            this.sdReady = sdReady;
            this.sourceDeleted = sourceDeleted;
        }
    }
}
