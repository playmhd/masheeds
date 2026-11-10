package com.masheeds.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

final class AppPreferences {
    static final Locale ARABIC = Locale.forLanguageTag("ar");
    private static final String DARK_THEME = "dark_theme";
    private static final String LANGUAGE = "language";
    private static final String NAVIGATION_HAPTICS = "navigation_haptics";

    private AppPreferences() {}

    static boolean isDark(Context context) {
        return preferences(context).getBoolean(DARK_THEME, false);
    }

    static Locale locale(Context context) {
        String language = preferences(context).getString(LANGUAGE, "en");
        return ARABIC.getLanguage().equals(language) ? ARABIC : Locale.ENGLISH;
    }

    static void setDark(Context context, boolean dark) {
        preferences(context).edit().putBoolean(DARK_THEME, dark).apply();
    }

    static void setLocale(Context context, Locale locale) {
        preferences(context).edit().putString(LANGUAGE, locale.getLanguage()).apply();
    }

    static boolean navigationHapticsEnabled(Context context) {
        return preferences(context).getBoolean(NAVIGATION_HAPTICS, true);
    }

    static void setNavigationHapticsEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(NAVIGATION_HAPTICS, enabled).apply();
    }

    static Context configuredContext(Context context) {
        Configuration overrides = new Configuration();
        overrides.uiMode = isDark(context) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        overrides.setLocale(locale(context));
        return context.createConfigurationContext(overrides);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
    }
}
