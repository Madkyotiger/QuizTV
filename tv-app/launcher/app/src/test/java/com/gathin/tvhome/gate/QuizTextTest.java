package com.gathin.tvhome.gate;

import org.junit.Test;
import static org.junit.Assert.*;

public class QuizTextTest {
    /** A Chinese character or full-width mark is 1 wide, everything else half: close to the real fonts. */
    private static final QuizText.Measurer M = s -> {
        float w = 0;
        for (int i = 0; i < s.length(); i++) w += s.charAt(i) > 0x2E80 || "“”‘’…—".indexOf(s.charAt(i)) >= 0 ? 1 : .5f;
        return w;
    };
    private static void assertFits(String wrapped, float max) {
        for (String line : wrapped.split("\n")) assertTrue(line, M.width(line) <= max);
    }

    @Test public void keepsComparisonPhraseTogetherAndTheQuestionOnItsOwnLine() {
        String q = "安安有57张贴纸，比小刚少11张。小刚有几张？";
        String out = QuizText.wrap(q, 16, M);
        assertEquals("安安有57张贴纸，比小刚少11张。\n小刚有几张？", out);
        assertEquals(q, out.replace("\n", ""));
    }
    @Test public void narrowLinesBreakOnlyAfterPunctuation() {
        String out = QuizText.wrap("安安有57张贴纸，比小刚少11张。小刚有几张？", 9, M);
        assertEquals("安安有57张贴纸，\n比小刚少11张。\n小刚有几张？", out);
    }
    @Test public void shortTextUnchanged() {
        assertEquals("8×2=？", QuizText.wrap("8×2=？", 16, M));
        assertEquals(1, QuizText.lineCount("8×2=？"));
    }
    @Test public void englishBreaksBetweenSentencesNotInsideWords() {
        String out = QuizText.wrap("Which word is right? I go to school ___ foot.", 16, M);
        assertEquals("Which word is right?\nI go to school ___ foot.", out);
    }
    @Test public void mixedSentenceKeepsTheEnglishPhraseWhole() {
        // seen on the TV 2026-11-07: "on" ended line one and "foot" started line two
        String out = QuizText.wrap("We go to the shop on foot. 句中 on foot 表示怎样去商店？", 17, M);
        assertEquals("We go to the shop on foot.\n句中 on foot 表示怎样去商店？", out);
    }
    @Test public void aSentenceStaysWholeEvenIfThatLeavesAShortLine() {
        String out = QuizText.wrap("I put on my coat. 句中的 my 告诉我们，外套是谁的？", 17, M);
        assertEquals("I put on my coat.\n句中的 my 告诉我们，外套是谁的？", out);
    }
    @Test public void noSingleCharacterLeftOnTheLastLine() {
        // seen on the TV 2026-11-07: "行" ended a line and "驶？" stood alone on the next
        String q = "只按短文回答：In this town, buses drive on the left side of the road. 公交车在哪一侧行驶？";
        String out = QuizText.wrap(q, 17, M);
        assertFits(out, 17);
        String[] lines = out.split("\n");
        assertTrue(out, lines[lines.length - 1].length() >= 4);
        assertFalse(out, out.contains("行\n驶"));
        assertEquals(q.replace(" ", ""), out.replace("\n", "").replace(" ", ""));
    }
    @Test public void aLongClauseWithoutPunctuationIsSplitEvenly() {
        String one = "床前明月光疑是地上霜举头望明月低头思故乡呀";
        String out = QuizText.wrap(one, 16, M);
        assertEquals(2, QuizText.lineCount(out));
        assertFits(out, 16);
        String[] lines = out.split("\n");
        assertTrue(out, Math.abs(lines[0].length() - lines[1].length()) <= 3);
    }
    @Test public void existingNewlineIsKept() {
        String out = QuizText.wrap("一\n二三四五六七八九十十一十二十三十四十五十六十七", 16, M);
        assertTrue(out.startsWith("一\n"));
        assertFits(out, 16);
    }
    @Test public void closingQuoteStaysWithItsPunctuation() {
        String q = "“天连水尾水连天。”下一句是什么？请选出正确的一句";
        String out = QuizText.wrap(q, 12, M);
        assertTrue(out, out.startsWith("“天连水尾水连天。”\n"));
        assertEquals(q, out.replace("\n", ""));
    }
    @Test public void numberStaysWithItsUnitAndExpressionsAreNeverSplit() {
        String out = QuizText.wrap("小红和乐乐都要读55页书小红读了18页乐乐读了19页谁剩下的多", 9, M);
        assertFits(out, 9);
        for (String bad : new String[]{"55\n页", "18\n页", "19\n页", "5\n5", "1\n8"}) assertFalse(out, out.contains(bad));
        assertEquals("先算\n52－15＝37", QuizText.wrap("先算 52－15＝37", 6, M));
    }
    @Test public void aWordWiderThanTheLineStandsAlone() {
        assertEquals("abcdefghijkl\nmn", QuizText.wrap("abcdefghijkl mn", 4, M));
    }
    @Test public void eachSentenceGetsItsOwnLineRatherThanSplittingOne() {
        // 2 lines would have to split "安安读了/25页"; three whole sentences read better
        String out = QuizText.wrap("安安和乐乐都要读81页书。安安读了25页，乐乐读了72页。谁剩下的多？", 16.4f, M);
        assertEquals("安安和乐乐都要读81页书。\n安安读了25页，乐乐读了72页。\n谁剩下的多？", out);
    }
    @Test public void clockIsTwelveHourWithThePartOfDay() {
        assertEquals("上午 8:30", QuizText.clock("08:30"));
        assertEquals("下午 12:00", QuizText.clock("12:00"));
        assertEquals("下午 1:00", QuizText.clock("13:00"));
        assertEquals("下午 5:30", QuizText.clock("17:30"));
        assertEquals("晚上 7:00", QuizText.clock("19:00"));
    }
    @Test public void emptyAndNullAreSafe() {
        assertNull(QuizText.wrap(null, 10, M));
        assertEquals("", QuizText.wrap("", 10, M));
        assertEquals(0, QuizText.lineCount(""));
    }
}
