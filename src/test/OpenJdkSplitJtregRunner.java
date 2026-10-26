package test;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Executes all 4 official OpenJDK jtreg String.split test suites:
 *   1. test/jdk/java/lang/String/Split.java (@bug 6840246 6559590)
 *   2. test/jdk/java/lang/String/Regex.java (@bug 4808962)
 *   3. test/jdk/java/lang/String/CompactString/Split.java (@bug 8077559)
 *   4. test/jdk/java/util/regex/SplitWithDelimitersTest.java (@bug 8305486)
 */
public final class OpenJdkSplitJtregRunner {

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        System.out.println("=== Running Official OpenJDK jtreg String.split Test Suites ===");

        int count1 = runJtregStringSplit();
        System.out.printf("  [PASS] 1. test/jdk/java/lang/String/Split.java              (%,d assertions)%n", count1);

        int count2 = runJtregStringRegex();
        System.out.printf("  [PASS] 2. test/jdk/java/lang/String/Regex.java              (%,d assertions)%n", count2);

        int count3 = runJtregCompactStringSplit();
        System.out.printf("  [PASS] 3. test/jdk/java/lang/String/CompactString/Split.java (%,d assertions)%n", count3);

        int count4 = runJtregSplitWithDelimiters();
        System.out.printf("  [PASS] 4. test/jdk/java/util/regex/SplitWithDelimitersTest.java (%,d assertions)%n", count4);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.printf("=== ALL 4 JTREG SUITES PASSED (Total: %,d assertions in %d ms) ===%n",
                count1 + count2 + count3 + count4, elapsedMs);
    }

    // 1. Exact logic of test/jdk/java/lang/String/Split.java (@bug 6840246 6559590)
    private static int runJtregStringSplit() {
        int checks = 0;
        String source = "0123456789";

        for (int limit = -2; limit < 3; limit++) {
            for (int x = 0; x < 10; x++) {
                String[] result = source.split(Integer.toString(x), limit);
                int expectedLength = limit < 1 ? 2 : limit;

                if ((limit == 0) && (x == 9)) {
                    if (result.length != 1)
                        throw new RuntimeException("String.split failure 1");
                    if (!result[0].equals("012345678"))
                        throw new RuntimeException("String.split failure 2");
                } else {
                    if (result.length != expectedLength)
                        throw new RuntimeException("String.split failure 3");
                    if (!result[0].equals(source.substring(0, x))) {
                        if (limit != 1) {
                            throw new RuntimeException("String.split failure 4");
                        } else {
                            if (!result[0].equals(source.substring(0, 10))) {
                                throw new RuntimeException("String.split failure 10");
                            }
                        }
                    }
                    if (expectedLength > 1) {
                        if (!result[1].equals(source.substring(x + 1, 10)))
                            throw new RuntimeException("String.split failure 5");
                    }
                }
                checks++;
            }
        }
        for (int limit = -2; limit < 3; limit++) {
            String[] result = source.split("e", limit);
            if (result.length != 1)
                throw new RuntimeException("String.split failure 6");
            if (!result[0].equals(source))
                throw new RuntimeException("String.split failure 7");
            checks++;
        }
        source = "";
        String[] result = source.split("e", 0);
        if (result.length != 1)
            throw new RuntimeException("String.split failure 8");
        if (!result[0].equals(source))
            throw new RuntimeException("String.split failure 9");
        checks++;

        source = "0123456789abcdefgABCDEFG";
        Random r = new Random(42);

        for (boolean doEscape : new boolean[] {false, true}) {
            for (int cp = 0; cp < 0x11000; cp++) {
                Pattern p;
                String regex = new String(Character.toChars(cp));
                if (doEscape)
                    regex = "\\" + regex;
                try {
                    p = Pattern.compile(regex);
                } catch (PatternSyntaxException pse) {
                    try {
                        "abc".split(regex);
                    } catch (PatternSyntaxException pse0) {
                        checks++;
                        continue;
                    }
                    throw new RuntimeException("String.split failure 11");
                }
                int off = r.nextInt(source.length());
                String[] srcStrs = new String[] {
                    "",
                    source,
                    regex + source,
                    source + regex,
                    source.substring(0, 3)
                        + regex + source.substring(3, 9)
                        + regex + source.substring(9, 15)
                        + regex + source.substring(15),
                    source.substring(0, off) + regex + source.substring(off)
                };
                for (String src : srcStrs) {
                    for (int limit = -2; limit < 3; limit++) {
                        if (!Arrays.equals(src.split(regex, limit), p.split(src, limit)))
                            throw new RuntimeException("String.split failure 12: cp=" + cp + " limit=" + limit);
                        checks++;
                    }
                }
            }
        }
        return checks;
    }

    // 2. Exact logic of test/jdk/java/lang/String/Regex.java (@bug 4808962)
    private static int runJtregStringRegex() throws Exception {
        String foo = "boo:and:foo";
        if (!Arrays.equals(foo.split(":"), new String[] { "boo", "and", "foo" }))
            throw new Exception("Regex failure 1");
        if (!Arrays.equals(foo.split("o"), new String[] { "b", "", ":and:f" }))
            throw new Exception("Regex failure 2");
        if (!Arrays.equals(foo.split(":", 2), new String[] { "boo", "and:foo" }))
            throw new Exception("Regex failure 3");
        if (!Arrays.equals(foo.split("o", -2), new String[] { "b", "", ":and:f", "", "" }))
            throw new Exception("Regex failure 4");
        return 4;
    }

    // 3. Exact logic of test/jdk/java/lang/String/CompactString/Split.java (@bug 8077559)
    private static int runJtregCompactStringSplit() throws Exception {
        String STRING_L1 = "A";
        String STRING_L2 = "AB";
        String STRING_LLONG = "ABCDEFGH";
        String STRING_U1 = "\uFF21";
        String STRING_M12 = "\uFF21A";
        String STRING_UDUPLICATE = "\uFF21\uFF22\uFF21\uFF22\uFF21\uFF22\uFF21\uFF22\uFF21\uFF22";
        String STRING_MDUPLICATE1 = "\uFF21A\uFF21A\uFF21A\uFF21A\uFF21A";
        String STRING_SUPPLEMENTARY = "\uD801\uDC00\uD801\uDC01\uFF21A";
        String STRING_SUPPLEMENTARY_LOWERCASE = "\uD801\uDC28\uD801\uDC29\uFF41a";

        Object[][] cases = new Object[][] {
            { STRING_L1, "", 0, new String[] { "A" } },
            { STRING_L1, "", 1, new String[] { "A" } },
            { STRING_L1, "", 2, new String[] { "A", "" } },
            { STRING_L1, "A", 0, new String[] {} },
            { STRING_L2, "A", 0, new String[] { "", "B" } },
            { STRING_L2, "B", 0, new String[] { "A" } },
            { STRING_LLONG, "D", 0, new String[] { "ABC", "EFGH" } },
            { STRING_LLONG, "[D]", 0, new String[] { "ABC", "EFGH" } },
            { STRING_LLONG, "CD", 0, new String[] { "AB", "EFGH" } },
            { STRING_LLONG, "DC", 0, new String[] { "ABCDEFGH" } },
            { STRING_LLONG, "[CF]", 0, new String[] { "AB", "DE", "GH" } },
            { STRING_LLONG, "[CF]", 1, new String[] { "ABCDEFGH" } },
            { STRING_LLONG, "[CF]", 2, new String[] { "AB", "DEFGH" } },
            { STRING_LLONG, "[FC]", 0, new String[] { "AB", "DE", "GH" } },
            { STRING_LLONG, "[FC]", 1, new String[] { "ABCDEFGH" } },
            { STRING_LLONG, "[FC]", 2, new String[] { "AB", "DEFGH" } },
            { STRING_U1, "", 0, new String[] { "\uFF21" } },
            { STRING_U1, "", 1, new String[] { "\uFF21" } },
            { STRING_U1, "", 2, new String[] { "\uFF21", "" } },
            { STRING_U1, "\uFF21", 0, new String[] {} },
            { STRING_M12, "\uFF21", 0, new String[] { "", "A" } },
            { STRING_M12, "A", 0, new String[] { "\uFF21" } },
            { STRING_UDUPLICATE, "\uFF21", 0, new String[] { "", "\uFF22", "\uFF22", "\uFF22", "\uFF22", "\uFF22" } },
            { STRING_UDUPLICATE, "\uFF21", 2, new String[] { "", "\uFF22\uFF21\uFF22\uFF21\uFF22\uFF21\uFF22\uFF21\uFF22" } },
            { STRING_UDUPLICATE, "\uFF21", 4, new String[] { "", "\uFF22", "\uFF22", "\uFF22\uFF21\uFF22\uFF21\uFF22" } },
            { STRING_UDUPLICATE, "\uFF22", 0, new String[] { "\uFF21", "\uFF21", "\uFF21", "\uFF21", "\uFF21" } },
            { STRING_UDUPLICATE, "\uFF22", 3, new String[] { "\uFF21", "\uFF21", "\uFF21\uFF22\uFF21\uFF22\uFF21\uFF22" } },
            { STRING_MDUPLICATE1, "\uFF21", 0, new String[] { "", "A", "A", "A", "A", "A" } },
            { STRING_MDUPLICATE1, "\uFF21", 3, new String[] { "", "A", "A\uFF21A\uFF21A\uFF21A" } },
            { STRING_MDUPLICATE1, "A", 0, new String[] { "\uFF21", "\uFF21", "\uFF21", "\uFF21", "\uFF21" } },
            { STRING_MDUPLICATE1, "A", 4, new String[] { "\uFF21", "\uFF21", "\uFF21", "\uFF21A\uFF21A" } },
            { STRING_SUPPLEMENTARY, "\uD801\uDC01", 0, new String[] { "\uD801\uDC00", "\uFF21A" } },
            { STRING_SUPPLEMENTARY, "\uDC01", 0, new String[] { "\uD801\uDC00\uD801\uDC01\uFF21A" } },
            { STRING_SUPPLEMENTARY, "[\uD801\uDC01\uFF21]", 0, new String[] { "\uD801\uDC00", "", "A" } },
            { STRING_SUPPLEMENTARY, "[\uD801\uDC01\uFF21]", 1, new String[] { "\uD801\uDC00\uD801\uDC01\uFF21A" } },
            { STRING_SUPPLEMENTARY, "[\uD801\uDC01\uFF21]", 2, new String[] { "\uD801\uDC00", "\uFF21A" } },
            { STRING_SUPPLEMENTARY, "[\uFF21\uD801\uDC01]", 0, new String[] { "\uD801\uDC00", "", "A" } },
            { STRING_SUPPLEMENTARY, "[\uFF21\uD801\uDC01]", 1, new String[] { "\uD801\uDC00\uD801\uDC01\uFF21A" } },
            { STRING_SUPPLEMENTARY, "[\uFF21\uD801\uDC01]", 2, new String[] { "\uD801\uDC00", "\uFF21A" } },
            { STRING_SUPPLEMENTARY_LOWERCASE, "\uDC01", 0, new String[] { "\uD801\uDC28\uD801\uDC29\uFF41a" } },
            { STRING_SUPPLEMENTARY_LOWERCASE, "\uD801\uDC29", 0, new String[] { "\uD801\uDC28", "\uFF41a" } },
            { STRING_SUPPLEMENTARY_LOWERCASE, "[\uD801\uDC29\uFF41]", 0, new String[] { "\uD801\uDC28", "", "a" } },
            { STRING_SUPPLEMENTARY_LOWERCASE, "[\uD801\uDC29\uFF41]", 1, new String[] { "\uD801\uDC28\uD801\uDC29\uFF41a" } },
            { STRING_SUPPLEMENTARY_LOWERCASE, "[\uD801\uDC29\uFF41]", 2, new String[] { "\uD801\uDC28", "\uFF41a" } },
            { STRING_SUPPLEMENTARY_LOWERCASE, "[\uFF41\uD801\uDC29]", 0, new String[] { "\uD801\uDC28", "", "a" } },
            { STRING_SUPPLEMENTARY_LOWERCASE, "[\uFF41\uD801\uDC29]", 1, new String[] { "\uD801\uDC28\uD801\uDC29\uFF41a" } },
            { STRING_SUPPLEMENTARY_LOWERCASE, "[\uFF41\uD801\uDC29]", 2, new String[] { "\uD801\uDC28", "\uFF41a" } }
        };

        int checks = 0;
        Charset utf8 = Charset.forName("UTF-8");
        for (Object[] c : cases) {
            String base = (String) c[0];
            String regex = (String) c[1];
            int limit = (Integer) c[2];
            String[] expected = (String[]) c[3];

            byte[] ba = base.getBytes(utf8);
            char[] ca = base.toCharArray();
            int[] cpa = base.codePoints().toArray();
            // 9 construction sources just like CompactString.java
            String[] variants = new String[] {
                new String(ba, "UTF-8"),
                new String(ba, utf8),
                new String(ca),
                new String(cpa, 0, cpa.length),
                new String(base),
                new String(new StringBuffer(base)),
                new String(new StringBuilder(base)),
                String.copyValueOf(ca),
                String.valueOf(ca)
            };
            for (String data : variants) {
                String[] actual = data.split(regex, limit);
                if (!Arrays.equals(actual, expected)) {
                    throw new RuntimeException("CompactString Split failed for " + base + " regex=" + regex + " limit=" + limit);
                }
                checks++;
            }
        }
        return checks;
    }

    // 4. Exact logic of test/jdk/java/util/regex/SplitWithDelimitersTest.java (@bug 8305486)
    private static String[] dropOddIndexed(String[] a, int limit) {
        String[] r = new String[(a.length + 1) / 2];
        for (int i = 0; i < a.length; i += 2) {
            r[i / 2] = a[i];
        }
        int len = r.length;
        if (limit == 0) {
            for (; len > 0 && r[len - 1].isEmpty(); --len);
        }
        return len < r.length ? Arrays.copyOf(r, len) : r;
    }

    private static int runJtregSplitWithDelimiters() {
        Object[][] cases = new Object[][] {
            { new String[] {"b", "o", "", "o", ":::and::f", "o", "", "o", ""}, "boo:::and::foo", "o", 5 },
            { new String[] {"b", "o", "", "o", ":::and::f", "o", "o"}, "boo:::and::foo", "o", 4 },
            { new String[] {"b", "o", "", "o", ":::and::foo"}, "boo:::and::foo", "o", 3 },
            { new String[] {"b", "o", "o:::and::foo"}, "boo:::and::foo", "o", 2 },
            { new String[] {"boo:::and::foo"}, "boo:::and::foo", "o", 1 },
            { new String[] {"b", "o", "", "o", ":::and::f", "o", "", "o"}, "boo:::and::foo", "o", 0 },
            { new String[] {"b", "o", "", "o", ":::and::f", "o", "", "o", ""}, "boo:::and::foo", "o", -1 },

            { new String[] {"boo", ":::", "and", "::", "foo"}, "boo:::and::foo", ":+", 3 },
            { new String[] {"boo", ":::", "and::foo"}, "boo:::and::foo", ":+", 2 },
            { new String[] {"boo:::and::foo"}, "boo:::and::foo", ":+", 1 },
            { new String[] {"boo", ":::", "and", "::", "foo"}, "boo:::and::foo", ":+", 0 },
            { new String[] {"boo", ":::", "and", "::", "foo"}, "boo:::and::foo", ":+", -1 },

            { new String[] {"b", "", "b", "", ""}, "bb", "a*|b*", 3 },
            { new String[] {"b", "", "b"}, "bb", "a*|b*", 2 },
            { new String[] {"bb"}, "bb", "a*|b*", 1 },
            { new String[] {"b", "", "b"}, "bb", "a*|b*", 0 },
            { new String[] {"b", "", "b", "", ""}, "bb", "a*|b*", -1 },

            { new String[] {"", "bb", "", "", ""}, "bb", "b*|a*", 3 },
            { new String[] {"", "bb", ""}, "bb", "b*|a*", 2 },
            { new String[] {"bb"}, "bb", "b*|a*", 1 },
            { new String[] {"", "bb"}, "bb", "b*|a*", 0 },
            { new String[] {"", "bb", "", "", ""}, "bb", "b*|a*", -1 }
        };

        int checks = 0;
        for (Object[] c : cases) {
            String[] expected = (String[]) c[0];
            String target = (String) c[1];
            String regex = (String) c[2];
            int limit = (Integer) c[3];

            String[] computedWith = target.splitWithDelimiters(regex, limit);
            if (!Arrays.equals(expected, computedWith))
                throw new RuntimeException("SplitWithDelimiters failure 1");
            String[] patComputedWith = Pattern.compile(regex).splitWithDelimiters(target, limit);
            if (!Arrays.equals(computedWith, patComputedWith))
                throw new RuntimeException("SplitWithDelimiters failure 2");

            String[] computedWithout = target.split(regex, limit);
            if (!Arrays.equals(dropOddIndexed(expected, limit), computedWithout))
                throw new RuntimeException("SplitWithDelimiters failure 3");
            String[] patComputedWithout = Pattern.compile(regex).split(target, limit);
            if (!Arrays.equals(computedWithout, patComputedWithout))
                throw new RuntimeException("SplitWithDelimiters failure 4");
            checks += 4;
        }
        return checks;
    }
}
