package us.bringardner.parley.mail.admin.log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * The Logs page's levels by source, and a java.util.logging Handler on the {@code us.bringardner}
 * logger that gives every entry it sees to the {@link LogBuffer}.
 * <p>
 * Parley code logs through BaseObject and SystemLogger, which Log4j receives (log4j-jpl): its entries come to
 * the page through {@link LogPageAppender}. The handler is for what logs through java.util.logging
 * instead (the JDK and libraries). The page's level for a source
 * ({@link #setSourceLevel(LogBuffer.Source, LogBuffer.Level)}) is set as the Log4j level of that source's
 * loggers, and also as the level of its java.util.logging loggers.
 */
public final class LogPageHandler extends Handler {

	/** The java.util.logging logger every Parley logger is under. */
	public static final String ROOT = "us.bringardner";

	private static final LogPageHandler INSTANCE = new LogPageHandler();

	/**
	 * The java.util.logging loggers this class has set up, kept here: java.util.logging holds its
	 * loggers weakly, and one that is collected loses its level and handlers.
	 */
	private static final Map<String, Logger> LOGGERS = new ConcurrentHashMap<>();

	/** The levels chosen on the Logs page, by source. */
	private static final Map<LogBuffer.Source, LogBuffer.Level> LEVELS = new ConcurrentHashMap<>();

	private final SimpleFormatter formatter = new SimpleFormatter();

	private LogPageHandler() {
		setLevel(Level.ALL);
	}

	/**
	 * Put the handler on the {@value #ROOT} logger (once; calling it again does nothing).
	 */
	public static synchronized void install() {
		Logger root = logger(ROOT);
		for (Handler h : root.getHandlers()) {
			if (h == INSTANCE) {
				return;
			}
		}
		root.addHandler(INSTANCE);
	}

	/**
	 * Take the handler off the {@value #ROOT} logger.
	 */
	public static synchronized void uninstall() {
		logger(ROOT).removeHandler(INSTANCE);
	}

	private static Logger logger(String name) {
		return LOGGERS.computeIfAbsent(name, Logger::getLogger);
	}

	/**
	 * Set the level of every logger of a source, now and for loggers made later.
	 *
	 * @param source the source
	 * @param level  its level, or null to go back to the logging configuration's
	 */
	public static void setSourceLevel(LogBuffer.Source source, LogBuffer.Level level) {
		if (level == null) {
			LEVELS.remove(source);
		} else {
			LEVELS.put(source, level);
		}
		Level jul = level == null ? null : toJul(level);
		org.apache.logging.log4j.Level log4j = toLog4j(level == null ? LogBuffer.Level.INFO : level);
		for (String p : source.packages()) {
			logger(p).setLevel(jul);
			// the application's logger is Log4j: the level of the source is its level
			org.apache.logging.log4j.core.config.Configurator.setLevel(p, log4j);
		}
	}

	/** The level chosen for a source on the Logs page, or null. */
	public static LogBuffer.Level getSourceLevel(LogBuffer.Source source) {
		return LEVELS.get(source);
	}

	static org.apache.logging.log4j.Level toLog4j(LogBuffer.Level level) {
		switch (level) {
		case TRACE:
			return org.apache.logging.log4j.Level.TRACE;
		case DEBUG:
			return org.apache.logging.log4j.Level.DEBUG;
		case INFO:
			return org.apache.logging.log4j.Level.INFO;
		case WARN:
			return org.apache.logging.log4j.Level.WARN;
		default:
			return org.apache.logging.log4j.Level.ERROR;
		}
	}

	static Level toJul(LogBuffer.Level level) {
		switch (level) {
		case TRACE:
			return Level.FINEST;
		case DEBUG:
			return Level.FINE;
		case INFO:
			return Level.INFO;
		case WARN:
			return Level.WARNING;
		default:
			return Level.SEVERE;
		}
	}

	static LogBuffer.Level fromJul(Level level) {
		int v = level.intValue();
		if (v >= Level.SEVERE.intValue()) {
			return LogBuffer.Level.ERROR;
		}
		if (v >= Level.WARNING.intValue()) {
			return LogBuffer.Level.WARN;
		}
		if (v >= Level.INFO.intValue()) {
			return LogBuffer.Level.INFO;
		}
		if (v >= Level.FINE.intValue()) {
			return LogBuffer.Level.DEBUG;
		}
		return LogBuffer.Level.TRACE;
	}

	@Override
	public void publish(LogRecord record) {
		LogBuffer b = LogBuffer.get();
		if (b == null || record == null || !isLoggable(record)) {
			return;
		}
		String name = record.getLoggerName() == null ? "" : record.getLoggerName();
		b.add(fromJul(record.getLevel()), name, formatter.formatMessage(record), record.getThrown());
	}

	@Override
	public void flush() {
		// LogBuffer writes its file as it goes
	}

	@Override
	public void close() {
		// nothing held
	}
}
