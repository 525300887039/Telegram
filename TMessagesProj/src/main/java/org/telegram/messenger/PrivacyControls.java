package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central policy for the optional local privacy controls added by this fork.
 *
 * Server-side read receipts, pts handling and deletion state are intentionally
 * left untouched.  These switches only affect what this client keeps and what
 * actions it exposes locally.
 */
public final class PrivacyControls {

    public enum ProtectedContentAction {
        COPY,
        SAVE,
        SEND_COPY
    }

    private static final long LOCAL_DELETE_TTL_MS = 60_000L;
    private static final ConcurrentHashMap<String, Long> localDeletes = new ConcurrentHashMap<>();

    private PrivacyControls() {
    }

    public static void registerLocalDeletion(int account, long dialogId, ArrayList<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        cleanupLocalDeletes();
        final long expiresAt = System.currentTimeMillis() + LOCAL_DELETE_TTL_MS;
        for (int i = 0; i < messageIds.size(); i++) {
            localDeletes.put(localDeleteKey(account, dialogId, messageIds.get(i)), expiresAt);
        }
    }

    public static boolean isLocalDeletion(int account, long dialogId, int messageId) {
        Long expiresAt = localDeletes.get(localDeleteKey(account, dialogId, messageId));
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt < System.currentTimeMillis()) {
            localDeletes.remove(localDeleteKey(account, dialogId, messageId));
            return false;
        }
        return true;
    }

    public static boolean shouldKeepDeletedMessage(int account, long dialogId, int messageId, int mode) {
        return SharedConfig.antiDeleteEnabled
                && mode == 0
                && isSupportedDialog(dialogId)
                && !isLocalDeletion(account, dialogId, messageId);
    }

    public static boolean canRepeatViewOnce(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        return canRepeatViewOnce(messageObject.getDialogId(), messageObject.messageOwner);
    }

    public static boolean canRepeatViewOnce(long dialogId, TLRPC.Message message) {
        if (!SharedConfig.repeatViewOnceEnabled || message == null) {
            return false;
        }
        return !(message instanceof TLRPC.TL_message_secret)
                && isSupportedDialog(dialogId)
                && !message.out
                && message.media != null
                && message.media.ttl_seconds == 0x7FFFFFFF
                && !isPaidMedia(message);
    }

    public static boolean canIgnoreNoForwards(ProtectedContentAction action, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || !isSupportedMessage(messageObject)) {
            return false;
        }
        if (action == ProtectedContentAction.SEND_COPY) {
            return SharedConfig.protectedContentSendCopyEnabled;
        }
        return SharedConfig.protectedContentCopySaveEnabled;
    }

    public static boolean canIgnoreNoForwards(ProtectedContentAction action, int account, long dialogId) {
        if (!isSupportedDialog(dialogId) || dialogId == UserObject.VERIFY) {
            return false;
        }
        if (action == ProtectedContentAction.SEND_COPY) {
            return SharedConfig.protectedContentSendCopyEnabled;
        }
        return SharedConfig.protectedContentCopySaveEnabled;
    }

    public static boolean isProtected(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        return messageObject.messageOwner.noforwards
                || MessagesController.getInstance(messageObject.currentAccount).isPeerNoForwards(messageObject.getDialogId());
    }

    public static boolean isSupportedMessage(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        return isSupportedDialog(messageObject.getDialogId())
                && !(messageObject.messageOwner instanceof TLRPC.TL_message_secret)
                && messageObject.getDialogId() != UserObject.VERIFY
                && !isPaidMedia(messageObject.messageOwner)
                && !messageObject.needDrawBluredPreview();
    }

    public static boolean isSupportedDialog(long dialogId) {
        return dialogId != 0 && !DialogObject.isEncryptedDialog(dialogId);
    }

    public static boolean isPaidMedia(TLRPC.Message message) {
        return message != null && MessageObject.getMedia(message) instanceof TLRPC.TL_messageMediaPaidMedia;
    }

    private static String localDeleteKey(int account, long dialogId, int messageId) {
        return account + ":" + dialogId + ":" + messageId;
    }

    private static void cleanupLocalDeletes() {
        final long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> iterator = localDeletes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (entry.getValue() < now) {
                localDeletes.remove(entry.getKey(), entry.getValue());
            }
        }
    }
}
