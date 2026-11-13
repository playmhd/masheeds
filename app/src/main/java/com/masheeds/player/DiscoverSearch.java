package com.masheeds.player;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

final class DiscoverSearch {
    private static final Pattern MARKS = Pattern.compile("[\\p{M}ـ]");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern ARABIC_ALEF = Pattern.compile("[أإآٱ]");
    private static final Pattern TRANSLITERATION = Pattern.compile("(?<=\\p{sc=Latin})[’'ʻ](?=\\p{sc=Latin})");
    private static final Pattern AL_PREFIX = Pattern.compile("\\b(?:al|el) (?=\\p{sc=Latin})");

    private DiscoverSearch() {}

    static String key(String value) {
        String text = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD);
        text = MARKS.matcher(text).replaceAll("");
        text = ARABIC_ALEF.matcher(text).replaceAll("ا").replace('ى', 'ي').replace('ة', 'ه');
        text = TRANSLITERATION.matcher(text).replaceAll("");
        text = SEPARATORS.matcher(text).replaceAll(" ").trim();
        return AL_PREFIX.matcher(text).replaceAll("al");
    }

    static boolean matches(String normalizedQuery, TrackLibrary.Track track) {
        if (normalizedQuery.isEmpty()) {
            return true;
        }
        String fields = key(track.title() + " " + track.artist() + " " + track.album() + " "
                + track.collection() + " " + track.searchAliases());
        for (String word : normalizedQuery.split(" ")) {
            if (!fields.contains(word)) {
                return false;
            }
        }
        return true;
    }
}
