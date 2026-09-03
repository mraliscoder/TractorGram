package network.zov.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.tgnet.TLRPC;

/**
 * The profile shows the id of whoever is being looked at. MTProto and the Bot API number the same
 * peer differently, so the row remembers which convention was asked for last; long-pressing it
 * flips between them. The choice is global rather than per-profile — someone who thinks in Bot API
 * ids thinks in them everywhere.
 */
public class PeerIdFormat {

    public static final int TELEGRAM_API = 0;
    public static final int BOT_API = 1;

    private static final String PREFS_NAME = "zov_peer_id";
    private static final String KEY_FORMAT = "format";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getFormat() {
        return prefs().getInt(KEY_FORMAT, TELEGRAM_API) == BOT_API ? BOT_API : TELEGRAM_API;
    }

    /** Flips the stored format and returns the new one. */
    public static int toggleFormat() {
        final int next = getFormat() == TELEGRAM_API ? BOT_API : TELEGRAM_API;
        prefs().edit().putInt(KEY_FORMAT, next).apply();
        return next;
    }

    /** Users and bots carry the same number in both APIs. */
    public static long forUser(long userId) {
        return userId;
    }

    /**
     * MTProto always uses the bare positive id. The Bot API negates a basic group, and pushes a
     * channel or supergroup past the -100 prefix so the two ranges cannot collide.
     */
    public static long forChat(long chatId, boolean channel, int format) {
        if (format != BOT_API) {
            return chatId;
        }
        return channel ? -1000000000000L - chatId : -chatId;
    }

    public static long forChat(TLRPC.Chat chat, int format) {
        if (chat == null) {
            return 0;
        }
        return forChat(chat.id, ChatObject.isChannel(chat), format);
    }
}
