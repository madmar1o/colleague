package com.mad.edt.colleague.agent;

/**
 * Единственное проверяемое исключение контракта инструментов плагина.
 *
 * <p>Заменяет размытый {@code throws Exception} в интерфейсах {@code IToolExecutor},
 * {@code ToolBridge}, {@code McpCatalog.Discoverer} и {@code ReloadableRegistry.Loader}:
 * вызывающий код знает, что ждёт сбой инструмента, а не произвольная ошибка. Причина
 * сбоя сохраняется в {@code cause} и попадает в журнал через {@code ColleagueLog}.</p>
 */
public class ToolExecutionException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Сбой с текстом ошибки.
     *
     * @param theMessage сообщение о том, что не удалось выполнить
     */
    public ToolExecutionException(final String theMessage) {
        super(theMessage);
    }

    /**
     * Сбой с текстом ошибки и сохранённой причиной.
     *
     * @param theMessage сообщение о том, что не удалось выполнить
     * @param theCause первоисточник сбоя, не {@code null} в типичных случаях
     */
    public ToolExecutionException(final String theMessage, final Throwable theCause) {
        super(theMessage, theCause);
    }
}
