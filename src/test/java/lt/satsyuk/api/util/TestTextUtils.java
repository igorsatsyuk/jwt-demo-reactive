package lt.satsyuk.api.util;

public final class TestTextUtils {

    private TestTextUtils() {
    }

    public static int countOccurrences(String text, String token) {
        int count = 0;
        int fromIndex = 0;
        while (true) {
            int next = text.indexOf(token, fromIndex);
            if (next < 0) {
                return count;
            }
            count++;
            fromIndex = next + token.length();
        }
    }
}

