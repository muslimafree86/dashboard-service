package com.muslimafree.dashboard.location;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalises place names the way members type them, so "الإسكندرية", "اسكندرية", "الاسكندريه", "محافظة الإسكندرية" and
 * "Alexandria Governorate" all meet: diacritics and tatweel removed, hamza/alef/yaa/taa-marbuta forms unified,
 * Arabic-Indic digits to ASCII, the article "ال" / "El-" / "Al-" dropped from every word, administrative words dropped,
 * case folded.
 */
public final class PlaceNames {

    private static final Pattern DIACRITICS = Pattern.compile("[\\u064B-\\u065F\\u0670\\u0640]");
    private static final Pattern NOT_LETTER = Pattern.compile("[^\\p{L}\\p{N} ]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern ADMIN_WORDS =
            Pattern.compile("^(محافظه|مدينه|منطقه|حي|governorate|gov|city|district|province)$");

    private PlaceNames() {
    }

    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String s = DIACRITICS.matcher(name).replaceAll("");
        StringBuilder unified = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            unified.append(switch (c) {
                case 'أ', 'إ', 'آ', 'ٱ' -> 'ا';
                case 'ى', 'ئ' -> 'ي';
                case 'ة' -> 'ه';
                case 'ؤ' -> 'و';
                case '-', '_', '/', '.', '،', ',' -> ' ';
                default -> c >= '٠' && c <= '٩' ? (char) ('0' + (c - '٠')) : c;
            });
        }
        s = NOT_LETTER.matcher(unified.toString().toLowerCase(Locale.ROOT)).replaceAll(" ");
        StringBuilder out = new StringBuilder();
        for (String word : SPACES.split(s.strip())) {
            String w = word;
            if (w.startsWith("ال") && w.length() > 3) {
                w = w.substring(2);
            } else if ((w.equals("el") || w.equals("al")) && !out.isEmpty()) {
                continue;
            }
            if (w.isEmpty() || ADMIN_WORDS.matcher(w).matches() || w.equals("el") || w.equals("al")) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(w);
        }
        return out.toString();
    }
}
