package us.bringardner.parley.mail.admin.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

/** The Logs page's level for a kind of server reaches Logback in the launcher's process. */
public class TestLoggerLevels {

	private static Logger logger(String name) {
		return (Logger) LoggerFactory.getLogger(name);
	}

	@Test
	public void aLevelIsSetForEveryLoggerUnderAPrefixAndGoesBack() {
		Logger smtp = logger("test.levels.smtp");
		Logger child = logger("test.levels.smtp.SmtpServer");
		try {
			assertTrue(new LoggerLevels().set("test.levels.smtp", "debug"));
			assertEquals(Level.DEBUG, smtp.getLevel());
			assertEquals(Level.DEBUG, child.getEffectiveLevel());
			assertTrue(new LoggerLevels().set("test.levels.smtp", null));
			assertNull(smtp.getLevel());
		} finally {
			smtp.setLevel(null);
			child.setLevel(null);
		}
	}

	@Test
	public void anEmptyLevelGoesBackToTheConfigurations() {
		Logger log = logger("test.levels.pop3");
		try {
			new LoggerLevels().set("test.levels.pop3", "ERROR");
			assertEquals(Level.ERROR, log.getLevel());
			new LoggerLevels().set("test.levels.pop3", "");
			assertNull(log.getLevel());
		} finally {
			log.setLevel(null);
		}
	}
}
