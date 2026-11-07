package com.gathin.tvhome.gate;

/** Pure text helpers for the quiz card (unit tested). */
final class QuizText {
    private QuizText() {}

    private static final String BREAK_AFTER = "，。？！；：";

    /**
     * Breaks a long Chinese question only after punctuation, so a phrase like "比小刚少11张" is never split
     * across lines. Lines are filled greedily up to maxChars; text without CJK or already containing a
     * newline is returned unchanged, and a single clause longer than maxChars is left for the TextView to wrap.
     */
    static String breakChinese(String s, int maxChars) {
        if (s == null || s.length() <= maxChars || s.indexOf('\n') >= 0 || !hasCjk(s)) return s;
        java.util.List<String> parts = new java.util.ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (BREAK_AFTER.indexOf(s.charAt(i)) >= 0) {
                int end = i + 1;
                while (end < s.length() && (s.charAt(end) == '”' || s.charAt(end) == '’' || s.charAt(end) == '）')) end++;
                parts.add(s.substring(start, end));
                start = end;
                i = end - 1;
            }
        }
        if (start < s.length()) parts.add(s.substring(start));
        if (parts.size() < 2) return s;
        StringBuilder out = new StringBuilder();
        int line = 0;
        for (String p : parts) {
            if (line > 0 && line + p.length() > maxChars) {
                out.append('\n');
                line = 0;
            }
            out.append(p);
            line += p.length();
        }
        return out.toString();
    }

    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }
}
