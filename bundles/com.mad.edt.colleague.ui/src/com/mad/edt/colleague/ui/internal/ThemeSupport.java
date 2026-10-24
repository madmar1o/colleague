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
        final Thread worker = new Thread(() -> {
            while(true) {
                try {
                    Thread.sleep(2000L);
                } catch (final InterruptedException ex) {
                    return;
                }
                if(theAnchor.isDisposed() || display.isDisposed()) {
                    return;
                }
                display.asyncExec(() -> {
                    if(theAnchor.isDisposed()) {
                        return;
                    }
                    final String palette = pagePalette(theAnchor).toString();
                    if(palette.equals(last.get())) {
                        return;
                    }
                    last.set(palette);
                    if(!theAnchor.isDisposed()) {
                        theOnChange.accept(JsonParser.parseString(palette).getAsJsonObject());
                    }
                });
            }
        }, "colleague-palette-watch");
        worker.setDaemon(true);
        worker.start();
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
        final RGB bg = background.getRGB();
        final RGB fg = foreground.getRGB();
        final boolean dark = luminance(bg) < 128;

        final RGB accent = dark ? new RGB(93, 138, 189) : new RGB(42, 100, 158);
        final RGB toward = dark ? new RGB(255, 255, 255) : new RGB(0, 0, 0);
        final RGB panel = mix(bg, toward, dark ? 0.06 : 0.10);
        final RGB panel2 = mix(bg, toward, dark ? 0.12 : 0.18);
        final RGB line = mix(bg, fg, 0.28);
        final RGB muted = mix(fg, bg, 0.45);
        final RGB user = mix(bg, accent, dark ? 0.35 : 0.22);
        final RGB answer = panel;
        final RGB error = mix(bg, new RGB(200, 90, 90), dark ? 0.28 : 0.35);
        final RGB chip = mix(bg, accent, 0.18);
        final RGB code = mix(bg, toward, dark ? 0.18 : 0.06);

        final JsonObject palette = new JsonObject();
        palette.addProperty("theme", dark ? "dark" : "light");
        palette.addProperty("bg", toHex(bg));
        palette.addProperty("panel", toHex(panel));
        palette.addProperty("panel2", toHex(panel2));
        palette.addProperty("text", toHex(fg));
        palette.addProperty("muted", toHex(muted));
        palette.addProperty("accent", toHex(accent));
        palette.addProperty("user", toHex(user));
        palette.addProperty("answer", toHex(answer));
        palette.addProperty("error", toHex(error));
        palette.addProperty("line", toHex(line));
        palette.addProperty("chip", toHex(chip));
        palette.addProperty("code", toHex(code));
        return palette;
    }

    private static double luminance(final RGB theColor) {
        return (theColor.red * 299D + theColor.green * 587D + theColor.blue * 114D) / 1000D;
    }

    private static RGB mix(final RGB theBase, final RGB theTarget, final double theFactor) {
        final double rest = 1.0D - theFactor;
        return new RGB(
            clamp((int)Math.round(theBase.red * rest + theTarget.red * theFactor)),
            clamp((int)Math.round(theBase.green * rest + theTarget.green * theFactor)),
            clamp((int)Math.round(theBase.blue * rest + theTarget.blue * theFactor)));
    }

    private static int clamp(final int theValue) {
        return Math.max(0, Math.min(255, theValue));
    }

    private static String toHex(final RGB theColor) {
        return String.format("#%02x%02x%02x", Integer.valueOf(theColor.red),
            Integer.valueOf(theColor.green), Integer.valueOf(theColor.blue));
    }
}
