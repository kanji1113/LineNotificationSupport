package com.mysticwind.linenotificationsupport.model;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;

import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;

import com.mysticwind.linenotificationsupport.utils.ChatTitleAndSenderResolver;
import com.mysticwind.linenotificationsupport.utils.StatusBarNotificationPrinter;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LineNotificationBuilder {

    public static final String CALL_VIRTUAL_CHAT_ID = "call_virtual_chat_id";
    public static final String DEFAULT_CHAT_ID = "default_chat_id";

    protected static final String CALL_CATEGORY = "call";
    protected static final String MESSAGE_CATEGORY = "msg";
    protected static final String MISSED_CALL_TAG = "NOTIFICATION_TAG_MISSED_CALL";
    protected static final String GENERAL_NOTIFICATION_CHANNEL = "jp.naver.line.android.notification.GeneralNotifications";
    protected static final String DEFAULT_SENDER_NAME = "?";

    private final Context context;
    private final ChatTitleAndSenderResolver chatTitleAndSenderResolver;
    private final StatusBarNotificationPrinter statusBarNotificationPrinter;

    public LineNotificationBuilder(final Context context,
                                   final ChatTitleAndSenderResolver chatTitleAndSenderResolver,
                                   final StatusBarNotificationPrinter statusBarNotificationPrinter) {
        this.context = context;
        this.chatTitleAndSenderResolver = chatTitleAndSenderResolver;
        this.statusBarNotificationPrinter = statusBarNotificationPrinter;
    }

    public LineNotification from(StatusBarNotification statusBarNotification) {
        final Pair<String, String> titleAndSender = chatTitleAndSenderResolver.resolveTitleAndSender(statusBarNotification);
        final String title = titleAndSender.getLeft();
        final String sender = titleAndSender.getRight();
        final Bitmap largeIconBitmap = getLargeIconBitmap(statusBarNotification);
        final Person senderPerson = buildPerson(sender, largeIconBitmap, statusBarNotification);
        final LineNotification.CallState callState = resolveCallState(statusBarNotification);
        final List<Notification.Action> actions = extractActions(statusBarNotification, callState);

        final String message = getMessage(statusBarNotification);
        final String lineStickerUrl = getLineStickerUrl(statusBarNotification);
        return LineNotification.builder()
                .title(title)
                .message(message)
                .sender(senderPerson)
                .lineStickerUrl(lineStickerUrl)
                .chatId(resolveChatId(statusBarNotification, callState))
                .timestamp(statusBarNotification.getPostTime())
                .actions(actions)
                .icon(largeIconBitmap)
                .callState(callState)
                .build();
    }

    private LineNotification.CallState resolveCallState(final StatusBarNotification statusBarNotification) {
        if (CALL_CATEGORY.equals(statusBarNotification.getNotification().category)) {
            return LineNotification.CallState.INCOMING;
        } else if (MISSED_CALL_TAG.equals(statusBarNotification.getTag())) {
            return LineNotification.CallState.MISSED_CALL;
        // if not incoming, not missed, it is probably in a call ... (but not guaranteed, we'll see)
        } else if (GENERAL_NOTIFICATION_CHANNEL.equals(statusBarNotification.getNotification().getChannelId())) {
            return LineNotification.CallState.IN_A_CALL;
        }
        return null;
    }

    private String resolveChatId(final StatusBarNotification statusBarNotification, LineNotification.CallState callState) {
        if (callState != null) {
            return CALL_VIRTUAL_CHAT_ID;
        }
        final String lineChatId = getChatId(statusBarNotification);
        if (StringUtils.isBlank(lineChatId)) {
            return DEFAULT_CHAT_ID;
        }
        return lineChatId;
    }

    private String getChatId(final StatusBarNotification statusBarNotification) {
        return statusBarNotification.getNotification().extras.getString("line.chat.id");
    }

    private String getLineStickerUrl(final StatusBarNotification statusBarNotification) {
        return statusBarNotification.getNotification().extras.getString("line.sticker.url");
    }

    private Bitmap getLargeIconBitmap(StatusBarNotification statusBarNotification) {
        final Icon largeIcon = statusBarNotification.getNotification().getLargeIcon();
        if (largeIcon == null) {
            return null;
        }
        return convertDrawableToBitmap(largeIcon.loadDrawable(context));
    }

    private Bitmap convertDrawableToBitmap(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            if (bitmapDrawable.getBitmap() != null) {
                return bitmapDrawable.getBitmap();
            }
        }

        final Bitmap bitmap;
        if(drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); // Single color bitmap will be created of 1x1 pixel
        } else {
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        }

        final Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private Person buildPerson(final String sender,
                               final Bitmap iconBitmap,
                               final StatusBarNotification statusBarNotification) {
        final IconCompat icon;
        if (iconBitmap == null) {
            icon = null;
        } else {
            icon = IconCompat.createWithBitmap(iconBitmap);
        }
        final String senderName;
        if (StringUtils.isBlank(sender)) {
            senderName = DEFAULT_SENDER_NAME;
            statusBarNotificationPrinter.printError("No sender identified, using default!", statusBarNotification);
        } else {
           senderName = sender;
        }
        return new Person.Builder()
                .setName(senderName)
                .setIcon(icon)
                .build();
    }

    private boolean isMessage(StatusBarNotification statusBarNotification) {
        return MESSAGE_CATEGORY.equals(statusBarNotification.getNotification().category);
    }

    private String getMessage(StatusBarNotification statusBarNotification) {
        return statusBarNotification.getNotification().extras.getString("android.text");
    }

    private List<Notification.Action> extractActions(final StatusBarNotification statusBarNotification,
                                                     final LineNotification.CallState callState) {
        if(isMessage(statusBarNotification)) {
            // mute and reply buttons
            // the mute button doesn't seem very useful
            return extractActionsOfIndices(statusBarNotification, 1);
        }

        if (callState == null) {
            return Collections.EMPTY_LIST;
        }

        switch (callState) {
            // decline and accept call buttons
            case INCOMING:
                // reverse the order - accepting a call seems to be more important
                return extractActionsOfIndices(statusBarNotification, 1, 0);
            case MISSED_CALL:
                // reply and redial buttons
                // the reply button opens something on phone, not really helpful
                return extractActionsOfIndices(statusBarNotification, 1);
            case IN_A_CALL:
                // end call button
                return extractActionsOfIndices(statusBarNotification, 0);
            default:
                return Collections.EMPTY_LIST;
        }
    }

    private List<Notification.Action> extractActionsOfIndices(final StatusBarNotification notificationFromLine,
                                                              final int... indices) {
        List<Notification.Action> extractedActions = new ArrayList<>();
        if (notificationFromLine.getNotification().actions == null) {
            return extractedActions;
        }
        for (final int index : indices) {
            if (index < notificationFromLine.getNotification().actions.length) {
                extractedActions.add(notificationFromLine.getNotification().actions[index]);
            }
        }
        return extractedActions;
    }

}
