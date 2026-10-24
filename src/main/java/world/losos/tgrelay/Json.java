package world.losos.tgrelay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny JSON reader.
 *
 * The plugin talks to exactly one API with a well-known response shape, so pulling in
 * Gson (which Paper does not expose to plugins at compile time) would be a dependency
 * for nothing. This parser covers objects, arrays, strings with escapes, numbers,
 * booleans and null — everything Telegram sends.
 */
final class Json {

    private final String text;
    private int position;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        return value;
    }

    /** Wraps a string into a JSON literal — used to build the tiny bridge payloads. */
    static String quote(String text) {
        StringBuilder builder = new StringBuilder(text.length() + 2).append('"');
        for (char ch : text.toCharArray()) {
            switch (ch) {
                case '"': builder.append("\\\""); break;
                case '\\': builder.append("\\\\"); break;
                case '\n': builder.append("\\n"); break;
                case '\r': builder.append("\\r"); break;
                case '\t': builder.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
            }
        }
        return builder.append('"').toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private Object readValue() {
        if (position >= text.length()) {
            return null;
        }
        char ch = text.charAt(position);
        switch (ch) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': position += 4; return Boolean.TRUE;
            case 'f': position += 5; return Boolean.FALSE;
            case 'n': position += 4; return null;
            default: return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return result;
        }
        while (position < text.length()) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            result.put(key, readValue());
            skipWhitespace();
            char separator = peek();
            position++;
            if (separator == '}') {
                break;
            }
        }
        return result;
    }

    private List<Object> readArray() {
        List<Object> result = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return result;
        }
        while (position < text.length()) {
            skipWhitespace();
            result.add(readValue());
            skipWhitespace();
            char separator = peek();
            position++;
            if (separator == ']') {
                break;
            }
        }
        return result;
    }

    private String readString() {
        expect('"');
        StringBuilder builder = new StringBuilder();
        while (position < text.length()) {
            char ch = text.charAt(position++);
            if (ch == '"') {
                break;
            }
            if (ch != '\\') {
                builder.append(ch);
                continue;
            }
            char escaped = text.charAt(position++);
            switch (escaped) {
                case '"': builder.append('"'); break;
                case '\\': builder.append('\\'); break;
                case '/': builder.append('/'); break;
                case 'b': builder.append('\b'); break;
                case 'f': builder.append('\f'); break;
                case 'n': builder.append('\n'); break;
                case 'r': builder.append('\r'); break;
                case 't': builder.append('\t'); break;
                case 'u':
                    builder.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                    position += 4;
                    break;
                default: builder.append(escaped);
            }
        }
        return builder.toString();
    }

    private Object readNumber() {
        int start = position;
        while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
        String raw = text.substring(start, position);
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                // falls through to double
            }
        }
        return Double.parseDouble(raw);
    }

    private char peek() {
        return position < text.length() ? text.charAt(position) : '\0';
    }

    private void expect(char expected) {
        if (position >= text.length() || text.charAt(position) != expected) {
            throw new IllegalArgumentException("JSON: expected '" + expected + "' at " + position);
        }
        position++;
    }

    private void skipWhitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }
}
