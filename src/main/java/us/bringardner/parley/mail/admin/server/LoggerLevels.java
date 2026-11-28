package us.bringardner.parley.mail.admin.server;

import java.lang.reflect.Method;

/**
 * Changes a logger's level at run time in the backend the JVM logs with, when it is Logback (the servers'
 * backend). Logback is not a dependency: it is reached through slf4j's logger factory, and any other
 * backend (the web application's Log4j has its own path, {@code LogPageHandler}) is left alone.
 *
 * @since 2.0.0
 */
public class LoggerLevels {

	/**
	 * Set the level of one logger.
	 *
	 * @param logger a logger's name
	 * @param level  TRACE, DEBUG, INFO, WARN or ERROR; null or empty goes back to the configuration's level
	 * @return true if the backend took it, false if it is not Logback
	 */
	public boolean set(String logger, String level) {
		try {
			Object factory = Class.forName("org.slf4j.LoggerFactory").getMethod("getILoggerFactory").invoke(null);
			if (!factory.getClass().getName().equals("ch.qos.logback.classic.LoggerContext")) {
				return false;
			}
			Object log = factory.getClass().getMethod("getLogger", String.class).invoke(factory, logger);
			Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level", true, log.getClass().getClassLoader());
			Object value = null;
			if (level != null && !level.trim().isEmpty()) {
				Method toLevel = levelClass.getMethod("toLevel", String.class);
				value = toLevel.invoke(null, level.trim().toUpperCase());
			}
			log.getClass().getMethod("setLevel", levelClass).invoke(log, value);
			return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return false;
		}
	}
}
