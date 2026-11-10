package us.bringardner.parley.mail.admin.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.util.LogHelper;

/**
 * The Logs page's entries come through java.util.logging (the JDK's System.Logger when no other
 * System.LoggerFinder is installed), and its levels are the java.util.logging loggers' levels.
 */
public class LogPageHandlerTest {

	private LogBuffer buffer;

	@BeforeEach
	public void setup() {
		buffer = new LogBuffer(100);
		LogBuffer.install(buffer);
		LogPageHandler.install();
		LogPageHandler.install();
	}

	@AfterEach
	public void cleanup() {
		LogPageHandler.setSourceLevel(LogBuffer.Source.POP3, null);
		LogPageHandler.uninstall();
		LogBuffer.install(null);
	}

	private List<LogBuffer.Entry> entries(LogBuffer.Level min, LogBuffer.Source source) {
		return buffer.query(0, min, source, null, 100);
	}

	@Test
	public void testEntriesFromBaseObject() {
		LogHelper pop3 = new LogHelper("us.bringardner.parley.pop3.server.TestHandler");
		pop3.logInfo("an info");
		pop3.logError("an error", new IOException("boom"));
		pop3.logDebug("a debug, below java.util.logging's INFO");

		List<LogBuffer.Entry> got = entries(LogBuffer.Level.TRACE, LogBuffer.Source.POP3);
		assertEquals(2, got.size(), "one entry each, though install was called twice");
		assertEquals(LogBuffer.Level.INFO, got.get(0).level);
		assertEquals("an info", got.get(0).message);
		assertEquals("us.bringardner.parley.pop3.server.TestHandler", got.get(0).logger);
		assertEquals(LogBuffer.Level.ERROR, got.get(1).level);
		assertTrue(got.get(1).error.contains("java.io.IOException: boom"), got.get(1).error);
	}

	@Test
	public void testSourceLevels() {
		LogHelper pop3 = new LogHelper("us.bringardner.parley.pop3.server.TestLevels");
		LogPageHandler.setSourceLevel(LogBuffer.Source.POP3, LogBuffer.Level.DEBUG);
		assertEquals(LogBuffer.Level.DEBUG, LogPageHandler.getSourceLevel(LogBuffer.Source.POP3));
		assertEquals(Level.FINE, Logger.getLogger("us.bringardner.parley.pop3").getLevel());
		pop3.logDebug("shown at DEBUG");

		LogPageHandler.setSourceLevel(LogBuffer.Source.POP3, LogBuffer.Level.WARN);
		pop3.logInfo("held back at WARN");
		pop3.logWarn("a warning");

		LogPageHandler.setSourceLevel(LogBuffer.Source.POP3, null);
		assertNull(LogPageHandler.getSourceLevel(LogBuffer.Source.POP3));
		assertNull(Logger.getLogger("us.bringardner.parley.pop3").getLevel(), "back to the configuration's level");

		List<LogBuffer.Entry> got = entries(LogBuffer.Level.TRACE, LogBuffer.Source.POP3);
		assertEquals(2, got.size(), got.toString());
		assertEquals("shown at DEBUG", got.get(0).message);
		assertEquals("a warning", got.get(1).message);
	}

	@Test
	public void testLevelMapping() {
		for (LogBuffer.Level l : LogBuffer.Level.values()) {
			assertEquals(l, LogPageHandler.fromJul(LogPageHandler.toJul(l)), l.name());
		}
		assertEquals(LogBuffer.Level.DEBUG, LogPageHandler.fromJul(Level.CONFIG));
		assertEquals(LogBuffer.Level.TRACE, LogPageHandler.fromJul(Level.FINER));
	}
}
