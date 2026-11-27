package us.bringardner.parley.mail.admin.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.admin.TestFiles;
import us.bringardner.parley.logging.testing.ErrorsExpected;


/** The providers run by the launcher: a server reads its own settings when it starts, and one with implicit TLS and no key store refuses to start. */
public class TestPop3Providers {

	/** Settings other tests in this JVM set that would change what these see */
	private static final String[] CLEARED = { "parley.mail.pop3.authenticationProvider", "parley.mail.pop3.userFile",
			"parley.mail.pop3.port", "parley.mail.pop3s.port", "parley.mail.pop3.secure", "parley.mail.users", "parley.mail.root",
			"parley.mail.pop3.configFile", "parley.mail.pop3.keyStoreName", "parley.mail.pop3s.keyStoreName", "parley.mail.tls.keyStore" };

	@TempDir
	Path tempDir;

	/** The @TempDir, on the local file system */
	private FileSource dir() throws java.io.IOException {
		return TestFiles.local(tempDir);
	}

	private final Map<String, String> saved = new HashMap<>();
	private ServerLauncher launcher;

	@BeforeEach
	public void clear() {
		us.bringardner.parley.core.BaseObject.clearSettings(); // the settings of a file read before stay for the process
		for (String n : CLEARED) {
			saved.put(n, System.getProperty(n));
			System.clearProperty(n);
		}
	}

	@AfterEach
	public void restore() {
		us.bringardner.parley.core.BaseObject.clearSettings();
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
		TestFiles.writeBytes(dir().getChild(name), (String.join("\n", lines).replace('\\', '/') + "\n").getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void providersFound() {
		assertTrue(ServerLauncher.providers().keySet().containsAll(Arrays.asList("pop3", "pop3s")), ServerLauncher.providers().keySet().toString());
	}

	@ErrorsExpected
	@Test
	public void runsWithTheSuiteSettings() throws Exception {
		int port = freePort();
		FileSource mail = dir().getChild("mail");
		us.bringardner.parley.mail.store.MailLocks.writeStoreSettings(
				mail,
				us.bringardner.parley.net.lock.Sharing.OFF); // made by an administrator
		write("users.txt", "tony , secret , READ|WRITE");
		// the server reads the file its configFile setting names, when it starts
		write("pop3.properties", "parley.mail.root=" + mail.getPath(), "parley.mail.users=" + dir().getChild("users.txt").getPath(),
				"parley.mail.pop3.port=" + port);
		System.setProperty("parley.mail.pop3.configFile", dir().getChild("pop3.properties").getPath());
		launcher = new ServerLauncher("Pop3Test", new LaunchSettings(new Properties()), Arrays.asList("pop3", "pop3s"), new ServerContext());
		launcher.init();
		launcher.startAll();
		assertEquals(ServerLauncher.State.FAILED, launcher.getState("pop3s"), "no key store: it refuses to start");
		assertTrue(launcher.getServer("pop3s").getError().contains("key store"), launcher.getServer("pop3s").getError());
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

}
