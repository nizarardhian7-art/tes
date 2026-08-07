package com.termux.app.themes;

public class ThemePreset {

    public final String name;
    public final String backgroundColor;
    public final String foregroundColor;
    public final String cursorColor;
    public final String[] ansiColors;

    public ThemePreset(String name, String backgroundColor, String foregroundColor, String cursorColor, String[] ansiColors) {
        this.name = name;
        this.backgroundColor = backgroundColor;
        this.foregroundColor = foregroundColor;
        this.cursorColor = cursorColor;
        this.ansiColors = ansiColors;
    }

    public static ThemePreset[] getPresets() {
        return new ThemePreset[]{
            // 1. Dracula
            new ThemePreset(
                "Dracula",
                "#282a36", "#f8f8f2", "#f8f8f2",
                new String[]{
                    "#21222c", "#ff5555", "#50fa7b", "#f1fa8c",
                    "#bd93f9", "#ff79c6", "#8be9fd", "#f8f8f2",
                    "#6272a4", "#ff6e6e", "#69ff94", "#ffffa5",
                    "#d6acff", "#ff92df", "#a4ffff", "#ffffff"
                }
            ),
            // 2. One Dark Pro
            new ThemePreset(
                "One Dark Pro",
                "#1e2227", "#abb2bf", "#528bff",
                new String[]{
                    "#1e2227", "#e06c75", "#98c379", "#d19a66",
                    "#61afef", "#c678dd", "#56b6c2", "#abb2bf",
                    "#5c6370", "#e06c75", "#98c379", "#d19a66",
                    "#61afef", "#c678dd", "#56b6c2", "#ffffff"
                }
            ),
            // 3. Tokio Night
            new ThemePreset(
                "Tokio Night",
                "#1a1b26", "#c0caf5", "#c0caf5",
                new String[]{
                    "#15161e", "#f7768e", "#9ece6a", "#e0af68",
                    "#7aa2f7", "#bb9af7", "#7dcfff", "#a9b1d6",
                    "#414868", "#f7768e", "#9ece6a", "#e0af68",
                    "#7aa2f7", "#bb9af7", "#7dcfff", "#c0caf5"
                }
            ),
            // 4. Monokai Pro
            new ThemePreset(
                "Monokai Pro",
                "#2d2a2e", "#fcfcfa", "#fcfcfa",
                new String[]{
                    "#2d2a2e", "#ff6188", "#a9dc76", "#ffd866",
                    "#fc9867", "#ab9df2", "#78dce8", "#fcfcfa",
                    "#727072", "#ff6188", "#a9dc76", "#ffd866",
                    "#fc9867", "#ab9df2", "#78dce8", "#ffffff"
                }
            ),
            // 5. Nord
            new ThemePreset(
                "Nord",
                "#2e3440", "#d8dee9", "#d8dee9",
                new String[]{
                    "#3b4252", "#bf616a", "#a3be8c", "#ebcb8b",
                    "#81a1c1", "#b48ead", "#88c0d0", "#e5e9f0",
                    "#4c566a", "#bf616a", "#a3be8c", "#ebcb8b",
                    "#81a1c1", "#b48ead", "#8fbcbb", "#eceff4"
                }
            ),
            // 6. Cyberpunk Neon
            new ThemePreset(
                "Cyberpunk Neon",
                "#0d0f18", "#00ff66", "#00ff66",
                new String[]{
                    "#0d0f18", "#ff0055", "#00ff66", "#ffe600",
                    "#00bfff", "#ff00ea", "#00ffff", "#ffffff",
                    "#2a2e3d", "#ff3300", "#33ff88", "#ffff33",
                    "#33ccff", "#ff33f0", "#33ffff", "#ffffff"
                }
            )
        };
    }
}