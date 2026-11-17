package com.mad.edt.colleague.ui.internal;

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Maps the workbench widget palette (whatever theme the user selected in EDT)
 * onto the CSS custom properties of the bundled pages, so the embedded chat
 * and contacts always match the surrounding light or dark skin.
 */
final class ThemeSupport {

    private ThemeSupport() {
        // utility
    }

    /**
     * Builds an SWT color equal to one entry (e.g. {@code bg}) of a palette so
     * that native widgets can be filled to match the surrounding theme colors.
     *
     * @param theDisplay display to allocate the color on
     * @param thePalette palette produced by {@link #pagePalette(Control)}
     * @param theKey palette entry name
     * @return always a fresh independent color the caller must dispose
     */
    static Color pageColor(final Display theDisplay, final JsonObject thePalette,
        final String theKey) {
        final String hex = thePalette.has(theKey) && !thePalette.get(theKey).isJsonNull()
            ? thePalette.get(theKey).getAsString() : "";
        if(hex.length() == 7 && hex.charAt(0) == '#') {
            try {
                return new Color(theDisplay,
                    Integer.parseInt(hex.substring(1, 3), 16),
                    Integer.parseInt(hex.substring(3, 5), 16),
                    Integer.parseInt(hex.substring(5, 7), 16));
            } catch (final NumberFormatException ex) {
                // fall through to the widget default
            }
        }
        final RGB fallback = theDisplay
            .getSystemColor(SWT.COLOR_WIDGET_BACKGROUND).getRGB();
        return new Color(theDisplay, fallback.red, fallback.green, fallback.blue);
    }

