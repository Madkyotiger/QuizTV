package com.gathin.tvhome.gate;

/**
 * Low-glare colours for all gate pages. Two themes (2026-11-07: both, picked at random per quiz round):
 * "star" = plush bunny under a plum night sky, "forest" = picture-book fox and panda in a pine-teal night forest.
 * Backgrounds live in assets/theme/&lt;id&gt;/{quiz,intro,rest,limit}.webp.
 */
final class GatePalette {
    private GatePalette() {}
    static final String[] THEMES = {"star", "forest"};

    static String id;
    static int BACKGROUND, PANEL, BUTTON, FOCUS, BORDER, TEXT, MUTED, LABEL_TEXT;
    static int PINK, MINT, SKY, AMBER, STAR, SUCCESS_SURFACE, ERROR_SURFACE, ERROR_TEXT;

    static { use("star"); }

    static boolean known(String theme) {
        for (String t : THEMES) if (t.equals(theme)) return true;
        return false;
    }

    static void use(String theme) {
        if (!known(theme)) theme = "star";
        id = theme;
        if (theme.equals("forest")) {
            BACKGROUND = 0xFF122D29; PANEL = 0xFF1F3D37; BUTTON = 0xFF233A31; FOCUS = 0xFF35513F;
            BORDER = 0xFF71907A; TEXT = 0xFFF2E8D2; MUTED = 0xFFC6D0BC; LABEL_TEXT = 0xFF15231E;
            PINK = 0xFFF3BC68; MINT = 0xFF9FD8AE; SKY = 0xFFAFD3C2; AMBER = 0xFFEFC98A; STAR = 0xFFF5CD6A;
            SUCCESS_SURFACE = 0xFF24483B; ERROR_SURFACE = 0xFF4B3030; ERROR_TEXT = 0xFFF3AE9C;
        } else {
            BACKGROUND = 0xFF211C3D; PANEL = 0xFF2F2856; BUTTON = 0xFF2A2350; FOCUS = 0xFF40366F;
            BORDER = 0xFF7468A6; TEXT = 0xFFF4EAD8; MUTED = 0xFFCFC5DE; LABEL_TEXT = 0xFF221A33;
            PINK = 0xFFF5B08F; MINT = 0xFF93D6BA; SKY = 0xFFB4C0F2; AMBER = 0xFFF2C66B; STAR = 0xFFF4CB62;
            SUCCESS_SURFACE = 0xFF234239; ERROR_SURFACE = 0xFF4A2741; ERROR_TEXT = 0xFFF5A9B0;
        }
    }

    /** Same colour with a new alpha (0-255), e.g. a slightly see-through question card over the artwork. */
    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
