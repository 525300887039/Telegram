package org.telegram.messenger;

import org.telegram.messenger.video.VideoAds;

public final class AdFreeController {

    private AdFreeController() {
    }

    public static boolean isEnabled() {
        return SharedConfig.adFreeEnabled;
    }

    public static void setEnabled(boolean enabled) {
        if (SharedConfig.adFreeEnabled == enabled) {
            return;
        }

        SharedConfig.setAdFreeEnabled(enabled);

        if (enabled) {
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (UserConfig.getInstance(account).isClientActivated()) {
                    MessagesController.getInstance(account).clearSponsoredMessages();
                }
            }
            VideoAds.dropCache();
        }

        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (UserConfig.getInstance(account).isClientActivated()) {
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.adFreeSettingsChanged, enabled);
            }
        }
    }
}
