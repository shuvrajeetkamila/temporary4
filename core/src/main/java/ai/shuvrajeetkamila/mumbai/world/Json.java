package ai.shuvrajeetkamila.mumbai.world;

/**
 * A deliberately tiny JSON scraper for the manifest blobs.
 *
 * <p>The engine reads a handful of scalars and one flat array of checkpoint
 * objects out of metadata the build pipeline wrote itself. Pulling in a real
 * JSON library for that would add a dependency to a module whose whole point is
 * to stay dependency free, so this does enough and no more: find a key at the
 * top nesting level and read the scalar after it.
 *
 * <p>It is not a general parser and is not used on anything the user can
 * supply. {@link #stringsOf} is the only structural method, and it is tolerant:
 * a malformed manifest yields empty results rather than an exception, because a
 * missing place name must not stop the game from starting.
 */
public final class Json {
    private Json() { }

    private static int findKey(String s, String key, int from) {
        String needle = "\"" + key + "\"";
        int i = s.indexOf(needle, from);
        if (i < 0) { return -1; }
        int c = s.indexOf(':', i + needle.length());
        return c < 0 ? -1 : c + 1;
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) { i++; }
        return i;
    }

    public static double number(String s, String key, double def) {
        int i = findKey(s, key, 0);
        if (i < 0) { return def; }
        i = skipSpace(s, i);
        int j = i;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'
                    || (c >= '0' && c <= '9')) { j++; } else { break; }
        }
        try {
            return Double.parseDouble(s.substring(i, j));
        } catch (RuntimeException e) {
            return def;
        }
    }

    public static int integer(String s, String key, int def) {
        double d = number(s, key, Double.NaN);
        return Double.isNaN(d) ? def : (int) Math.round(d);
    }

    public static boolean bool(String s, String key, boolean def) {
        int i = findKey(s, key, 0);
        if (i < 0) { return def; }
        i = skipSpace(s, i);
        if (s.startsWith("true", i)) { return true; }
        if (s.startsWith("false", i)) { return false; }
        return def;
    }

    public static String string(String s, String key, String def) {
        int i = findKey(s, key, 0);
        if (i < 0) { return def; }
        i = skipSpace(s, i);
        if (i >= s.length() || s.charAt(i) != '"') { return def; }
        StringBuilder sb = new StringBuilder();
        for (int j = i + 1; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '\\' && j + 1 < s.length()) {
                char n = s.charAt(++j);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (j + 4 < s.length()) {
                            sb.append((char) Integer.parseInt(
                                    s.substring(j + 1, j + 5), 16));
                            j += 4;
                        }
                        break;
                    default: sb.append(n);
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return def;
    }

    /** Split a flat JSON array of objects into its element substrings. */
    public static java.util.List<String> objectsOf(String s, String arrayKey) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int i = arrayKey == null ? s.indexOf('[') : findKey(s, arrayKey, 0);
        if (i < 0) { return out; }
        i = s.indexOf('[', i);
        if (i < 0) { return out; }
        int depth = 0;
        int start = -1;
        for (int j = i; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '"') {
                j++;
                while (j < s.length() && (s.charAt(j) != '"' || s.charAt(j - 1) == '\\')) {
                    j++;
                }
                continue;
            }
            if (c == '{') {
                if (depth == 0) { start = j; }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(s.substring(start, j + 1));
                    start = -1;
                }
            } else if (c == ']' && depth == 0) {
                break;
            }
        }
        return out;
    }

    public static java.util.List<String> stringsOf(String s, String arrayKey) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int i = findKey(s, arrayKey, 0);
        if (i < 0) { return out; }
        i = s.indexOf('[', i);
        if (i < 0) { return out; }
        for (int j = i + 1; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == ']') { break; }
            if (c == '"') {
                StringBuilder sb = new StringBuilder();
                for (j++; j < s.length(); j++) {
                    char d = s.charAt(j);
                    if (d == '\\' && j + 1 < s.length()) { sb.append(s.charAt(++j)); }
                    else if (d == '"') { break; }
                    else { sb.append(d); }
                }
                out.add(sb.toString());
            }
        }
        return out;
    }
}
