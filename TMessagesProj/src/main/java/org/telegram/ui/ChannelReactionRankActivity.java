/*
 * This is the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChannelReactionRankController;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ReactionRankMessageCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ReactionRankFilterBottomSheet;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

public class ChannelReactionRankActivity extends BaseFragment implements
        NotificationCenter.NotificationCenterDelegate,
        ChannelReactionRankController.Delegate {

    private static final int MENU_FILTER = 1;
    private static final int MENU_REFRESH = 2;
    private static final int TOP_LIMIT = 50;

    private final long chatId;
    private final long dialogId;
    private final ArrayList<MessageObject> loadedMessages;
    private final ArrayList<ChannelReactionRankController.RankedResult> rankedResults = new ArrayList<>();
    private final HashSet<ChannelReactionRankController.ReactionKey> selectedReactions = new HashSet<>();

    private TLRPC.Chat chat;
    private ChannelReactionRankController controller;
    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private EmptyTextProgressView emptyView;
    private TextView statusView;
    private ActionBarMenuItem refreshItem;
    private String keyword = "";
    private int matchedMessages;
    private boolean lastLoadFailed;
    private final Runnable dateBoundaryRunnable = new Runnable() {
        @Override
        public void run() {
            if (controller != null) {
                controller.reloadIfDateBoundaryChanged();
            }
            scheduleDateBoundaryCheck();
        }
    };

    public ChannelReactionRankActivity(long chatId, ArrayList<MessageObject> loadedMessages) {
        this.chatId = chatId;
        this.dialogId = -chatId;
        this.loadedMessages = loadedMessages == null ? new ArrayList<>() : new ArrayList<>(loadedMessages);
    }

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) {
            return false;
        }
        chat = getMessagesController().getChat(chatId);
        if (!ChatObject.isChannelAndNotMegaGroup(chat)) {
            return false;
        }
        getNotificationCenter().addObserver(this, NotificationCenter.didUpdateReactions);
        getNotificationCenter().addObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().addObserver(this, NotificationCenter.replaceMessagesObjects);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.didUpdateReactions);
        getNotificationCenter().removeObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().removeObserver(this, NotificationCenter.replaceMessagesObjects);
        if (controller != null) {
            controller.destroy();
            controller = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (controller != null) {
            controller.reloadIfDateBoundaryChanged();
        }
        scheduleDateBoundaryCheck();
    }

    @Override
    public void onPause() {
        AndroidUtilities.cancelRunOnUIThread(dateBoundaryRunnable);
        super.onPause();
    }

    @Override
    protected View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.ReactionRankTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_FILTER) {
                    showFilters();
                } else if (id == MENU_REFRESH && controller != null) {
                    lastLoadFailed = false;
                    controller.refresh();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem filterItem = menu.addItem(MENU_FILTER, R.drawable.menu_tag_filter);
        filterItem.setContentDescription(LocaleController.getString(R.string.ReactionRankFilterTitle));
        refreshItem = menu.addItem(MENU_REFRESH, R.drawable.msg_retry);
        refreshItem.setContentDescription(LocaleController.getString(R.string.ReactionRankRefresh));

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
        fragmentView = root;

        statusView = new TextView(context);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, getResourceProvider()));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        statusView.setMaxLines(2);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        root.addView(statusView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 58, Gravity.TOP));

        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider, getResourceProvider()));
        root.addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1, Gravity.TOP, 0, 58, 0, 0));

        FrameLayout listContainer = new FrameLayout(context);
        root.addView(listContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 59, 0, 0));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < rankedResults.size()) {
                openMessage(rankedResults.get(position));
            }
        });
        listContainer.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new EmptyTextProgressView(context, null, getResourceProvider());
        emptyView.setText(LocaleController.getString(R.string.ReactionRankNoResults));
        listContainer.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setEmptyView(emptyView);

        controller = new ChannelReactionRankController(
                currentAccount,
                dialogId,
                classGuid,
                loadedMessages,
                this);
        controller.load(ChannelReactionRankController.RangeSpec.loaded(), false);
        return fragmentView;
    }

    private void showFilters() {
        if (getContext() == null || controller == null) {
            return;
        }
        ReactionRankFilterBottomSheet sheet = new ReactionRankFilterBottomSheet(
                getContext(),
                controller.getCurrentRange(),
                controller.getAvailableReactions(),
                selectedReactions,
                keyword,
                getResourceProvider(),
                (range, reactions, newKeyword) -> applyFilters(range, reactions, newKeyword));
        showDialog(sheet);
    }

    private void scheduleDateBoundaryCheck() {
        AndroidUtilities.cancelRunOnUIThread(dateBoundaryRunnable);
        Calendar nextMidnight = Calendar.getInstance();
        nextMidnight.set(Calendar.HOUR_OF_DAY, 0);
        nextMidnight.set(Calendar.MINUTE, 0);
        nextMidnight.set(Calendar.SECOND, 0);
        nextMidnight.set(Calendar.MILLISECOND, 0);
        nextMidnight.add(Calendar.DAY_OF_YEAR, 1);
        long delay = Math.max(1000L, nextMidnight.getTimeInMillis() - System.currentTimeMillis() + 1000L);
        AndroidUtilities.runOnUIThread(dateBoundaryRunnable, delay);
    }

    private void applyFilters(ChannelReactionRankController.RangeSpec range,
                              Set<ChannelReactionRankController.ReactionKey> reactions,
                              String newKeyword) {
        if (controller == null) {
            return;
        }
        boolean rangeChanged = !range.equals(controller.getCurrentRange());
        selectedReactions.clear();
        if (reactions != null) {
            selectedReactions.addAll(reactions);
        }
        keyword = newKeyword == null ? "" : newKeyword;
        lastLoadFailed = false;
        if (rangeChanged) {
            controller.load(range, false);
        } else {
            rebuildResults();
        }
    }

    private void rebuildResults() {
        if (controller == null || listAdapter == null) {
            return;
        }
        ArrayList<ChannelReactionRankController.RankedResult> allResults = controller.getRankedMessages(selectedReactions, keyword, 0);
        matchedMessages = allResults.size();
        rankedResults.clear();
        rankedResults.addAll(allResults.subList(0, Math.min(TOP_LIMIT, allResults.size())));
        listAdapter.notifyDataSetChanged();

        boolean loading = controller.isLoading();
        String range = getRangeLabel(controller.getCurrentRange());
        if (loading) {
            statusView.setText(LocaleController.formatString(
                    R.string.ReactionRankStatusLoading,
                    range,
                    controller.getCheckedMessages(),
                    matchedMessages));
        } else {
            statusView.setText(LocaleController.formatString(
                    R.string.ReactionRankStatus,
                    range,
                    controller.getCheckedMessages(),
                    matchedMessages,
                    rankedResults.size()));
        }
        refreshItem.setVisibility(controller.getCurrentRange().isLoadedOnly() ? View.GONE : View.VISIBLE);

        if (rankedResults.isEmpty()) {
            if (loading) {
                emptyView.showProgress();
            } else {
                emptyView.setText(LocaleController.getString(lastLoadFailed ? R.string.ReactionRankLoadFailed : R.string.ReactionRankNoResults));
                emptyView.showTextView();
            }
        } else {
            emptyView.showTextView();
        }
    }

    private String getRangeLabel(ChannelReactionRankController.RangeSpec range) {
        switch (range.type) {
            case TODAY:
                return LocaleController.getString(R.string.ReactionRankRangeToday);
            case UNREAD:
                return LocaleController.getString(R.string.ReactionRankRangeUnread);
            case LAST_7_DAYS:
                return LocaleController.getString(R.string.ReactionRankRange7Days);
            case LAST_30_DAYS:
                return LocaleController.getString(R.string.ReactionRankRange30Days);
            case LAST_60_DAYS:
                return LocaleController.getString(R.string.ReactionRankRange60Days);
            case CUSTOM_DAYS:
                return LocaleController.formatPluralString("ReactionRankDays", range.days);
            case LOADED:
            default:
                return LocaleController.getString(R.string.ReactionRankRangeLoaded);
        }
    }

    private void openMessage(ChannelReactionRankController.RankedResult result) {
        Bundle args = new Bundle();
        args.putLong("chat_id", chatId);
        args.putInt("message_id", result.message.messageId);
        args.putBoolean("need_remove_previous_same_chat_activity", false);
        if (getMessagesController().checkCanOpenChat(args, this)) {
            presentFragment(new ChatActivity(args));
        }
    }

    @Override
    public void onDataChanged(int checkedMessages, boolean loading) {
        if (loading) {
            lastLoadFailed = false;
        }
        rebuildResults();
    }

    @Override
    public void onLoadError(TLRPC.TL_error error) {
        lastLoadFailed = true;
        rebuildResults();
        if (fragmentView != null) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.ReactionRankLoadFailed)).show();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void didReceivedNotification(int id, int account, Object... args) {
        if (controller == null || account != currentAccount || args.length == 0) {
            return;
        }
        if (id == NotificationCenter.didUpdateReactions) {
            long did = (Long) args[0];
            if (did == dialogId && args.length > 2 && args[2] instanceof TLRPC.TL_messageReactions) {
                controller.updateReactions((Integer) args[1], (TLRPC.TL_messageReactions) args[2]);
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            long did = (Long) args[0];
            boolean scheduled = args.length > 2 && (Boolean) args[2];
            int mode = args.length > 3 ? (Integer) args[3] : ChatActivity.MODE_DEFAULT;
            if (did == dialogId && !scheduled && mode == ChatActivity.MODE_DEFAULT && args.length > 1) {
                controller.addNewMessages((ArrayList<MessageObject>) args[1]);
            }
        } else if (id == NotificationCenter.replaceMessagesObjects) {
            long did = (Long) args[0];
            if (did == dialogId && args.length > 1) {
                controller.replaceMessages((ArrayList<MessageObject>) args[1]);
            }
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        private ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return rankedResults.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new RecyclerListView.Holder(new ReactionRankMessageCell(context, chat, getResourceProvider()));
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((ReactionRankMessageCell) holder.itemView).setData(rankedResults.get(position), position + 1, position == rankedResults.size() - 1);
        }
    }
}
