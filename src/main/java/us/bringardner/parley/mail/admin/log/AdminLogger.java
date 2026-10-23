package us.bringardner.parley.mail.admin.log;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import us.bringardner.parley.core.ParleyLogger;

/**
 * The Parley logger of the mail suite (set with the {@code parley.core.logger} system property
 * before any Parley object logs): a {@link ParleyLogger}, so the usual {@code logLevel} and
 * {@code logFile} settings still work, that also gives every entry it writes to the
 * {@link LogBuffer} for the Logs page. The Logs page sets the level of each source's loggers.
 */
public class AdminLogger extends ParleyLogger {

	/** Every AdminLogger made (Parley shares loggers by name, so these are few). */
	private static final Set<AdminLogger> LOGGERS = ConcurrentHashMap.newKeySet();
	/** Levels set on the Logs page, by source. */
	private static final Map<LogBuffer.Source, Level> LEVELS = new ConcurrentHashMap<>();

	private volatile String loggerName = "";
	private volatile LogBuffer.Source source = LogBuffer.Source.OTHER;

	@Override
	public void init(String name) {
		super.init(name);
		loggerName = name == null ? "" : name;
		source = LogBuffer.Source.of(loggerName);
		Level l = LEVELS.get(source);
		if (l != null) {
			super.setLevel(l);
		}
		LOGGERS.add(this);
	}

	/**
	 * Servers set their loggers' levels when they are made (INFO); a level chosen on the Logs page
	 * wins over that.
	 */
	@Override
	public void setLevel(Level level) {
		Level chosen = LEVELS.get(source);
		super.setLevel(chosen != null ? chosen : level);
	}

	/** Set the level of every logger of a source, now and for loggers made later. */
	public static void setSourceLevel(LogBuffer.Source source, Level level) {
		if (level == null) {
			LEVELS.remove(source);
		} else {
			LEVELS.put(source, level);
		}
		for (AdminLogger l : LOGGERS) {
			if (l.source == source && level != null) {
				l.applyLevel(level);
			}
		}
	}

	/** The level chosen for a source, or null. */
	public static Level getSourceLevel(LogBuffer.Source source) {
		return LEVELS.get(source);
	}

	private void applyLevel(Level level) {
		super.setLevel(level);
	}

	private void record(LogBuffer.Level level, String msg, Throwable error) {
		LogBuffer b = LogBuffer.get();
		if (b != null) {
			b.add(level, loggerName, msg, error);
		}
	}

	@Override
	public void debug(String msg) {
		if (isDebugEnabled()) {
			record(LogBuffer.Level.DEBUG, msg, null);
		}
		super.debug(msg);
	}

	@Override
	public void debug(String msg, Throwable error) {
		if (isDebugEnabled()) {
			record(LogBuffer.Level.DEBUG, msg, error);
		}
		super.debug(msg, error);
	}

	@Override
	public void info(String msg) {
		if (isInfoEnabled()) {
			record(LogBuffer.Level.INFO, msg, null);
		}
		super.info(msg);
	}

	@Override
	public void info(String msg, Throwable error) {
		if (isInfoEnabled()) {
			record(LogBuffer.Level.INFO, msg, error);
		}
		super.info(msg, error);
	}

	@Override
	public void warn(String msg) {
		if (isWarnEnabled()) {
			record(LogBuffer.Level.WARN, msg, null);
		}
		super.warn(msg);
	}

	@Override
	public void warn(String msg, Throwable error) {
		if (isWarnEnabled()) {
			record(LogBuffer.Level.WARN, msg, error);
		}
		super.warn(msg, error);
	}

	@Override
	public void error(String msg) {
		if (isErrorEnabled()) {
			record(LogBuffer.Level.ERROR, msg, null);
		}
		super.error(msg);
	}

	@Override
	public void error(String msg, Throwable error) {
		if (isErrorEnabled()) {
			record(LogBuffer.Level.ERROR, msg, error);
		}
		super.error(msg, error);
	}
}
