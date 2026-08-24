/*
 * This is the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.messenger;

import android.text.TextUtils;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;

/**
 * Loads channel history on demand and builds a client-side message ranking from reactions.
 * The default {@link RangeType#LOADED} range never performs a network request.
 */
public class ChannelReactionRankController {

    private static final int PAGE_SIZE = 100;
    private static final int CACHE_LIMIT = 8;
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    public enum RangeType {
        LOADED,
        TODAY,
        LAST_7_DAYS,
        LAST_30_DAYS,
        LAST_60_DAYS,
        CUSTOM_DAYS
    }

    public static final class RangeSpec {
        public final RangeType type;
        public final int days;

        private RangeSpec(RangeType type, int days) {
            this.type = type;
            this.days = days;
        }

        public static RangeSpec loaded() {
            return new RangeSpec(RangeType.LOADED, 0);
        }

        public static RangeSpec today() {
            return new RangeSpec(RangeType.TODAY, 1);
        }

        public static RangeSpec last7Days() {
            return new RangeSpec(RangeType.LAST_7_DAYS, 7);
        }

        public static RangeSpec last30Days() {
            return new RangeSpec(RangeType.LAST_30_DAYS, 30);
        }

        public static RangeSpec last60Days() {
            return new RangeSpec(RangeType.LAST_60_DAYS, 60);
        }

        public static RangeSpec customDays(int days) {
            return new RangeSpec(RangeType.CUSTOM_DAYS, Math.max(1, days));
        }

        public boolean isLoadedOnly() {
            return type == RangeType.LOADED;
        }

