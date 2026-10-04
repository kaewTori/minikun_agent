package com.minikun.presentation;

import java.awt.Color;

enum PresentationTheme {
    JAPANESE(new Color(0xF5F1E9), new Color(0x252623), new Color(0xB34939), new Color(0x827E73),
            new Color(0xDFD8CC)),
    PAPER(new Color(0xFAF9F5), new Color(0x262827), new Color(0x476C63), new Color(0x777D78),
            new Color(0xE5E7E1)),
    OCEAN(new Color(0xF0F5F7), new Color(0x182E3B), new Color(0x147D91), new Color(0x637984),
            new Color(0xD8E6E9)),
    MIDNIGHT(new Color(0x111A23), new Color(0xF2F5F5), new Color(0x6BD3C1), new Color(0xB2C0C4),
            new Color(0x293844));

    final Color background;
    final Color ink;
    final Color accent;
    final Color muted;
    final Color divider;

    PresentationTheme(Color background, Color ink, Color accent, Color muted, Color divider) {
        this.background = background;
        this.ink = ink;
        this.accent = accent;
        this.muted = muted;
        this.divider = divider;
    }

    static PresentationTheme named(String name) {
        return switch (name) {
            case "japanese" -> JAPANESE;
            case "ocean" -> OCEAN;
            case "midnight" -> MIDNIGHT;
            default -> PAPER;
        };
    }
}
