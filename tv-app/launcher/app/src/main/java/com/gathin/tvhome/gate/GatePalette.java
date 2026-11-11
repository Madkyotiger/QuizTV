package com.gathin.tvhome.gate;

/**
 * Low-glare colours for all gate pages. Three themes, one picked per quiz round (never the same twice in a row):
 * "star" = plush bunny under a plum night sky, "forest" = picture-book fox and panda in a pine-teal night forest,
 * "ocean" = paper-cut baby whale and turtle in a navy deep sea.
 * Backgrounds live in assets/theme/&lt;id&gt;/{quiz,intro,rest,limit}.webp.
 */
final class GatePalette {
    private GatePalette() {}
    static final String[] THEMES = {"star", "forest", "ocean"};

    static String id;
    /** The theme's character as an emoji, for the countdown toast. */
    static String MASCOT;
    /** Width (dp) of artwork in the top-right corner that the parents' button must stay clear of (the star theme's moon). */
    static int CORNER_DP;
    static int BACKGROUND, PANEL, BUTTON, FOCUS, BORDER, TEXT, MUTED, LABEL_TEXT;
    /** ACCENT = headlines and the focus ring; MINT / SKY / AMBER = subject pills and "correct". */
    static int ACCENT, MINT, SKY, AMBER, STAR, SUCCESS_SURFACE, ERROR_SURFACE, ERROR_TEXT;

    static { use("star"); }

    static boolean known(String theme) {
        for (String t : THEMES) if (t.equals(theme)) return true;
        return false;
    }

    static void use(String theme) {
        if (!known(theme)) theme = "star";
        id = theme;
        if (theme.equals("forest")) {
            MASCOT = "🦊"; CORNER_DP = 0;
            BACKGROUND = 0xFF122D29; PANEL = 0xFF1F3D37; BUTTON = 0xFF233A31; FOCUS = 0xFF35513F;
            BORDER = 0xFF71907A; TEXT = 0xFFF2E8D2; MUTED = 0xFFC6D0BC; LABEL_TEXT = 0xFF15231E;
            ACCENT = 0xFFF3BC68; MINT = 0xFF9FD8AE; SKY = 0xFFAFD3C2; AMBER = 0xFFEFC98A; STAR = 0xFFF5CD6A;
            SUCCESS_SURFACE = 0xFF24483B; ERROR_SURFACE = 0xFF4B3030; ERROR_TEXT = 0xFFF3AE9C;
        } else if (theme.equals("ocean")) {
            MASCOT = "🐳"; CORNER_DP = 0;
            BACKGROUND = 0xFF0F2137; PANEL = 0xFF1A314D; BUTTON = 0xFF172C46; FOCUS = 0xFF28466A;
            BORDER = 0xFF6387AD; TEXT = 0xFFF2EBDC; MUTED = 0xFFC2D0E0; LABEL_TEXT = 0xFF11202F;
            ACCENT = 0xFFF6B48E; MINT = 0xFF93D9C6; SKY = 0xFFA9CCEF; AMBER = 0xFFF2CB7C; STAR = 0xFFF6D36B;
            SUCCESS_SURFACE = 0xFF1C4A44; ERROR_SURFACE = 0xFF4C2B3B; ERROR_TEXT = 0xFFF6ABA9;
        } else {
            MASCOT = "🐰"; CORNER_DP = 62;
            BACKGROUND = 0xFF211C3D; PANEL = 0xFF2F2856; BUTTON = 0xFF2A2350; FOCUS = 0xFF40366F;
            BORDER = 0xFF7468A6; TEXT = 0xFFF4EAD8; MUTED = 0xFFCFC5DE; LABEL_TEXT = 0xFF221A33;
            ACCENT = 0xFFF5B08F; MINT = 0xFF93D6BA; SKY = 0xFFB4C0F2; AMBER = 0xFFF2C66B; STAR = 0xFFF4CB62;
            SUCCESS_SURFACE = 0xFF234239; ERROR_SURFACE = 0xFF4A2741; ERROR_TEXT = 0xFFF5A9B0;
        }
    }

    /** Same colour with a new alpha (0-255), e.g. a slightly see-through question card over the artwork. */
    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
