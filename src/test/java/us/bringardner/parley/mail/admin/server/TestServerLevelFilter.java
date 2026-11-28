package us.bringardner.parley.mail.admin.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import us.bringardner.parley.core.LogContext;

/** A server's level covers what the framework's classes log while that server works. */
public class TestServerLevelFilter {

	private static final String FTP = "us.bringardner.parley.ftp";
	private final Logger framework = (Logger) LoggerFactory.getLogger("test.framework.Sockets");

	@AfterEach
	public void restore() {
		new LoggerLevels().set(FTP, null);
		new LoggerLevels().set("us.bringardner.parley.smtp", null);
		framework.setLevel(null);
		((LoggerContext) LoggerFactory.getILoggerFactory()).getTurboFilterList().removeIf(f -> f instanceof ServerLevelFilter);
	}

	@Test
	public void aServersLevelCoversTheFrameworkLinesOfItsWork() throws Exception {
		framework.setLevel(Level.WARN);
		assertFalse(framework.isDebugEnabled(), "the framework's own level");
		assertTrue(new LoggerLevels().set(FTP, "DEBUG"));

		try (LogContext.Scope ftp = LogContext.enter("FtpServer-15")) {
			assertTrue(framework.isDebugEnabled(), "FTP's work");
			assertTrue(framework.isEnabledFor(Level.INFO));
			assertFalse(framework.isTraceEnabled(), "below FTP's level");
		}
		try (LogContext.Scope smtp = LogContext.enter("SmtpServer-3")) {
			assertFalse(framework.isDebugEnabled(), "another server's work");
		}
		assertFalse(framework.isDebugEnabled(), "work of no server");

		// the thread that runs the server, before there is a session
		AtomicBoolean named = new AtomicBoolean();
		Thread t = new Thread(() -> named.set(framework.isDebugEnabled()), "FtpServer");
		t.start();
		t.join(10000);
		assertTrue(named.get(), "the server's own thread");

		assertTrue(new LoggerLevels().set(FTP, null));
		try (LogContext.Scope ftp = LogContext.enter("FtpServer-16")) {
			assertFalse(framework.isDebugEnabled(), "the level is cleared");
		}
		assertEquals(Level.WARN, framework.getLevel());
	}

	@Test
	public void aLevelAboveTheConfigurationsNeverHidesALine() {
		framework.setLevel(Level.DEBUG);
		assertTrue(new LoggerLevels().set(FTP, "ERROR"));
		try (LogContext.Scope ftp = LogContext.enter("FtpServer-1")) {
			assertTrue(framework.isDebugEnabled(), "the configuration's level still applies");
		}
	}
}
