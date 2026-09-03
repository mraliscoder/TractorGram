package network.zov.messenger;

public class Extra {
    public static boolean isVerifiedByTractor(long chatId) {
        return RemoteConfig.checkmarks.contains(chatId);
    }
}
