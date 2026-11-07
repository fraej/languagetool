/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.android;

import android.app.Application;
import com.google.android.material.color.DynamicColors;

public final class OfflineApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
