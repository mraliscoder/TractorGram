package network.zov.messenger;

public class Extra {
    public static boolean isVerifiedByTractor(long chatId) {
        for (Long id : RemoteConfig.checkmarks) {
            if (id == chatId) return true;
        }
        return false;
    }
}
