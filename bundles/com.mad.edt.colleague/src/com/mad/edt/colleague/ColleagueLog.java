package com.mad.edt.colleague;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

/**
 * Единственный лог-фасад плагина: весь код пишет сюда, а не в {@code System.out/err}.
 *
 * <p>Привязка к платформенному журналу EDT выполняется один раз из активатора
 * ({@code ColleagueUIPlugin.start} -> {@link #bind(ILog)}). До привязки и в plain-JVM
 * тестах (без OSGi) сообщения уходят в {@code java.util.logging} — консольные потоки
 * фасад не использует никогда.</p>
 */
public final class ColleagueLog {

    /** Идентификатор бандла, от имени которого пишутся записи в журнал платформы. */
    private static final String PLUGIN_ID = "com.mad.edt.colleague";

    /** Платформенный журнал; {@code null} пока активатор не выполнил привязку. */
    private static volatile ILog ourLog;

    /** Резервный канал для среды без OSGi (юнит-тесты, headless-прогоны). */
    private static final Logger JUL = Logger.getLogger(PLUGIN_ID);

    private ColleagueLog() {
        // фасад статический, экземпляр не создаётся
    }

    /**
     * Привязывает платформенный журнал. Вызывается только активатором UI-бандла
     * при запуске; повторная привязка заменяет канал.
     *
     * @param theLog журнал бандла, полученный из {@code Platform.getLog(bundle)}
     */
    public static void bind(final ILog theLog) {
        ourLog = theLog;
    }

    /**
     * Информационная запись.
     *
     * @param theMessage текст сообщения
     */
    public static void info(final String theMessage) {
        write(IStatus.INFO, theMessage, null);
    }

    /**
     * Предупреждение.
     *
     * @param theMessage текст сообщения
     */
    public static void warn(final String theMessage) {
        write(IStatus.WARNING, theMessage, null);
    }

    /**
     * Ошибка без причины.
     *
     * @param theMessage текст сообщения
     */
    public static void error(final String theMessage) {
        write(IStatus.ERROR, theMessage, null);
    }

    /**
     * Ошибка с сохранением причины.
     *
     * @param theMessage текст сообщения
     * @param theCause исключение-первопричина, сохраняется в записи журнала
     */
    public static void error(final String theMessage, final Throwable theCause) {
        write(IStatus.ERROR, theMessage, theCause);
    }

    private static void write(final int theSeverity, final String theMessage, final Throwable theCause) {
        final ILog platformLog = ourLog;
        if (platformLog != null) {
            platformLog.log(new Status(theSeverity, PLUGIN_ID, theMessage, theCause));
            return;
        }
        JUL.log(toJulLevel(theSeverity), theMessage, theCause);
    }

    private static Level toJulLevel(final int theSeverity) {
        if (theSeverity == IStatus.ERROR) {
            return Level.SEVERE;
        }
        if (theSeverity == IStatus.WARNING) {
            return Level.WARNING;
        }
        return Level.INFO;
    }
}
