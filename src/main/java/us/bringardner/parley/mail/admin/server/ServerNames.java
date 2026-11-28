package us.bringardner.parley.mail.admin.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which server a line of the log belongs to, and the level chosen for each server.
 * <p>
 * What a server's code logs while it works on a request is identified with that server whichever class writes
 * it (the socket and TLS classes, the settings, the mail store): the work's ID ({@code FtpServer-15}, from
 * {@link us.bringardner.parley.core.LogContext}) starts with the server's name, and so does the name of the
 * thread that runs the server itself. A server is named by the start of its logger names, its package
 * ({@code us.bringardner.parley.ftp}), which is what the Logs page sets a level for.
 *
 * @since 2.0.0
 */
public final class ServerNames {

	private static final String[][] SERVERS = { { "Smtp", "us.bringardner.parley.smtp" },
			{ "Imap", "us.bringardner.parley.imap" }, { "Pop3", "us.bringardner.parley.pop3" },
			{ "Ftp", "us.bringardner.parley.ftp" }, { "Dns", "us.bringardner.parley.dns" } };

	/** The level (a Logback level's number) the Logs page chose for a server's package, if any */
	private static final Map<String, Integer> LEVELS = new ConcurrentHashMap<>();

	private ServerNames() {
	}

	/**
	 * @param sessionId the work's ID (FtpServer-15), or null
	 * @param threadName the thread's name, or null
	 * @return the package of the server that is doing the work, or null when it is not a server's work
	 */
	public static String packageOf(String sessionId, String threadName) {
		String ret = packageOfName(sessionId);
		return ret != null ? ret : packageOfName(threadName);
	}

	private static String packageOfName(String name) {
		if (name != null) {
			for (String[] s : SERVERS) {
				if (name.startsWith(s[0])) {
					return s[1];
				}
			}
		}
		return null;
	}

	/** @return true if the package is one of the servers' */
	public static boolean isServer(String pkg) {
		for (String[] s : SERVERS) {
			if (s[1].equals(pkg)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Choose the level of a server's lines (every line written for it, whatever class writes it).
	 *
	 * @param pkg the server's package
	 * @param level a Logback level's number (TRACE 5000, DEBUG 10000, INFO 20000, WARN 30000, ERROR 40000), or null for none
	 */
	static void setLevel(String pkg, Integer level) {
		if (level == null) {
			LEVELS.remove(pkg);
		} else {
			LEVELS.put(pkg, level);
		}
	}

	/** @return the level chosen for the server's lines (a Logback level's number), or null */
	static Integer getLevel(String pkg) {
		return LEVELS.get(pkg);
	}

	/** @return true if no server has a chosen level */
	static boolean isEmpty() {
		return LEVELS.isEmpty();
	}
}
