/*
 * This is the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.URLSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.ChannelReactionRankController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Date;

public class ReactionRankMessageCell extends FrameLayout {

    private final TextView rankView;
    private final BackupImageView imageView;
    private final TextView messageView;
    private final TextView reactionsView;
    private final TextView metadataView;
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final TLRPC.Chat chat;
    private final Theme.ResourcesProvider resourcesProvider;
    private boolean needDivider;

    public ReactionRankMessageCell(Context context, TLRPC.Chat chat, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.chat = chat;
        this.resourcesProvider = resourcesProvider;

        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
        setWillNotDraw(false);

        rankView = new TextView(context);
        rankView.setGravity(Gravity.CENTER);
        rankView.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider));
        rankView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        rankView.setTypeface(AndroidUtilities.bold());
        rankView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(18), Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)));
        addView(rankView, LayoutHelper.createFrame(36, 36, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL,
                LocaleController.isRTL ? 0 : 10, 0, LocaleController.isRTL ? 10 : 0, 0));

        imageView = new BackupImageView(context);
        imageView.setRoundRadius(AndroidUtilities.dp(9));
        addView(imageView, LayoutHelper.createFrame(52, 52, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL,
                LocaleController.isRTL ? 0 : 56, 0, LocaleController.isRTL ? 56 : 0, 0));

        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setGravity(Gravity.CENTER_VERTICAL);

        messageView = new TextView(context);
        messageView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        messageView.setMaxLines(2);
        messageView.setEllipsize(TextUtils.TruncateAt.END);
        messageView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        NotificationCenter.listenEmojiLoading(messageView);
        contentLayout.addView(messageView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        reactionsView = new TextView(context);
        reactionsView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
        reactionsView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        reactionsView.setSingleLine(true);
        reactionsView.setEllipsize(TextUtils.TruncateAt.END);
        reactionsView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        contentLayout.addView(reactionsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        metadataView = new TextView(context);
        metadataView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourcesProvider));
        metadataView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        metadataView.setSingleLine(true);
        metadataView.setEllipsize(TextUtils.TruncateAt.END);
        metadataView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        contentLayout.addView(metadataView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        addView(contentLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP,
                LocaleController.isRTL ? 14 : 120, 8, LocaleController.isRTL ? 120 : 14, 8));
    }

    public void setData(ChannelReactionRankController.RankedResult result, int rank, boolean isLast) {
        rankView.setText(String.valueOf(rank));
        boolean previousNeedDivider = needDivider;
        needDivider = !isLast;
        if (previousNeedDivider != needDivider) {
            requestLayout();
        }

        MessageObject messageObject = result.message.messageObject;
        if (messageObject.photoThumbs != null && !messageObject.photoThumbs.isEmpty()) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, AndroidUtilities.getPhotoSize());
            TLRPC.PhotoSize thumbSize = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, 50);
            imageView.setImage(
                    ImageLocation.getForObject(size, messageObject.photoThumbsObject), "52_52",
                    ImageLocation.getForObject(thumbSize, messageObject.photoThumbsObject), "b1", 0, messageObject);
            imageView.setRoundRadius(AndroidUtilities.dp(9));
        } else {
            avatarDrawable.setInfo(chat);
            imageView.setForUserOrChat(chat, avatarDrawable);
            imageView.setRoundRadius(AndroidUtilities.dp(26));
        }

        CharSequence text = messageObject.caption != null ? messageObject.caption : messageObject.messageText;
        if (TextUtils.isEmpty(text)) {
            text = LocaleController.getString(R.string.ReactionRankMediaMessage);
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        URLSpan[] urlSpans = builder.getSpans(0, builder.length(), URLSpan.class);
        for (URLSpan span : urlSpans) {
            builder.removeSpan(span);
        }
        CharSequence cleanText = AndroidUtilities.trim(AndroidUtilities.replaceNewLines(builder), null);
        messageView.setText(Emoji.replaceEmoji(cleanText, messageView.getPaint().getFontMetricsInt(), false));

        String selected = AndroidUtilities.formatWholeNumber(result.selectedReactionCount, 0);
        String total = AndroidUtilities.formatWholeNumber(result.message.totalReactions, 0);
        reactionsView.setText(LocaleController.formatString(R.string.ReactionRankCellReactions, selected, total));

        Date time = new Date(result.message.date * 1000L);
        String date = LocaleController.formatString(
                R.string.formatDateAtTime,
                LocaleController.getInstance().getFormatterYear().format(time),
                LocaleController.getInstance().getFormatterDay().format(time));
        metadataView.setText(LocaleController.formatString(
                R.string.ReactionRankCellMetadata,
                AndroidUtilities.formatWholeNumber(result.message.views, 0),
                AndroidUtilities.formatWholeNumber(result.message.forwards, 0),
                date));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(104) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (needDivider) {
            dividerPaint.setColor(Theme.getColor(Theme.key_divider, resourcesProvider));
            float y = getHeight() - 1;
            if (LocaleController.isRTL) {
                canvas.drawRect(0, y, getWidth() - AndroidUtilities.dp(120), getHeight(), dividerPaint);
            } else {
                canvas.drawRect(AndroidUtilities.dp(120), y, getWidth(), getHeight(), dividerPaint);
            }
        }
    }
}