        public int getMinDateSeconds() {
            if (isLoadedOnly()) {
                return 0;
            }
            return calculateStartDateSeconds(days, System.currentTimeMillis(), TimeZone.getDefault());
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof RangeSpec)) {
                return false;
            }
            RangeSpec that = (RangeSpec) object;
            return days == that.days && type == that.type;
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, days);
        }
    }

    public static final class ReactionKey {
        public static final int TYPE_EMOJI = 0;
        public static final int TYPE_CUSTOM_EMOJI = 1;
        public static final int TYPE_PAID = 2;

        public final int type;
        public final String emoji;
        public final long documentId;

        private ReactionKey(int type, String emoji, long documentId) {
            this.type = type;
            this.emoji = emoji;
            this.documentId = documentId;
        }

        public static ReactionKey fromReaction(TLRPC.Reaction reaction) {
            if (reaction instanceof TLRPC.TL_reactionEmoji) {
                return new ReactionKey(TYPE_EMOJI, ((TLRPC.TL_reactionEmoji) reaction).emoticon, 0);
            } else if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                return new ReactionKey(TYPE_CUSTOM_EMOJI, null, ((TLRPC.TL_reactionCustomEmoji) reaction).document_id);
            } else if (reaction instanceof TLRPC.TL_reactionPaid) {
                return new ReactionKey(TYPE_PAID, null, 0);
            }
            return null;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof ReactionKey)) {
                return false;
            }
            ReactionKey that = (ReactionKey) object;
            return type == that.type && documentId == that.documentId && TextUtils.equals(emoji, that.emoji);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, emoji, documentId);
        }
    }

    public static final class ReactionOption {
        public final ReactionKey key;
        public final int count;

        private ReactionOption(ReactionKey key, int count) {
            this.key = key;
            this.count = count;
        }
    }

    public static final class RankedMessage {
        public MessageObject messageObject;
        public final int messageId;
        public final int date;
        public final int views;
        public final int forwards;
        public final String text;
        public final int totalReactions;
        private final HashMap<ReactionKey, Integer> reactionCounts;

        private RankedMessage(MessageObject messageObject) {
            this.messageObject = messageObject;
            TLRPC.Message message = messageObject.messageOwner;
            messageId = message.id;
            date = message.date;
            views = message.views;
            forwards = message.forwards;
            text = message.message == null ? "" : message.message;
            reactionCounts = extractReactionCounts(message.reactions);
            int total = 0;
            for (Integer value : reactionCounts.values()) {
                total += value;
            }
            totalReactions = total;
        }

        public int getCount(Set<ReactionKey> selectedReactions) {
            if (selectedReactions == null || selectedReactions.isEmpty()) {
                return totalReactions;
            }
            int count = 0;
            for (ReactionKey reaction : selectedReactions) {
                Integer value = reactionCounts.get(reaction);
                if (value != null) {
                    count += value;
                }
            }
            return count;
        }

        public Map<ReactionKey, Integer> getReactionCounts() {
            return Collections.unmodifiableMap(reactionCounts);
        }
    }

    public static final class RankedResult {
        public final RankedMessage message;
        public final int selectedReactionCount;

        private RankedResult(RankedMessage message, int selectedReactionCount) {
            this.message = message;
            this.selectedReactionCount = selectedReactionCount;
        }
    }

    public interface Delegate {
        void onDataChanged(int checkedMessages, boolean loading);

        void onLoadError(TLRPC.TL_error error);
    }

    private static final class CacheEntry {
        final long createdAt;
        final ArrayList<RankedMessage> messages;
        final int checkedMessages;

        CacheEntry(ArrayList<RankedMessage> messages, int checkedMessages) {
            this.createdAt = System.currentTimeMillis();
            this.messages = messages;
            this.checkedMessages = checkedMessages;
        }
    }

    private static final LinkedHashMap<String, CacheEntry> memoryCache = new LinkedHashMap<String, CacheEntry>(CACHE_LIMIT, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    private final int currentAccount;
    private final long dialogId;
    private final int classGuid;
    private final SparseArray<MessageObject> loadedSnapshotById = new SparseArray<>();
    private final SparseArray<RankedMessage> messagesById = new SparseArray<>();
    private final SparseBooleanArray checkedMessageIds = new SparseBooleanArray();

    private Delegate delegate;
    private RangeSpec currentRange = RangeSpec.loaded();
    private int activeMinDate;
    private int checkedMessages;
    private int requestId;
    private int requestGeneration;
    private boolean loading;
    private boolean destroyed;

    public ChannelReactionRankController(int currentAccount, long dialogId, int classGuid, List<MessageObject> loadedMessages, Delegate delegate) {
        this.currentAccount = currentAccount;
        this.dialogId = dialogId;
        this.classGuid = classGuid;
        this.delegate = delegate;
        if (loadedMessages != null) {
            for (MessageObject messageObject : loadedMessages) {
                putLoadedSnapshot(messageObject);
            }
        }
    }

    public RangeSpec getCurrentRange() {
        return currentRange;
    }

    public int getCheckedMessages() {
        return checkedMessages;
    }

    public boolean isLoading() {
        return loading;
    }

    public void load(RangeSpec range, boolean force) {
        if (destroyed || range == null) {
            return;
        }
        cancelCurrentRequest();
        requestGeneration++;
        currentRange = range;
        resetData();

        activeMinDate = range.getMinDateSeconds();
        seedLoadedMessages(activeMinDate);

        if (range.isLoadedOnly()) {
            loading = false;
            notifyDataChanged();
            return;
        }

        String cacheKey = getCacheKey(activeMinDate);
        if (!force) {
            CacheEntry cached = getCached(cacheKey);
            if (cached != null) {
                messagesById.clear();
                checkedMessageIds.clear();
                for (RankedMessage message : cached.messages) {
                    messagesById.put(message.messageId, message);
                    checkedMessageIds.put(message.messageId, true);
                }
                checkedMessages = cached.checkedMessages;
                seedLoadedMessages(activeMinDate);
                loading = false;
                notifyDataChanged();
                return;
            }
        }

        loading = true;
        notifyDataChanged();
        loadPage(0, activeMinDate, requestGeneration, cacheKey);
    }

    public void refresh() {
        load(currentRange, true);
    }

    /**
     * Natural-day ranges depend on the device time zone and local midnight. Re-run the
     * current range if that boundary changed while this screen was in the background.
     */
    public boolean reloadIfDateBoundaryChanged() {
        if (destroyed || currentRange.isLoadedOnly()) {
            return false;
        }
        int minDate = currentRange.getMinDateSeconds();
        if (minDate == activeMinDate) {
            return false;
        }
        load(currentRange, false);
        return true;
    }

    public ArrayList<RankedResult> getRankedMessages(Set<ReactionKey> selectedReactions, String keyword, int limit) {
        ArrayList<RankedResult> result = new ArrayList<>();
        String normalizedKeyword = TextUtils.isEmpty(keyword) ? null : keyword.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < messagesById.size(); i++) {
            RankedMessage message = messagesById.valueAt(i);
            if (normalizedKeyword != null && !message.text.toLowerCase(Locale.ROOT).contains(normalizedKeyword)) {
                continue;
            }
            int count = message.getCount(selectedReactions);
            if (count > 0) {
                result.add(new RankedResult(message, count));
            }
        }
        result.sort(RANKING_COMPARATOR);
        if (limit > 0 && result.size() > limit) {
            return new ArrayList<>(result.subList(0, limit));
        }
        return result;
    }

    public ArrayList<ReactionOption> getAvailableReactions() {
        HashMap<ReactionKey, Integer> totals = new HashMap<>();
        for (int i = 0; i < messagesById.size(); i++) {
            RankedMessage message = messagesById.valueAt(i);
            for (Map.Entry<ReactionKey, Integer> reaction : message.getReactionCounts().entrySet()) {
                Integer previous = totals.get(reaction.getKey());
                totals.put(reaction.getKey(), (previous == null ? 0 : previous) + reaction.getValue());
            }
        }
        ArrayList<ReactionOption> result = new ArrayList<>();
        for (Map.Entry<ReactionKey, Integer> entry : totals.entrySet()) {
            result.add(new ReactionOption(entry.getKey(), entry.getValue()));
        }
        result.sort((left, right) -> {
            int byCount = Integer.compare(right.count, left.count);
            if (byCount != 0) {
                return byCount;
            }
            int byType = Integer.compare(left.key.type, right.key.type);
            if (byType != 0) {
                return byType;
            }
            if (left.key.type == ReactionKey.TYPE_EMOJI) {
                return String.valueOf(left.key.emoji).compareTo(String.valueOf(right.key.emoji));
            }
            return Long.compare(left.key.documentId, right.key.documentId);
        });
        return result;
    }

    public void updateReactions(int messageId, TLRPC.TL_messageReactions reactions) {
        RankedMessage current = messagesById.get(messageId);
        MessageObject currentObject = current == null ? null : current.messageObject;
        MessageObject loadedObject = loadedSnapshotById.get(messageId);
        if (currentObject == null && loadedObject == null) {
            return;
        }
        if (currentObject != null) {
            MessageObject.updateReactions(currentObject.messageOwner, reactions);
            messagesById.put(messageId, new RankedMessage(currentObject));
        }
        if (loadedObject != null && loadedObject != currentObject) {
            MessageObject.updateReactions(loadedObject.messageOwner, reactions);
            if (isEligible(loadedObject, activeMinDate)) {
                putMessage(loadedObject, true);
            }
        }
        invalidateDialogCache();
        notifyDataChanged();
    }

    public void addNewMessages(List<MessageObject> messageObjects) {
        if (destroyed || messageObjects == null) {
            return;
        }
        boolean changed = false;
        for (MessageObject messageObject : messageObjects) {
            putLoadedSnapshot(messageObject);
            if (isEligible(messageObject, activeMinDate)) {
                changed |= putMessage(messageObject, true);
            }
        }
        if (changed) {
            invalidateDialogCache();
            notifyDataChanged();
        }
    }

    public void replaceMessages(List<MessageObject> messageObjects) {
        if (destroyed || messageObjects == null) {
            return;
        }
        boolean changed = false;
        boolean cacheChanged = false;
        for (MessageObject messageObject : messageObjects) {
            if (messageObject == null) {
                continue;
            }
            int messageId = messageObject.getId();
            boolean wasLoaded = loadedSnapshotById.indexOfKey(messageId) >= 0;
            boolean wasRanked = messagesById.indexOfKey(messageId) >= 0;
            if (wasLoaded) {
                putLoadedSnapshot(messageObject);
                cacheChanged = true;
            }
            // A replacement can introduce reactions to a message that was previously
            // absent from messagesById, so re-add every eligible message from the
            // original loaded snapshot instead of updating only existing ranked rows.
            if (wasLoaded || wasRanked) {
                if (isEligible(messageObject, activeMinDate)) {
                    changed |= putMessage(messageObject, true);
                } else if (wasRanked) {
                    messagesById.remove(messageId);
                    changed = true;
                }
            }
        }
        if (cacheChanged || changed) {
            invalidateDialogCache();
        }
        if (changed) {
            notifyDataChanged();
        }
    }

    public void destroy() {
        destroyed = true;
        delegate = null;
        requestGeneration++;
        cancelCurrentRequest();
    }

    public static int calculateStartDateSeconds(int days, long nowMillis, TimeZone timeZone) {
        Calendar calendar = Calendar.getInstance(timeZone);
        calendar.setTimeInMillis(nowMillis);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.DAY_OF_YEAR, -(Math.max(1, days) - 1));
        return (int) (calendar.getTimeInMillis() / 1000L);
    }

    private void loadPage(int offsetId, int minDate, int generation, String cacheKey) {
        if (destroyed || generation != requestGeneration) {
            return;
        }
        TLRPC.InputPeer peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        if (peer == null) {
            finishWithError(null);
            return;
        }
        TLRPC.TL_messages_getHistory request = new TLRPC.TL_messages_getHistory();
        request.peer = peer;
        request.offset_id = offsetId;
        request.offset_date = 0;
        request.add_offset = 0;
        request.limit = PAGE_SIZE;
        request.max_id = 0;
        request.min_id = 0;
        request.hash = 0;

        requestId = ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (destroyed || generation != requestGeneration) {
                return;
            }
            requestId = 0;
            if (!(response instanceof TLRPC.messages_Messages)) {
                finishWithError(error);
                return;
            }

            TLRPC.messages_Messages messages = (TLRPC.messages_Messages) response;
            MessagesController.getInstance(currentAccount).putUsers(messages.users, false);
            MessagesController.getInstance(currentAccount).putChats(messages.chats, false);

            int nextOffsetId = 0;
            boolean reachedBoundary = false;
            for (TLRPC.Message message : messages.messages) {
                if (message == null || message instanceof TLRPC.TL_messageEmpty || message.id <= 0 || message.date <= 0) {
                    continue;
                }
                if (nextOffsetId == 0 || message.id < nextOffsetId) {
                    nextOffsetId = message.id;
                }
                if (message.date < minDate) {
                    reachedBoundary = true;
                    continue;
                }
                message.dialog_id = dialogId;
                MessageObject messageObject = loadedSnapshotById.get(message.id);
                if (messageObject == null) {
                    messageObject = new MessageObject(currentAccount, message, false, true);
                }
                if (isEligible(messageObject, minDate)) {
                    putMessage(messageObject, true);
                }
            }

            notifyDataChanged();
            if (messages.messages.isEmpty() || reachedBoundary || messages.messages.size() < PAGE_SIZE || nextOffsetId <= 0 || nextOffsetId == offsetId) {
                loading = false;
                putCached(cacheKey);
                notifyDataChanged();
            } else {
                loadPage(nextOffsetId, minDate, generation, cacheKey);
            }
        }));
        ConnectionsManager.getInstance(currentAccount).bindRequestToGuid(requestId, classGuid);
    }

    private void seedLoadedMessages(int minDate) {
        for (int i = 0; i < loadedSnapshotById.size(); i++) {
            MessageObject messageObject = loadedSnapshotById.valueAt(i);
            if (isEligible(messageObject, minDate)) {
                putMessage(messageObject, true);
            }
        }
    }

    private void putLoadedSnapshot(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.getId() <= 0 || messageObject.getDialogId() != dialogId) {
            return;
        }
        loadedSnapshotById.put(messageObject.getId(), messageObject);
    }

    private boolean putMessage(MessageObject messageObject, boolean countAsChecked) {
        int messageId = messageObject.getId();
        boolean changed = messagesById.indexOfKey(messageId) < 0 || messagesById.get(messageId).messageObject != messageObject;
        messagesById.put(messageId, new RankedMessage(messageObject));
        if (countAsChecked && !checkedMessageIds.get(messageId)) {
            checkedMessageIds.put(messageId, true);
            checkedMessages++;
            changed = true;
        }
        return changed;
    }

    private boolean isEligible(MessageObject messageObject, int minDate) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.isDateObject || messageObject.getId() <= 0) {
            return false;
        }
        if (messageObject.getDialogId() != dialogId || messageObject.messageOwner.action != null) {
            return false;
        }
        return minDate == 0 || messageObject.messageOwner.date >= minDate;
    }

    private void resetData() {
        messagesById.clear();
        checkedMessageIds.clear();
        checkedMessages = 0;
        loading = false;
    }

    private void cancelCurrentRequest() {
        if (requestId != 0) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(requestId, true);
            requestId = 0;
        }
    }

    private void finishWithError(TLRPC.TL_error error) {
        loading = false;
        notifyDataChanged();
        if (delegate != null) {
            delegate.onLoadError(error);
        }
    }

    private void notifyDataChanged() {
        if (delegate != null) {
            delegate.onDataChanged(checkedMessages, loading);
        }
    }

    private String getCacheKey(int minDate) {
        return currentAccount + ":" + dialogId + ":" + minDate;
    }

    private CacheEntry getCached(String key) {
        synchronized (memoryCache) {
            CacheEntry entry = memoryCache.get(key);
            if (entry != null && System.currentTimeMillis() - entry.createdAt <= CACHE_TTL_MS) {
                return entry;
            }
            if (entry != null) {
                memoryCache.remove(key);
            }
            return null;
        }
    }

    private void putCached(String key) {
        ArrayList<RankedMessage> messages = new ArrayList<>();
        for (int i = 0; i < messagesById.size(); i++) {
            messages.add(messagesById.valueAt(i));
        }
        synchronized (memoryCache) {
            memoryCache.put(key, new CacheEntry(messages, checkedMessages));
        }
    }

    private void invalidateDialogCache() {
        String prefix = currentAccount + ":" + dialogId + ":";
        synchronized (memoryCache) {
            ArrayList<String> keys = new ArrayList<>(memoryCache.keySet());
            for (String key : keys) {
                if (key.startsWith(prefix)) {
                    memoryCache.remove(key);
                }
            }
        }
    }

    private static HashMap<ReactionKey, Integer> extractReactionCounts(TLRPC.TL_messageReactions reactions) {
        HashMap<ReactionKey, Integer> result = new HashMap<>();
        if (reactions == null || reactions.results == null) {
            return result;
        }
        for (TLRPC.ReactionCount count : reactions.results) {
            ReactionKey key = ReactionKey.fromReaction(count.reaction);
            if (key != null && count.count > 0) {
                Integer previous = result.get(key);
                result.put(key, (previous == null ? 0 : previous) + count.count);
            }
        }
        return result;
    }

    private static final Comparator<RankedResult> RANKING_COMPARATOR = (left, right) -> {
        int result = Integer.compare(right.selectedReactionCount, left.selectedReactionCount);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(right.message.totalReactions, left.message.totalReactions);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(right.message.forwards, left.message.forwards);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(right.message.views, left.message.views);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(right.message.date, left.message.date);
        if (result != 0) {
            return result;
        }
        return Integer.compare(right.message.messageId, left.message.messageId);
    };
}
