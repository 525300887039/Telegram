/*
 * This is the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChannelReactionRankController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextSettingsCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ReactionRankFilterBottomSheet extends BottomSheet {

    public interface Delegate {
        void onApply(ChannelReactionRankController.RangeSpec range,
                     Set<ChannelReactionRankController.ReactionKey> selectedReactions,
                     String keyword);
    }

    private final ArrayList<RadioCell> rangeCells = new ArrayList<>();
    private final ArrayList<ChannelReactionRankController.RangeSpec> rangeOptions = new ArrayList<>();
    private final ArrayList<TextCheckCell> reactionCells = new ArrayList<>();
    private final ArrayList<ChannelReactionRankController.ReactionKey> reactionKeys = new ArrayList<>();
    private final HashSet<ChannelReactionRankController.ReactionKey> selectedReactions = new HashSet<>();
    private final EditTextSettingsCell customDaysCell;
    private final EditTextSettingsCell keywordCell;
    private final TextCheckCell allReactionsCell;
    private final TextView unreadRangeHint;
    private ChannelReactionRankController.RangeSpec selectedRange;

    public ReactionRankFilterBottomSheet(
            Context context,
            ChannelReactionRankController.RangeSpec currentRange,
            List<ChannelReactionRankController.ReactionOption> availableReactions,
            Set<ChannelReactionRankController.ReactionKey> currentReactions,
            String currentKeyword,
            Theme.ResourcesProvider resourcesProvider,
            Delegate delegate) {
        super(context, true, resourcesProvider);

        selectedRange = currentRange == null ? ChannelReactionRankController.RangeSpec.loaded() : currentRange;
        if (currentReactions != null) {
            selectedReactions.addAll(currentReactions);
        }

        setApplyTopPadding(false);
        setApplyBottomPadding(false);
        fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.ReactionRankFilterTitle));
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        title.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(12), AndroidUtilities.dp(21), 0);
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));

        content.addView(createSectionHeader(context, LocaleController.getString(R.string.ReactionRankMessageRange), resourcesProvider));

        int customDays = selectedRange.type == ChannelReactionRankController.RangeType.CUSTOM_DAYS ? selectedRange.days : 7;
        addRangeOption(context, content, ChannelReactionRankController.RangeSpec.loaded(), R.string.ReactionRankRangeLoaded, resourcesProvider);
        addRangeOption(context, content, ChannelReactionRankController.RangeSpec.unread(), R.string.ReactionRankRangeUnread, resourcesProvider);
        addRangeOption(context, content, ChannelReactionRankController.RangeSpec.today(), R.string.ReactionRankRangeToday, resourcesProvider);
        addRangeOption(context, content, ChannelReactionRankController.RangeSpec.last7Days(), R.string.ReactionRankRange7Days, resourcesProvider);
        addRangeOption(context, content, ChannelReactionRankController.RangeSpec.last30Days(), R.string.ReactionRankRange30Days, resourcesProvider);
        addRangeOption(context, content, ChannelReactionRankController.RangeSpec.last60Days(), R.string.ReactionRankRange60Days, resourcesProvider);
        addRangeOption(context, content, ChannelReactionRankController.RangeSpec.customDays(customDays), R.string.ReactionRankRangeCustom, resourcesProvider);

        customDaysCell = new EditTextSettingsCell(context);
        customDaysCell.getTextView().setInputType(InputType.TYPE_CLASS_NUMBER);
        customDaysCell.setTextAndHint(String.valueOf(customDays), LocaleController.getString(R.string.ReactionRankCustomDaysHint), false);
        content.addView(customDaysCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView rangeHint = createHint(context, LocaleController.getString(R.string.ReactionRankLoadedRangeHint), resourcesProvider);
        content.addView(rangeHint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        unreadRangeHint = createHint(context, LocaleController.getString(R.string.ReactionRankUnreadRangeHint), resourcesProvider);
        content.addView(unreadRangeHint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        content.addView(createSectionHeader(context, LocaleController.getString(R.string.ReactionRankReactionFilter), resourcesProvider));

        allReactionsCell = new TextCheckCell(context, 21, false, resourcesProvider);
        allReactionsCell.setTextAndCheck(LocaleController.getString(R.string.ReactionRankAllReactions), selectedReactions.isEmpty(), availableReactions != null && !availableReactions.isEmpty());
        allReactionsCell.setOnClickListener(view -> {
            selectedReactions.clear();
            updateReactionCells();
        });
        content.addView(allReactionsCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (availableReactions != null) {
            for (int i = 0; i < availableReactions.size(); i++) {
                ChannelReactionRankController.ReactionOption option = availableReactions.get(i);
                TextCheckCell cell = new TextCheckCell(context, 21, false, resourcesProvider);
                cell.setTextAndCheck(createReactionLabel(option), selectedReactions.contains(option.key), i != availableReactions.size() - 1);
                cell.setOnClickListener(view -> {
                    if (selectedReactions.isEmpty()) {
                        selectedReactions.add(option.key);
                    } else if (!selectedReactions.remove(option.key)) {
                        selectedReactions.add(option.key);
                    }
                    updateReactionCells();
                });
                NotificationCenter.listenEmojiLoading(cell);
                reactionCells.add(cell);
                reactionKeys.add(option.key);
                content.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
        }

        if (availableReactions == null || availableReactions.isEmpty()) {
            content.addView(createHint(context, LocaleController.getString(R.string.ReactionRankNoAvailableReactions), resourcesProvider),
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        content.addView(createSectionHeader(context, LocaleController.getString(R.string.ReactionRankKeyword), resourcesProvider));

        keywordCell = new EditTextSettingsCell(context);
        keywordCell.getTextView().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        keywordCell.setTextAndHint(currentKeyword == null ? "" : currentKeyword, LocaleController.getString(R.string.ReactionRankKeywordHint), false);
        content.addView(keywordCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView applyButton = new TextView(context);
        applyButton.setText(LocaleController.getString(R.string.ReactionRankApply));
        applyButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider));
        applyButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        applyButton.setTypeface(AndroidUtilities.bold());
        applyButton.setGravity(Gravity.CENTER);
        applyButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(8),
                Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider),
                Theme.getColor(Theme.key_featuredStickers_addButtonPressed, resourcesProvider)));
        applyButton.setOnClickListener(view -> {
            ChannelReactionRankController.RangeSpec range = selectedRange;
            if (selectedRange.type == ChannelReactionRankController.RangeType.CUSTOM_DAYS) {
                String value = customDaysCell.getText().trim();
                int days;
                try {
                    days = Integer.parseInt(value);
                } catch (Exception ignore) {
                    days = 0;
                }
                if (days <= 0) {
                    customDaysCell.getTextView().setError(LocaleController.getString(R.string.ReactionRankCustomDaysError));
                    customDaysCell.getTextView().requestFocus();
                    return;
                }
                range = ChannelReactionRankController.RangeSpec.customDays(days);
            }
            AndroidUtilities.hideKeyboard(keywordCell.getTextView());
            delegate.onApply(range, new HashSet<>(selectedReactions), keywordCell.getText().trim());
            dismiss();
        });
        content.addView(applyButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.NO_GRAVITY, 16, 16, 16, 12));

        NestedScrollView scrollView = new NestedScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.addView(content);
        setCustomView(scrollView);

        updateRangeCells();
        updateReactionCells();
    }

    private void addRangeOption(Context context, LinearLayout content, ChannelReactionRankController.RangeSpec range, int titleRes, Theme.ResourcesProvider resourcesProvider) {
        RadioCell cell = new RadioCell(context, false, 21, resourcesProvider);
        cell.setText(LocaleController.getString(titleRes), selectedRange.type == range.type, true);
        cell.setOnClickListener(view -> {
            selectedRange = range;
            updateRangeCells();
        });
        rangeCells.add(cell);
        rangeOptions.add(range);
        content.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void updateRangeCells() {
        for (int i = 0; i < rangeCells.size(); i++) {
            rangeCells.get(i).setChecked(rangeOptions.get(i).type == selectedRange.type, true);
        }
        if (customDaysCell != null) {
            customDaysCell.setVisibility(selectedRange.type == ChannelReactionRankController.RangeType.CUSTOM_DAYS ? View.VISIBLE : View.GONE);
        }
        unreadRangeHint.setVisibility(selectedRange.type == ChannelReactionRankController.RangeType.UNREAD ? View.VISIBLE : View.GONE);
    }

    private void updateReactionCells() {
        allReactionsCell.setChecked(selectedReactions.isEmpty());
        for (int i = 0; i < reactionCells.size(); i++) {
            reactionCells.get(i).setChecked(selectedReactions.contains(reactionKeys.get(i)));
        }
    }

    private CharSequence createReactionLabel(ChannelReactionRankController.ReactionOption option) {
        CharSequence reaction;
        if (option.key.type == ChannelReactionRankController.ReactionKey.TYPE_EMOJI) {
            reaction = option.key.emoji;
        } else if (option.key.type == ChannelReactionRankController.ReactionKey.TYPE_CUSTOM_EMOJI) {
            reaction = ReactionsLayoutInBubble.VisibleReaction.fromCustomEmoji(option.key.documentId).toCharSequence(22);
        } else {
            reaction = LocaleController.getString(R.string.ReactionRankPaidReaction);
        }
        SpannableStringBuilder label = new SpannableStringBuilder(reaction == null ? "" : reaction);
        label.append("  ");
        label.append(AndroidUtilities.formatWholeNumber(option.count, 0));
        return label;
    }

    private static TextView createSectionHeader(Context context, String text, Theme.ResourcesProvider resourcesProvider) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider));
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTypeface(AndroidUtilities.bold());
        view.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        view.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(12), AndroidUtilities.dp(21), 0);
        return view;
    }

    private static TextView createHint(Context context, String text, Theme.ResourcesProvider resourcesProvider) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4, resourcesProvider));
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        view.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        view.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(8), AndroidUtilities.dp(21), AndroidUtilities.dp(12));
        return view;
    }
}
