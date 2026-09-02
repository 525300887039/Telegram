package org.telegram.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.secretmedia.EncryptedFileInputStream;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * A database independent from Telegram's message database.  It stores only
 * messages explicitly preserved by the optional anti-delete/view-once tools.
 */
public final class LocalMessageArchive extends SQLiteOpenHelper {

    public static final int REASON_REMOTE_DELETE = 1;
    public static final int REASON_VIEW_ONCE = 2;

    private static final int DATABASE_VERSION = 1;
    private static final LocalMessageArchive[] instances = new LocalMessageArchive[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private final Object databaseLock = new Object();

    public static LocalMessageArchive getInstance(int account) {
        LocalMessageArchive result = instances[account];
        if (result == null) {
            synchronized (instances) {
                result = instances[account];
                if (result == null) {
                    result = instances[account] = new LocalMessageArchive(ApplicationLoader.applicationContext, account);
                }
            }
        }
        return result;
    }

    private LocalMessageArchive(Context context, int account) {
        super(context, "local_message_archive_" + account + ".db", null, DATABASE_VERSION);
        currentAccount = account;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE archived_messages (dialog_id INTEGER NOT NULL, mid INTEGER NOT NULL, date INTEGER NOT NULL, topic_id INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER NOT NULL, reason INTEGER NOT NULL, data BLOB NOT NULL, media_path TEXT, PRIMARY KEY(dialog_id, mid))");
        db.execSQL("CREATE INDEX archived_messages_date_idx ON archived_messages(dialog_id, date DESC)");
        db.execSQL("CREATE INDEX archived_messages_deleted_idx ON archived_messages(deleted_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public void archiveMessageObject(MessageObject messageObject, int reason) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return;
        }
        archiveMessage(messageObject.getDialogId(), messageObject.messageOwner, reason);
    }

    public void archiveMessage(long dialogId, TLRPC.Message message, int reason) {
        if (!PrivacyControls.isSupportedDialog(dialogId) || message == null || PrivacyControls.isPaidMedia(message)) {
            return;
        }
        try {
            final int topicId = (int) MessageObject.getTopicId(currentAccount, message, 0);
            final byte[] data = serializeMessage(message);
            ContentValues values = new ContentValues();
            values.put("dialog_id", dialogId);
            values.put("mid", message.id);
            values.put("date", message.date);
            values.put("topic_id", topicId);
            values.put("deleted_at", System.currentTimeMillis());
            values.put("reason", reason);
            values.put("data", data);
            synchronized (databaseLock) {
                SQLiteDatabase database = getWritableDatabase();
                int updated = database.update("archived_messages", values, "dialog_id = ? AND mid = ?", new String[]{String.valueOf(dialogId), String.valueOf(message.id)});
                if (updated == 0) {
                    database.insert("archived_messages", null, values);
                }
            }
            if (SharedConfig.keepDeletedMediaEnabled || reason == REASON_VIEW_ONCE) {
                archiveMediaAsync(dialogId, message, reason == REASON_VIEW_ONCE);
            }
            cleanupExpiredAsync();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void prepareMediaForExport(MessageObject messageObject, Utilities.Callback<File> callback) {
        if (callback == null) {
            return;
        }
        if (!PrivacyControls.canRepeatViewOnce(messageObject)) {
            AndroidUtilities.runOnUIThread(() -> callback.run(null));
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            File file = null;
            try {
                archiveMessageObject(messageObject, REASON_VIEW_ONCE);
                file = archiveMedia(messageObject.getDialogId(), messageObject.messageOwner, true);
            } catch (Exception e) {
                FileLog.e(e);
            }
            final File result = file;
            AndroidUtilities.runOnUIThread(() -> callback.run(result));
        });
    }

    public ArrayList<TLRPC.Message> loadMessages(long dialogId, ArrayList<TLRPC.Message> loadedMessages, int maxId, int count, long threadMessageId) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        if ((!SharedConfig.antiDeleteEnabled && !SharedConfig.repeatViewOnceEnabled) || !PrivacyControls.isSupportedDialog(dialogId)) {
            return result;
        }
        int minDate = 0;
        int maxDate = Integer.MAX_VALUE;
        if (loadedMessages != null && !loadedMessages.isEmpty()) {
            minDate = Integer.MAX_VALUE;
            int loadedMaxDate = 0;
            for (int i = 0; i < loadedMessages.size(); i++) {
                TLRPC.Message message = loadedMessages.get(i);
                if (message.date > 0) {
                    minDate = Math.min(minDate, message.date);
                    loadedMaxDate = Math.max(loadedMaxDate, message.date);
                }
            }
            if (minDate == Integer.MAX_VALUE) {
                minDate = 0;
            }
            if (maxId > 0 && loadedMaxDate > 0) {
                maxDate = loadedMaxDate;
            }
        }
        StringBuilder selection = new StringBuilder("dialog_id = ?");
        ArrayList<String> args = new ArrayList<>();
        args.add(String.valueOf(dialogId));
        if (!SharedConfig.antiDeleteEnabled) {
            selection.append(" AND reason = ?");
            args.add(String.valueOf(REASON_VIEW_ONCE));
        }
        if (threadMessageId != 0) {
            selection.append(" AND topic_id = ?");
            args.add(String.valueOf(threadMessageId));
        }
        if (minDate > 0) {
            selection.append(" AND date >= ?");
            args.add(String.valueOf(minDate));
            if (maxDate != Integer.MAX_VALUE) {
                selection.append(" AND date <= ?");
                args.add(String.valueOf(maxDate));
            }
        }
        if (maxId > 0) {
            selection.append(" AND mid < ?");
            args.add(String.valueOf(maxId));
        }
        Cursor cursor = null;
        try {
            synchronized (databaseLock) {
                cursor = getReadableDatabase().query("archived_messages", new String[]{"data", "media_path"}, selection.toString(), args.toArray(new String[0]), null, null, "date DESC, mid DESC", String.valueOf(Math.max(count, 50)));
                while (cursor.moveToNext()) {
                    byte[] data = cursor.getBlob(0);
                    TLRPC.Message message = deserializeMessage(data);
                    if (message == null) {
                        continue;
                    }
                    String mediaPath = cursor.isNull(1) ? null : cursor.getString(1);
                    if (mediaPath != null && new File(mediaPath).exists()) {
                        message.attachPath = mediaPath;
                    }
                    message.dialog_id = dialogId;
                    result.add(message);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    public HashSet<Integer> mergeMessages(long dialogId, ArrayList<TLRPC.Message> destination, ArrayList<TLRPC.Message> archived) {
        HashSet<Integer> archivedIds = new HashSet<>();
        if (archived == null || archived.isEmpty()) {
            return archivedIds;
        }
        HashSet<Integer> existingIds = new HashSet<>();
        for (int i = 0; i < destination.size(); i++) {
            existingIds.add(destination.get(i).id);
        }
        for (int i = 0; i < archived.size(); i++) {
            TLRPC.Message message = archived.get(i);
            if (existingIds.add(message.id)) {
                message.dialog_id = dialogId;
                destination.add(message);
                archivedIds.add(message.id);
            }
        }
        destination.sort((left, right) -> {
            int dateCompare = Integer.compare(right.date, left.date);
            return dateCompare != 0 ? dateCompare : Integer.compare(right.id, left.id);
        });
        return archivedIds;
    }

    public void clear() {
        Utilities.globalQueue.postRunnable(() -> {
            synchronized (databaseLock) {
                ArrayList<String> paths = collectMediaPaths(0);
                getWritableDatabase().delete("archived_messages", null, null);
                deleteFiles(paths);
            }
        });
    }

    public void cleanupExpiredAsync() {
        Utilities.globalQueue.postRunnable(() -> {
            int retentionDays = SharedConfig.localArchiveRetentionDays;
            if (retentionDays <= 0) {
                return;
            }
            long threshold = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L;
            synchronized (databaseLock) {
                ArrayList<String> paths = collectMediaPaths(threshold);
                getWritableDatabase().delete("archived_messages", "deleted_at < ?", new String[]{String.valueOf(threshold)});
                deleteFiles(paths);
            }
        });
    }

    private void archiveMediaAsync(long dialogId, TLRPC.Message message, boolean exportable) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                archiveMedia(dialogId, message, exportable);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private File archiveMedia(long dialogId, TLRPC.Message message, boolean exportable) throws Exception {
        File source = resolveMediaFile(message, exportable);
        if (source == null) {
            return null;
        }
        File root = exportable ? ApplicationLoader.applicationContext.getExternalFilesDir(null) : ApplicationLoader.applicationContext.getFilesDir();
        if (root == null) {
            return null;
        }
        File directory = new File(root, "privacy_archive/account_" + currentAccount);
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }
        String previousPath = message.attachPath;
        String suffix = exportable ? archiveSuffix(source, message) : source.getName().contains(".") ? source.getName().substring(source.getName().lastIndexOf('.')) : ".bin";
        File target = new File(directory, dialogId + "_" + message.id + suffix);
        boolean sameFile = source.getCanonicalFile().equals(target.getCanonicalFile());
        if (!sameFile) {
            File temporary = new File(target.getAbsolutePath() + ".tmp");
            try {
                if (exportable && source.getName().endsWith(".enc")) {
                    try (InputStream input = new EncryptedFileInputStream(source, encryptionKeyFor(source, message))) {
                        copyFile(input, temporary);
                    }
                } else {
                    try (InputStream input = new FileInputStream(source)) {
                        copyFile(input, temporary);
                    }
                }
                if (target.exists() && !target.delete()) {
                    return null;
                }
                if (!temporary.renameTo(target)) {
                    try (InputStream input = new FileInputStream(temporary)) {
                        copyFile(input, target);
                    } catch (Exception e) {
                        target.delete();
                        throw e;
                    }
                }
            } finally {
                if (temporary.exists() && !temporary.delete()) {
                    FileLog.d("Unable to delete temporary local archive file " + temporary);
                }
            }
        }
        ContentValues values = new ContentValues();
        values.put("media_path", target.getAbsolutePath());
        final int updated;
        synchronized (databaseLock) {
            updated = getWritableDatabase().update("archived_messages", values, "dialog_id = ? AND mid = ?", new String[]{String.valueOf(dialogId), String.valueOf(message.id)});
        }
        if (updated == 0) {
            if (target.exists() && !target.delete()) {
                FileLog.d("Unable to delete untracked local archive file " + target);
            }
            return null;
        }
        message.attachPath = target.getAbsolutePath();
        deleteReplacedArchive(previousPath, target);
        return target;
    }

    static String archiveSuffix(String sourceName) {
        String plainName = sourceName.endsWith(".enc") ? sourceName.substring(0, sourceName.length() - 4) : sourceName;
        int extensionStart = plainName.lastIndexOf('.');
        return extensionStart >= 0 ? plainName.substring(extensionStart) : ".bin";
    }

    private String archiveSuffix(File source, TLRPC.Message message) {
        String suffix = archiveSuffix(source.getName());
        if (!".bin".equals(suffix)) {
            return suffix;
        }
        File original = getOriginalEncryptedPath(message);
        return original == null ? suffix : archiveSuffix(original.getName());
    }

    private File resolveMediaFile(TLRPC.Message message, boolean exportable) {
        File attached = null;
        if (message.attachPath != null && !message.attachPath.isEmpty()) {
            attached = new File(message.attachPath);
            if ((!exportable || !attached.getName().endsWith(".enc")) && isUsableMediaSource(attached, exportable, message)) {
                return attached;
            }
        }
        File file = FileLoader.getInstance(currentAccount).getPathToMessage(message);
        if (isUsableMediaSource(file, exportable, message)) {
            return file;
        }
        File encrypted = file == null ? null : new File(file.getAbsolutePath() + ".enc");
        if (isUsableMediaSource(encrypted, exportable, message)) {
            return encrypted;
        }
        if (exportable) {
            File originalEncrypted = getOriginalEncryptedPath(message);
            if (isUsableMediaSource(originalEncrypted, true, message)) {
                return originalEncrypted;
            }
        }
        return isUsableMediaSource(attached, exportable, message) ? attached : null;
    }

    private File getOriginalEncryptedPath(TLRPC.Message message) {
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media == null) {
            return null;
        }
        File plain;
        if (media.document != null) {
            plain = FileLoader.getInstance(currentAccount).getPathToAttach(media.document, null, true, true);
        } else if (media.photo != null) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
            plain = size == null ? null : FileLoader.getInstance(currentAccount).getPathToAttach(size, null, true, true);
        } else {
            plain = null;
        }
        if (plain == null || plain.getPath().isEmpty()) {
            return null;
        }
        return plain.getName().endsWith(".enc") ? plain : new File(plain.getAbsolutePath() + ".enc");
    }

    private boolean isUsableMediaSource(File file, boolean requireKey, TLRPC.Message message) {
        if (file == null || !file.exists() || !file.isFile() || file.length() == 0) {
            return false;
        }
        if (requireKey) {
            long expectedSize = expectedMediaSize(message);
            if (expectedSize > 0 && file.length() < expectedSize) {
                return false;
            }
        }
        if (!requireKey || !file.getName().endsWith(".enc")) {
            return true;
        }
        long keyLength = encryptionKeyFor(file, message).length();
        return keyLength > 0 && keyLength % 48 == 0;
    }

    private long expectedMediaSize(TLRPC.Message message) {
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media == null) {
            return 0;
        }
        if (media.document != null) {
            return media.document.size;
        }
        if (media.photo != null) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
            return size == null ? 0 : size.size;
        }
        return 0;
    }

