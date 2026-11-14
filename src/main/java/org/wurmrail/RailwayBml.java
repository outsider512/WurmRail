package org.wurmrail;

/** Safe display strings for Wurm's BML grammar; never use these as object identity. */
public final class RailwayBml {
    private RailwayBml() { }

    public static String text(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) result.append(' ');
            else if (c == '"') result.append('\u201d');
            else if (c == '\'') result.append('\u2019');
            else if (c == '{') result.append('(');
            else if (c == '}') result.append(')');
            else if (c == '\\') result.append('/');
            else if (c == ';') result.append(':');
            else result.append(c);
        }
        return result.toString();
    }

    /** A comma delimits choices even inside a quoted BML dropdown options string. */
    public static String option(String value) {
        return text(value).replace(',', '/');
    }
}
