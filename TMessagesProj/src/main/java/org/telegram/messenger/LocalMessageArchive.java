package org.telegram.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
                getWritableDatabase().insertWithOnConflict("archived_messages", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            if (SharedConfig.keepDeletedMediaEnabled || reason == REASON_VIEW_ONCE) {
                archiveMediaAsync(dialogId, message);
            }
            cleanupExpiredAsync();
        } catch (Exception e) {
            FileLog.e(e);
        }
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
            ArrayList<String> paths = collectMediaPaths(0);
            synchronized (databaseLock) {
                getWritableDatabase().delete("archived_messages", null, null);
            }
            deleteFiles(paths);
        });
    }

    public void cleanupExpiredAsync() {
        Utilities.globalQueue.postRunnable(() -> {
            int retentionDays = SharedConfig.localArchiveRetentionDays;
            if (retentionDays <= 0) {
                return;
            }
            long threshold = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L;
            ArrayList<String> paths = collectMediaPaths(threshold);
            synchronized (databaseLock) {
                getWritableDatabase().delete("archived_messages", "deleted_at < ?", new String[]{String.valueOf(threshold)});
            }
            deleteFiles(paths);
        });
    }

    private void archiveMediaAsync(long dialogId, TLRPC.Message message) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                File source = resolveMediaFile(message);
                if (source == null || !source.exists() || !source.isFile()) {
                    return;
                }
                File directory = new File(ApplicationLoader.applicationContext.getFilesDir(), "privacy_archive/account_" + currentAccount);
                if (!directory.exists() && !directory.mkdirs()) {
                    return;
                }
                String suffix = source.getName().contains(".") ? source.getName().substring(source.getName().lastIndexOf('.')) : ".bin";
                File target = new File(directory, dialogId + "_" + message.id + suffix);
                copyFile(source, target);
                ContentValues values = new ContentValues();
                values.put("media_path", target.getAbsolutePath());
                synchronized (databaseLock) {
                    getWritableDatabase().update("archived_messages", values, "dialog_id = ? AND mid = ?", new String[]{String.valueOf(dialogId), String.valueOf(message.id)});
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private File resolveMediaFile(TLRPC.Message message) {
        if (message.attachPath != null && !message.attachPath.isEmpty()) {
            File file = new File(message.attachPath);
            if (file.exists()) {
                return file;
            }
        }
        File file = FileLoader.getInstance(currentAccount).getPathToMessage(message);
        if (file.exists()) {
            return file;
        }
        File encrypted = new File(file.getAbsolutePath() + ".enc");
        return encrypted.exists() ? encrypted : null;
    }

    private ArrayList<String> collectMediaPaths(long before) {
        ArrayList<String> result = new ArrayList<>();
        Cursor cursor = null;
        try {
            synchronized (databaseLock) {
                String selection = before > 0 ? "deleted_at < ? AND media_path IS NOT NULL" : "media_path IS NOT NULL";
                String[] args = before > 0 ? new String[]{String.valueOf(before)} : null;
                cursor = getReadableDatabase().query("archived_messages", new String[]{"media_path"}, selection, args, null, null, null);
                while (cursor.moveToNext()) {
                    result.add(cursor.getString(0));
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

    private static void copyFile(File source, File target) throws Exception {
        try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
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