    private File encryptionKeyFor(File encrypted, TLRPC.Message message) {
        File direct = new File(FileLoader.getInternalCacheDir(), encrypted.getName() + ".key");
        if (direct.length() > 0 && direct.length() % 48 == 0) {
            return direct;
        }
        File original = getOriginalEncryptedPath(message);
        return original == null ? direct : new File(FileLoader.getInternalCacheDir(), original.getName() + ".key");
    }

    private void deleteReplacedArchive(String path, File replacement) {
        if (path == null || path.isEmpty()) {
            return;
        }
        File file = new File(path);
        File internalDirectory = new File(ApplicationLoader.applicationContext.getFilesDir(), "privacy_archive/account_" + currentAccount);
        File externalRoot = ApplicationLoader.applicationContext.getExternalFilesDir(null);
        File externalDirectory = externalRoot == null ? null : new File(externalRoot, "privacy_archive/account_" + currentAccount);
        boolean managed = internalDirectory.equals(file.getParentFile()) || externalDirectory != null && externalDirectory.equals(file.getParentFile());
        if (managed && !file.equals(replacement) && file.exists() && !file.delete()) {
            FileLog.d("Unable to delete replaced local archive file " + file);
        }
    }

    private ArrayList<String> collectMediaPaths(long before) {
        ArrayList<String> result = new ArrayList<>();
        Cursor cursor = null;
        try {
            String selection = before > 0 ? "deleted_at < ? AND media_path IS NOT NULL" : "media_path IS NOT NULL";
            String[] args = before > 0 ? new String[]{String.valueOf(before)} : null;
            cursor = getReadableDatabase().query("archived_messages", new String[]{"media_path"}, selection, args, null, null, null);
            while (cursor.moveToNext()) {
                result.add(cursor.getString(0));
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    private void deleteFiles(ArrayList<String> paths) {
        for (int i = 0; i < paths.size(); i++) {
            try {
                File file = new File(paths.get(i));
                if (file.exists() && !file.delete()) {
                    FileLog.d("Unable to delete local archive file " + file);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private static void copyFile(InputStream input, File target) throws Exception {
        try (FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private byte[] serializeMessage(TLRPC.Message message) {
        SerializedData data = new SerializedData(message.getObjectSize() + 1024);
        message.serializeToStream(data);
        byte[] result = data.toByteArray();
        data.cleanup();
        return result;
    }

    private TLRPC.Message deserializeMessage(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return null;
        }
        SerializedData data = new SerializedData(bytes);
        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
        if (message != null) {
            message.readAttachPath(data, UserConfig.getInstance(currentAccount).clientUserId);
        }
        data.cleanup();
        return message;
    }
}
