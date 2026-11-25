package us.bringardner.parley.mail.admin.log;

import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.core.ParleyLogger;

/**
 * The logger of this application: a {@link ParleyLogger} (console or the {@code parley.log.file}
 * file, written as it always is) that also gives each entry it writes to the {@link LogBuffer} the
 * Logs page shows. The application selects it with the {@code parley.logger} setting
 * ({@link us.bringardner.parley.mail.admin.AdminApp#installLogger()}), so parley-core knows nothing of
 * the page.
 * <p>
 * The level of a logger is, in this order:
 * <ol>
 * <li>the level chosen on the Logs page for the logger's source
 * ({@link LogPageHandler#setSourceLevel}),</li>
 * <li>the one set on its object ({@code setLogLevel}) or by a {@code parley.log.level} setting in
 * its configuration,</li>
 * <li>INFO.</li>
 * </ol>
 * (ParleyLogger alone defaults to ERROR; the page has always shown INFO and above.)
 */
public class AdminLogger extends ParleyLogger {

	/** The level the page shows when nothing else says */
	public static final LogLevel DEFAULT_LEVEL = LogLevel.INFO;

	private volatile String loggerName;
	private volatile boolean explicit;
	private volatile boolean configured;

	@Override
	public void init(String name) {
		super.init(name);
		this.loggerName = name;
		this.explicit = false;
		this.configured = hasLevelSetting(name);
	}

	/** True when a {@code parley.log.level} setting covers the logger's name: its own, a shorter one's, or the general one. */
	private boolean hasLevelSetting(String name) {
		String n = levelName(name);
		while (!n.isEmpty()) {
			if (nonEmpty(getProperty(PROPERTY_LOG_LEVEL + "." + n))) {
				return true;
			}
			int dot = n.lastIndexOf('.');
			n = dot < 0 ? "" : n.substring(0, dot);
		}
		return nonEmpty(getProperty(PROPERTY_LOG_LEVEL));
	}

	private static boolean nonEmpty(String s) {
		return s != null && !s.trim().isEmpty();
	}

	@Override
	public void setLevel(LogLevel level) {
		explicit = true;
		super.setLevel(level);
	}

	@Override
	public LogLevel getLevel() {
		LogBuffer.Level page = LogPageHandler.getSourceLevel(LogBuffer.Source.of(loggerName));
		if (page != null) {
			return page == LogBuffer.Level.TRACE ? LogLevel.DEBUG : LogLevel.parse(page.name(), DEFAULT_LEVEL);
		}
		return explicit || configured ? super.getLevel() : DEFAULT_LEVEL;
	}

	private boolean allows(LogLevel target) {
		LogLevel current = getLevel();
		return current != LogLevel.NONE && current.ordinal() >= target.ordinal();
	}

	@Override
	public boolean isDebugEnabled() {
		return allows(LogLevel.DEBUG);
	}

	@Override
	public boolean isErrorEnabled() {
		return allows(LogLevel.ERROR);
	}

	@Override
	public boolean isInfoEnabled() {
		return allows(LogLevel.INFO);
	}

	@Override
	public boolean isWarnEnabled() {
		return allows(LogLevel.WARN);
	}

	private void buffer(LogBuffer.Level level, String msg, Throwable error) {
		LogBuffer b = LogBuffer.get();
		if (b != null) {
			b.add(level, loggerName == null ? "" : loggerName, msg, error);
		}
	}

	@Override
	public void debug(String msg) {
		if (isDebugEnabled()) {
			super.debug(msg);
			buffer(LogBuffer.Level.DEBUG, msg, null);
		}
	}

	@Override
	public void debug(String msg, Throwable error) {
		if (isDebugEnabled()) {
			super.debug(msg, error);
			buffer(LogBuffer.Level.DEBUG, msg, error);
		}
	}

	@Override
	public void info(String msg) {
		if (isInfoEnabled()) {
			super.info(msg);
			buffer(LogBuffer.Level.INFO, msg, null);
		}
	}

	@Override
	public void info(String msg, Throwable error) {
		if (isInfoEnabled()) {
			super.info(msg, error);
			buffer(LogBuffer.Level.INFO, msg, error);
		}
	}

	@Override
	public void warn(String msg) {
		if (isWarnEnabled()) {
			super.warn(msg);
			buffer(LogBuffer.Level.WARN, msg, null);
		}
	}

	@Override
	public void warn(String msg, Throwable error) {
		if (isWarnEnabled()) {
			super.warn(msg, error);
			buffer(LogBuffer.Level.WARN, msg, error);
		}
	}

	@Override
	public void error(String msg) {
		if (isErrorEnabled()) {
			super.error(msg);
			buffer(LogBuffer.Level.ERROR, msg, null);
		}
	}

	@Override
	public void error(String msg, Throwable error) {
		if (isErrorEnabled()) {
			super.error(msg, error);
			buffer(LogBuffer.Level.ERROR, msg, error);
		}
	}
}
