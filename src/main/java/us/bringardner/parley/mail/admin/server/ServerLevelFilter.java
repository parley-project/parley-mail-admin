package us.bringardner.parley.mail.admin.server;

import org.slf4j.Marker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import us.bringardner.parley.core.LogContext;

/**
 * Applies the level the Logs page chose for a server to everything logged while that server works, whichever
 * class logs it. A class of the framework (the sockets, TLS, the mail store) has one logger for every server
 * that uses it, so its level can't be a server's; the work's ID and the thread's name say whose work it is
 * ({@link ServerNames}). A line at or above the server's level is let through; any other is left to the
 * logging configuration (the filter never hides a line).
 * <p>
 * Only the servers' launcher installs it ({@link LoggerLevels}), and only when Logback is the backend.
 *
 * @since 2.0.0
 */
public class ServerLevelFilter extends TurboFilter {

	@Override
	public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
		if (level == null || ServerNames.isEmpty()) {
			return FilterReply.NEUTRAL;
		}
		String pkg = ServerNames.packageOf(LogContext.get(), Thread.currentThread().getName());
		if (pkg == null) {
			return FilterReply.NEUTRAL;
		}
		Integer chosen = ServerNames.getLevel(pkg);
		return chosen != null && level.toInt() >= chosen ? FilterReply.ACCEPT : FilterReply.NEUTRAL;
	}
}
