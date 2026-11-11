package com.gathin.tvhome.gate;

import java.util.ArrayList;
import java.util.List;

/**
 * Line breaking for quiz text (pure, unit tested). A TextView breaks Chinese between any two characters, so it
 * splits "比小刚少11张" or leaves a single character on the last line. Here lines break after clause punctuation
 * first, then between English words, and only as a last resort inside a Chinese clause, and the lines come out
 * about equally long.
 */
final class QuizText {
    private QuizText() {}

    /** Width of a piece of text in any unit; the Activity passes Paint.measureText. */
    interface Measurer {
        float width(String s);
    }

    private static final String CLAUSE_END = "。？！；：.?!;:";
    private static final String COMMA = "，,";
    private static final String CLOSING = "，。？！；：、”’）》】…,.?!;:)]}%";
    private static final String CLOSING_QUOTES = "”’）》】)]}";
    private static final String OPENING = "“‘（《【([{";
    private static final String GLUE = "'’_-＋－×÷＝+=<>＜＞/□";

    private static final float NEVER = 1e9f;
    /**
     * Cost of one more line, of uneven lines, and of a last line under a quarter wide. Where a line may end counts
     * for more than evenness (a sentence stays whole even if that leaves a short line), and a break inside a
     * Chinese clause costs more than an extra line.
     */
    private static final float LINE = 30, RAGGED = 20, ORPHAN = 80;

    /**
     * Returns s with '\n' inserted so that every line is at most maxWidth wide (a single unbreakable word that is
     * wider stays on its own line). Existing newlines are kept; spaces at a break are dropped.
     */
    static String wrap(String s, float maxWidth, Measurer m) {
        if (s == null || s.isEmpty() || maxWidth <= 0) return s;
        StringBuilder out = new StringBuilder();
        for (String para : s.split("\n", -1)) {
            if (out.length() > 0) out.append('\n');
            out.append(wrapParagraph(para.trim(), maxWidth, m));
        }
        return out.toString();
    }

    /** "13:00" -> "下午 1:00": the clock a child reads, not the 24-hour form of the settings file. */
    static String clock(String hhmm) {
        String[] p = hhmm.trim().split(":");
        int h = Integer.parseInt(p[0]);
        return (h < 12 ? "上午" : h < 18 ? "下午" : "晚上") + " " + (h % 12 == 0 ? 12 : h % 12) + ":" + p[1];
    }

    static int lineCount(String wrapped) {
        if (wrapped == null || wrapped.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < wrapped.length(); i++) if (wrapped.charAt(i) == '\n') n++;
        return n;
    }

    private static String wrapParagraph(String s, float maxWidth, Measurer m) {
        if (s.isEmpty() || m.width(s) <= maxWidth) return s;
        // atoms: [start, end) pieces that are never split (a word, or one Chinese character with its punctuation)
        List<int[]> atoms = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            if (s.charAt(i) == ' ') { i++; continue; }
            int start = i;
            while (i < n && OPENING.indexOf(s.charAt(i)) >= 0) i++;
            if (i < n && isWordChar(s.charAt(i))) {
                while (i < n && isWordChar(s.charAt(i))) i++;
            } else if (i < n && s.charAt(i) != ' ' && CLOSING.indexOf(s.charAt(i)) < 0) {
                i++;
            }
            while (i < n && CLOSING.indexOf(s.charAt(i)) >= 0) i++;
            if (i == start) i++;
            atoms.add(new int[]{start, i});
        }
        int count = atoms.size();
        if (count < 2) return s;
        float[] gap = new float[count]; // gap[k] = how bad it is to start a line at atom k
        for (int k = 1; k < count; k++) gap[k] = penalty(s, atoms.get(k - 1), atoms.get(k));

        float[] best = new float[count + 1];
        int[] from = new int[count + 1];
        for (int j = 1; j <= count; j++) {
            best[j] = Float.MAX_VALUE;
            for (int k = j - 1; k >= 0; k--) {
                float w = m.width(s.substring(atoms.get(k)[0], atoms.get(j - 1)[1]));
                if (w > maxWidth && k < j - 1) break; // only grows as k moves left
                float slack = Math.max(0, 1 - w / maxWidth);
                float cost = best[k] + LINE + RAGGED * slack * slack;
                if (w > maxWidth) cost += 500;
                if (j == count && k > 0 && w < maxWidth * 0.25f) cost += ORPHAN;
                if (j < count) cost += gap[j] * 3;
                if (cost < best[j]) { best[j] = cost; from[j] = k; }
            }
        }
        List<String> lines = new ArrayList<>();
        for (int j = count; j > 0; j = from[j])
            lines.add(0, s.substring(atoms.get(from[j])[0], atoms.get(j - 1)[1]));
        return String.join("\n", lines);
    }

    /** How bad it is to start a new line between two neighbouring atoms. */
    private static float penalty(String s, int[] a, int[] b) {
        // the last character that is not a closing quote or bracket decides: "……连天。”" ends a clause
        int e = a[1] - 1;
        while (e > a[0] && CLOSING_QUOTES.indexOf(s.charAt(e)) >= 0) e--;
        char end = s.charAt(e);
        if (CLAUSE_END.indexOf(end) >= 0) return 0;
        if (COMMA.indexOf(end) >= 0) return 2;
        if (end == '、') return 4;
        boolean spaced = b[0] > a[1];
        char last = s.charAt(a[1] - 1);
        boolean wordA = isWordChar(last), wordB = isWordChar(firstLetter(s, b));
        if (wordA && wordB) return spaced ? 8 : NEVER;
        if (spaced) return 6;
        if (wordA && isDigit(last)) return 40; // keep "11张" together
        if (wordA || wordB) return 14;
        return 20; // inside a Chinese clause: last resort
    }

    private static char firstLetter(String s, int[] atom) {
        int k = atom[0];
        while (k < atom[1] - 1 && OPENING.indexOf(s.charAt(k)) >= 0) k++;
        return s.charAt(k);
    }

    private static boolean isDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= '０' && c <= '９');
    }

    private static boolean isWordChar(char c) {
        return (c < 0x80 && Character.isLetterOrDigit(c)) || (c >= '０' && c <= '９') || GLUE.indexOf(c) >= 0;
    }
}
