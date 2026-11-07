package com.gathin.tvhome.gate;

import org.junit.Test;
import static org.junit.Assert.*;

public class QuizTextTest {
    @Test public void keepsComparisonPhraseTogether() {
        String q = "安安有57张贴纸，比小刚少11张。小刚有几张？";
        String out = QuizText.breakChinese(q, 16);
        assertEquals("安安有57张贴纸，\n比小刚少11张。小刚有几张？", out);
        assertFalse(out.contains("比小刚\n"));
        assertEquals(q, out.replace("\n", ""));
    }
    @Test public void narrowLinesBreakOnlyAfterPunctuation() {
        String out = QuizText.breakChinese("安安有57张贴纸，比小刚少11张。小刚有几张？", 9);
        assertEquals("安安有57张贴纸，\n比小刚少11张。\n小刚有几张？", out);
    }
    @Test public void shortTextUnchanged() {
        assertEquals("8×2=？", QuizText.breakChinese("8×2=？", 16));
    }
    @Test public void englishUnchanged() {
        String s = "Which word is right? I go to school ___ foot.";
        assertEquals(s, QuizText.breakChinese(s, 16));
    }
    @Test public void existingNewlineAndSingleClauseUnchanged() {
        assertEquals("一\n二三四五六七八九十十一十二十三十四十五十六十七", QuizText.breakChinese("一\n二三四五六七八九十十一十二十三十四十五十六十七", 16));
        String one = "床前明月光疑是地上霜举头望明月低头思故乡呀";
        assertEquals(one, QuizText.breakChinese(one, 16));
    }
    @Test public void closingQuoteStaysWithItsPunctuation() {
        String q = "“天连水尾水连天。”下一句是什么？请选出正确的一句";
        String out = QuizText.breakChinese(q, 12);
        assertTrue(out.startsWith("“天连水尾水连天。”\n"));
        assertEquals(q, out.replace("\n", ""));
    }
}
