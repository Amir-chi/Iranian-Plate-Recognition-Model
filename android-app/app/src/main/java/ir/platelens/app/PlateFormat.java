package ir.platelens.app;

import java.util.regex.Pattern;

public final class PlateFormat {
    private static final Pattern STANDARD = Pattern.compile("[۰-۹]{2}[ابپتثجحدزژسشصطعفقکلمنوهیDS][۰-۹]{5}");
    private PlateFormat() {}
    public static boolean isStandard(String text) { return STANDARD.matcher(text).matches(); }
}
