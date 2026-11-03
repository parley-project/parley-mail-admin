package us.bringardner.parley.mail.admin.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.net.server.ServerContext;

/**
 * The POP3 providers: found by the launcher, settings from parley-mail.properties then
 * parley-pop3.properties, the suite's user file (parley.mail.users), pop3s skipped while it has no key
 * store, and the 1.x secure flag only a warning.
 */
public class TestPop3Providers {

	/** Settings other tests in this JVM set that would change what these see */
	private static final String[] CLEARED = { "parley.mail.pop3.authenticationProvider", "parley.mail.pop3.userFile",
			"parley.mail.pop3.port", "parley.mail.pop3s.port", "parley.mail.pop3.secure", "parley.mail.users", "parley.mail.root",
			"parley.mail.pop3.keyStoreName", "parley.mail.pop3s.keyStoreName", "parley.mail.tls.keyStore" };

	@TempDir
	File dir;

	private final Map<String, String> saved = new HashMap<>();
	private ServerLauncher launcher;

	@BeforeEach
	public void clear() {
		for (String n : CLEARED) {
			saved.put(n, System.getProperty(n));
			System.clearProperty(n);
		}
	}

	@AfterEach
	public void restore() {
		if (launcher != null) {
			launcher.close();
		}
		for (String n : CLEARED) {
			System.clearProperty(n);
			if (saved.get(n) != null) {
				System.setProperty(n, saved.get(n));
			}
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}

	private void write(String name, String... lines) throws Exception {
		// (a .properties file reads \ as an escape: the paths in it have / on Windows)
		Files.write(new File(dir, name).toPath(), (String.join("\n", lines).replace('\\', '/') + "\n").getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void providersFound() {
		assertTrue(ServerLauncher.providers().keySet().containsAll(Arrays.asList("pop3", "pop3s")), ServerLauncher.providers().keySet().toString());
	}

	@Test
	public void runsWithTheSuiteSettings() throws Exception {
		int port = freePort();
		File mail = new File(dir, "mail");
		us.bringardner.parley.mail.store.MailLocks.writeStoreSettings(
				us.bringardner.parley.files.FileSourceFactory.getDefaultFactory().createFileSource(mail.getPath()),
				us.bringardner.parley.net.lock.Sharing.OFF); // made by an administrator
		write("users.txt", "tony , secret , READ|WRITE");
		write("parley-mail.properties", "parley.mail.root=" + mail.getPath(), "parley.mail.users=" + new File(dir, "users.txt").getPath(),
				"parley.mail.pop3.port=1");
		write("parley-pop3.properties", "parley.mail.pop3.port=" + port); // wins over the shared file
		launcher = new ServerLauncher("Pop3Test", new LaunchSettings(new Properties()), Arrays.asList("pop3", "pop3s"), dir, new ServerContext());
		launcher.init();
		assertEquals(ServerLauncher.State.OFF, launcher.getState("pop3s"), "no key store: skipped");
		assertTrue(launcher.getServer("pop3s").getError().contains("key store"), launcher.getServer("pop3s").getError());
		launcher.startAll();
		assertEquals(ServerLauncher.State.RUNNING, launcher.getState("pop3"));
		assertEquals(port, launcher.getServer("pop3").getPort());

		try (Socket s = new Socket("127.0.0.1", port)) {
			BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
			OutputStream out = s.getOutputStream();
			assertTrue(in.readLine().startsWith("+OK"));
			out.write("USER tony\r\n".getBytes(StandardCharsets.US_ASCII));
			assertTrue(in.readLine().startsWith("+OK"));
			out.write("PASS secret\r\n".getBytes(StandardCharsets.US_ASCII));
			String reply = in.readLine();
			assertTrue(reply.startsWith("+OK"), "logged in with parley.mail.users: " + reply);
			out.write("QUIT\r\n".getBytes(StandardCharsets.US_ASCII));
			in.readLine();
		}
	}

	@Test
	public void portIsOnlyAPort() throws Exception {
		MailServerProvider pop3 = (MailServerProvider) ServerLauncher.providers().get("pop3");
		MailServerProvider pop3s = (MailServerProvider) ServerLauncher.providers().get("pop3s");
		Properties c = new Properties();
		assertEquals(110, pop3.port(c));
		assertEquals(995, pop3s.port(c), "the standard port, not 0 (off)");
		assertNull(pop3.unavailable(c));
		assertTrue(pop3s.unavailable(c).contains("parley.mail.tls.keyStore"), pop3s.unavailable(c));
		c.setProperty("parley.mail.tls.keyStore", "keystore.p12");
		assertNull(pop3s.unavailable(c), "the suite's key store");
		Properties own = new Properties();
		own.setProperty("parley.mail.pop3s.keyStoreName", "pop3s.p12");
		assertNull(pop3s.unavailable(own), "its own key store");
		own.setProperty("parley.mail.pop3s.port", "0");
		assertTrue(pop3s.unavailable(own).contains("parley.servers"), "port 0 no longer turns it off: " + pop3s.unavailable(own));
	}

	@Test
	public void secureFlagIsOnlyAWarning() throws Exception {
		Properties c = new Properties();
		c.setProperty("parley.mail.pop3.secure", "true");
		MailServerProvider pop3 = (MailServerProvider) ServerLauncher.providers().get("pop3");
		MailServerProvider pop3s = (MailServerProvider) ServerLauncher.providers().get("pop3s");
		assertTrue(pop3.enabled(c) && pop3.unavailable(c) == null, "pop3 still runs");
		assertEquals(110, pop3.port(c));
		assertEquals(1, pop3.warnings(c).size());
		assertTrue(pop3.warnings(c).get(0).startsWith("parley.mail.pop3.secure is no longer read"), pop3.warnings(c).get(0));
		assertTrue(pop3.warnings(new Properties()).isEmpty());
		assertTrue(pop3s.warnings(c).isEmpty(), "pop3s's settings are its own");
	}
}
