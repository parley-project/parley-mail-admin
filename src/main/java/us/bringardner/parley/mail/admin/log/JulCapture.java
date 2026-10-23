package us.bringardner.parley.mail.admin.log;

import java.text.MessageFormat;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Gives java.util.logging entries (and System.Logger's, which go to java.util.logging unless
 * another provider is installed) of the Parley loggers to the {@link LogBuffer}. parley-smtp's
 * queue logs this way. Installed on the {@code us.bringardner} logger, so the container's own
 * logging isn't collected.
 */
public final class JulCapture extends Handler {

	static final String ROOT = "us.bringardner";

	private Logger logger;

	/** Start collecting. Entries still go to the container's handlers too. */
	public void install() {
		logger = Logger.getLogger(ROOT);
		if (logger.getLevel() == null) {
			logger.setLevel(Level.INFO);
		}
		setLevel(Level.ALL);
		logger.addHandler(this);
	}

	public void uninstall() {
		if (logger != null) {
			logger.removeHandler(this);
		}
	}

	/** Set the java.util.logging level of a source's loggers. */
	public static void setSourceLevel(LogBuffer.Source source, LogBuffer.Level level) {
		for (String p : source.packages()) {
			Logger.getLogger(p).setLevel(level == null ? null : toJul(level));
		}
	}

	static Level toJul(LogBuffer.Level level) {
		switch (level) {
		case TRACE:
			return Level.FINEST;
		case DEBUG:
			return Level.FINE;
		case WARN:
			return Level.WARNING;
		case ERROR:
			return Level.SEVERE;
		default:
			return Level.INFO;
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
	public void publish(LogRecord r) {
		LogBuffer b = LogBuffer.get();
		if (b == null || !isLoggable(r)) {
			return;
		}
		String msg = r.getMessage();
		Object[] params = r.getParameters();
		if (msg != null && params != null && params.length > 0) {
			try {
				msg = MessageFormat.format(msg, params);
			} catch (IllegalArgumentException e) {
				// keep the pattern
			}
		}
		b.add(fromJul(r.getLevel()), r.getLoggerName(), msg, r.getThrown());
	}

	@Override
	public void flush() {
	}

	@Override
	public void close() {
	}
}
