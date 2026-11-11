package com.gathin.tvhome.gate;

import org.junit.Test;
import static org.junit.Assert.*;

/** Dark backgrounds must stay dark even for focused, correct and incorrect answers — in every theme. */
public class GatePaletteTest {
    private static double channel(int c) {
        double s = c / 255.0;
        return s <= .04045 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4);
    }
    private static double luminance(int rgb) {
        return .2126 * channel((rgb >> 16) & 255) + .7152 * channel((rgb >> 8) & 255) + .0722 * channel(rgb & 255);
    }
    private static double contrast(int a, int b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }
    private static int[] surfaces() {
        return new int[]{GatePalette.BACKGROUND, GatePalette.PANEL, GatePalette.BUTTON,
                GatePalette.FOCUS, GatePalette.SUCCESS_SURFACE, GatePalette.ERROR_SURFACE};
    }
    @Test public void allLargeSurfacesRemainDarkAndOpaque() {
        for (String t : GatePalette.THEMES) {
            GatePalette.use(t);
            for (int c : surfaces()) {
                assertEquals(t, 255, (c >>> 24));
                assertTrue(t + " bright large surface", luminance(c) < .10);
            }
        }
    }
    @Test public void mainTextIsReadableOnAllSurfaces() {
        for (String t : GatePalette.THEMES) {
            GatePalette.use(t);
            for (int c : surfaces()) assertTrue(t + " main text contrast", contrast(GatePalette.TEXT, c) >= 4.5);
        }
    }
    @Test public void explanationsAndCountdownsRemainReadable() {
        for (String t : GatePalette.THEMES) {
            GatePalette.use(t);
            for (int c : new int[]{GatePalette.BACKGROUND, GatePalette.PANEL})
                assertTrue(t + " secondary text contrast", contrast(GatePalette.MUTED, c) >= 4.5);
        }
    }
    @Test public void subjectPillsUseDarkLettersOnPastelColours() {
        for (String t : GatePalette.THEMES) {
            GatePalette.use(t);
            for (int c : new int[]{GatePalette.SKY, GatePalette.MINT, GatePalette.AMBER})
                assertTrue(t + " subject label contrast", contrast(GatePalette.LABEL_TEXT, c) >= 4.5);
        }
    }
    @Test public void accentFeedbackAndRemoteFocusStayVisible() {
        for (String t : GatePalette.THEMES) {
            GatePalette.use(t);
            for (int c : new int[]{GatePalette.ACCENT, GatePalette.MINT, GatePalette.SKY,
                    GatePalette.STAR, GatePalette.ERROR_TEXT}) {
                assertTrue(t + " accent text contrast", contrast(c, GatePalette.PANEL) >= 4.5);
                assertTrue(t + " focus outline contrast", contrast(c, GatePalette.FOCUS) >= 3.0);
            }
        }
    }
    @Test public void unknownThemeFallsBackToStar() {
        GatePalette.use("nope");
        assertEquals("star", GatePalette.id);
        for (String t : GatePalette.THEMES) {
            GatePalette.use(t);
            assertEquals(t, GatePalette.id);
            assertFalse(t, GatePalette.MASCOT.isEmpty());
        }
        GatePalette.use("star");
    }
    @Test public void aNewRoundNeverRepeatsTheThemeJustSeen() {
        int n = GatePalette.THEMES.length;
        assertTrue(n >= 2);
        for (String previous : GatePalette.THEMES) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int pick = 0; pick < n - 1; pick++) {
                String next = GateStore.nextTheme(previous, pick);
                assertTrue(GatePalette.known(next));
                assertNotEquals(previous, next);
                seen.add(next);
            }
            assertEquals("every other theme can come up", n - 1, seen.size());
        }
        assertTrue(GatePalette.known(GateStore.nextTheme("gone-theme", 1)));
    }
    @Test public void withAlphaKeepsColour() {
        assertEquals(0x80123456, GatePalette.withAlpha(0xFF123456, 0x80));
    }
}
