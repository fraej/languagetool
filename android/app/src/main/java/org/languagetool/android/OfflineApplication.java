/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.android;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;

public final class OfflineApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    }
}
