package com.abhinav.taskflow.common.util;

import java.util.Locale;

public final class Normalize {

    private Normalize() {}

    public static String normalizeEmail (String raw) {
        return raw == null ? null : raw.strip().toLowerCase(Locale.ROOT);
    }

    public static String normalizeUsername (String raw) {
        return raw == null ? null : raw.strip().toLowerCase(Locale.ROOT);
    }
}
