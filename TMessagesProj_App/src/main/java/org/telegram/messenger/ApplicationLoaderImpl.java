package org.telegram.messenger;

import org.telegram.messenger.regular.BuildConfig;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    public boolean usesTractorGramUpdates() {
        return "release".equals(BuildConfig.BUILD_TYPE) && "bundleAfat".equals(BuildConfig.FLAVOR) &&
                "network.zov.messenger".equals(BuildConfig.APPLICATION_ID);
    }

    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }
}