    /**
     * Polls the widget palette of a control twice a second from a daemon
     * thread and reports every actual change on the UI thread. Theme switches
     * in the workbench give no universal SWT notification, so periodic
     * comparison is the only reliable watcher; the thread stops by itself once
     * the control is disposed.
     *
     * @param theAnchor control whose palette is watched
     * @param theOnChange receives the new palette on the UI thread
     */
    static void watchPalette(final Control theAnchor, final Consumer<JsonObject> theOnChange) {
        final Display display = theAnchor.getDisplay();
        final java.util.concurrent.atomic.AtomicReference<String> last =
            new java.util.concurrent.atomic.AtomicReference<>("");
        final Thread worker = new Thread(
            () -> paletteWatchLoop(theAnchor, display, last, theOnChange),
            "colleague-palette-watch");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Compares the palette of the anchor every two seconds and reports every
     * actual change on the UI thread; stops by itself once the control or the
     * display is gone.
     *
     * @param theAnchor control whose palette is watched
     * @param theDisplay display of the anchor
     * @param theLast last palette text seen, empty before the first round
     * @param theOnChange receives the new palette on the UI thread
     */
    private static void paletteWatchLoop(final Control theAnchor, final Display theDisplay,
        final java.util.concurrent.atomic.AtomicReference<String> theLast,
        final Consumer<JsonObject> theOnChange) {
        while(true) {
            try {
                Thread.sleep(2000L);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            if(theAnchor.isDisposed() || theDisplay.isDisposed()) {
                return;
            }
            theDisplay.asyncExec(() -> {
                if(theAnchor.isDisposed()) {
                    return;
                }
                final String palette = pagePalette(theAnchor).toString();
                if(palette.equals(theLast.get())) {
                    return;
                }
                theLast.set(palette);
                if(!theAnchor.isDisposed()) {
                    theOnChange.accept(JsonParser.parseString(palette).getAsJsonObject());
                }
            });
        }
    }

    /**
     * Builds the palette object understood by the pages: a {@code theme} flag
     * plus {@code bg, panel, panel2, text, muted, accent, user, answer,
     * error, line, chip, code} as {@code #rrggbb} strings. The base colors are
     * taken from the closest themed ancestor of the anchor: workbench themes
     * color the view container itself, while plain composites built by the
     * plug-in still report neutral system defaults, which would otherwise
     * produce a light page inside a dark workbench.
     *
     * @param theAnchor control whose themed background and foreground carry
     *            the workbench theme
     * @return palette JSON ready to push to the page
     */
    static JsonObject pagePalette(final Control theAnchor) {
        final BaseColors base = themedColors(theAnchor);
        final Palette palette = new Palette(base);
        final JsonObject json = new JsonObject();
        json.addProperty("theme", palette.dark ? "dark" : "light");
        json.addProperty("bg", toHex(palette.bg));
        json.addProperty("panel", toHex(palette.panel));
        json.addProperty("panel2", toHex(palette.panel2));
        json.addProperty("text", toHex(palette.fg));
        json.addProperty("muted", toHex(palette.muted));
        json.addProperty("accent", toHex(palette.accent));
        json.addProperty("user", toHex(palette.user));
        json.addProperty("answer", toHex(palette.answer));
        json.addProperty("error", toHex(palette.error));
        json.addProperty("line", toHex(palette.line));
        json.addProperty("chip", toHex(palette.chip));
        json.addProperty("code", toHex(palette.code));
        return json;
    }

    /** Themed background and foreground of an anchor, as plain RGB. */
    private static final class BaseColors {

        private final RGB background;

        private final RGB foreground;

        /**
         * @param theBackground themed background
         * @param theForeground themed foreground
         */
        BaseColors(final RGB theBackground, final RGB theForeground) {
            background = theBackground;
            foreground = theForeground;
        }
    }

    /** All page colors derived from the base pair. */
    private static final class Palette {

        private final boolean dark;

        private final RGB bg;

        private final RGB fg;

        private final RGB accent;

        private final RGB panel;

        private final RGB panel2;

        private final RGB line;

        private final RGB muted;

        private final RGB user;

        private final RGB answer;

        private final RGB error;

        private final RGB chip;

        private final RGB code;

        /**
         * @param theBase themed base pair
         */
        Palette(final BaseColors theBase) {
            bg = theBase.background;
            fg = theBase.foreground;
            dark = luminance(bg) < 128;
            final RGB toward = dark ? new RGB(255, 255, 255) : new RGB(0, 0, 0);
            accent = dark ? new RGB(93, 138, 189) : new RGB(42, 100, 158);
            panel = mix(bg, toward, dark ? 0.06 : 0.10);
            panel2 = mix(bg, toward, dark ? 0.12 : 0.18);
            line = mix(bg, fg, 0.28);
            muted = mix(fg, bg, 0.45);
            user = mix(bg, accent, dark ? 0.35 : 0.22);
            answer = panel;
            error = mix(bg, new RGB(200, 90, 90), dark ? 0.28 : 0.35);
            chip = mix(bg, accent, 0.18);
            code = mix(bg, toward, dark ? 0.18 : 0.06);
        }

        /**
         * @param theColor color to weigh
         * @return perceived brightness of the color
         */
        private static double luminance(final RGB theColor) {
            return (theColor.red * 299D + theColor.green * 587D + theColor.blue * 114D) / 1000D;
        }

        /**
         * @param theBase starting color
         * @param theTarget color to move toward
         * @param theFactor share of the target color
         * @return the blended color, channels clamped to the widget range
         */
        private static RGB mix(final RGB theBase, final RGB theTarget, final double theFactor) {
            final double rest = 1.0D - theFactor;
            return new RGB(
                clamp((int)Math.round(theBase.red * rest + theTarget.red * theFactor)),
                clamp((int)Math.round(theBase.green * rest + theTarget.green * theFactor)),
                clamp((int)Math.round(theBase.blue * rest + theTarget.blue * theFactor)));
        }

        /**
         * @param theValue channel value
         * @return the value inside the 0..255 widget range
         */
        private static int clamp(final int theValue) {
            return Math.max(0, Math.min(255, theValue));
        }
    }

    /**
     * Walks the ancestor chain of the anchor to the first control whose
     * colors differ from the neutral widget defaults.
     *
     * @param theAnchor control whose themed background and foreground carry
     *            the workbench theme
     * @return the themed pair, falling back to the system widget colors
     */
    private static BaseColors themedColors(final Control theAnchor) {
        final Display display = theAnchor.getDisplay();
        final RGB neutralBackground = display
            .getSystemColor(SWT.COLOR_WIDGET_BACKGROUND).getRGB();
        final RGB neutralForeground = display
            .getSystemColor(SWT.COLOR_WIDGET_FOREGROUND).getRGB();
        Color themedBackground = null;
        Color themedForeground = null;
        for(Control probe = theAnchor; probe != null; probe = probe.getParent()) {
            final Color background = probe.getBackground();
            final Color foreground = probe.getForeground();
            if(themedBackground == null && background != null
                && !background.getRGB().equals(neutralBackground)) {
                themedBackground = background;
            }
            if(themedForeground == null && foreground != null
                && !foreground.getRGB().equals(neutralForeground)) {
                themedForeground = foreground;
            }
            if(themedBackground != null && themedForeground != null) {
                break;
            }
        }
        final Color background = themedBackground != null ? themedBackground
            : display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
        final Color foreground = themedForeground != null ? themedForeground
            : display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND);
        return new BaseColors(background.getRGB(), foreground.getRGB());
    }

    private static String toHex(final RGB theColor) {
        return String.format("#%02x%02x%02x", Integer.valueOf(theColor.red),
            Integer.valueOf(theColor.green), Integer.valueOf(theColor.blue));
    }
}
