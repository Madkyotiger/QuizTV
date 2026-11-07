package com.gathin.tvhome.gate;

import org.junit.Test;
import static org.junit.Assert.*;

public class QuizIntroTest {
    @Test public void aNewRoundNeedsWelcome() {
        QuizIntro intro = new QuizIntro();
        assertTrue(intro.shouldShow(100));
        assertEquals(5000, intro.remainingMs(100));
    }
    @Test public void countsDownForFiveSeconds() {
        QuizIntro intro = new QuizIntro(); intro.start(100);
        assertEquals(5000, intro.remainingMs(100));
        assertEquals(4000, intro.remainingMs(1100));
        assertTrue(intro.shouldShow(5099));
        assertFalse(intro.shouldShow(5100));
    }
    @Test public void recreationDoesNotRestartCountdown() {
        QuizIntro intro = new QuizIntro(); intro.start(100); intro.start(2100);
        assertEquals(3000, intro.remainingMs(2100));
    }
    @Test public void pressingStartSkipsCountdown() {
        QuizIntro intro = new QuizIntro(); intro.start(100); intro.complete();
        assertFalse(intro.shouldShow(101));
    }
    @Test public void nextQuestionDoesNotReplayWelcome() {
        QuizIntro intro = new QuizIntro(); intro.start(100);
        assertFalse(intro.shouldShow(5100));
        intro.start(6000);
        assertFalse(intro.shouldShow(6000));
    }
    @Test public void nextRoundGetsItsOwnWelcome() {
        QuizIntro old = new QuizIntro(); old.start(100); old.complete();
        assertFalse(old.shouldShow(6000));
        assertTrue(new QuizIntro().shouldShow(6000));
    }
    @Test public void aClockRegressionCannotIncreasePastFiveSeconds() {
        QuizIntro intro = new QuizIntro(); intro.start(100);
        assertEquals(5000, intro.remainingMs(50));
    }
    @Test public void expiredCountdownStaysAtZero() {
        QuizIntro intro = new QuizIntro(); intro.start(100);
        assertEquals(0, intro.remainingMs(9000));
    }
    @Test public void greetingIsPlayedOnlyOncePerRound() {
        QuizIntro intro = new QuizIntro(); intro.start(100);
        assertTrue(intro.claimAudio());
        intro.start(1000);
        assertFalse(intro.claimAudio());
    }
    @Test public void completedIntroCannotStartAudio() {
        QuizIntro intro = new QuizIntro(); intro.complete();
        assertFalse(intro.claimAudio());
    }
    @Test public void newRoundCanPlayGreetingAgain() {
        QuizIntro intro = new QuizIntro(); assertTrue(intro.claimAudio());
        intro.complete();
        assertTrue(new QuizIntro().claimAudio());
    }
}
