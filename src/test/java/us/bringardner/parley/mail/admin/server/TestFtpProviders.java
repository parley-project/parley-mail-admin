package us.bringardner.parley.mail.admin.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/**
 * The FTP provider run on its own by the launcher (no application to give it users): it reads its settings file
 * when it starts, its users are the suite's users file, and with loginPermission only users with that permission log in.
 */
public class TestFtpProviders {

	private static final String[] CLEARED = { "parley.mail.ftp.configFile", "parley.mail.ftp.port", "parley.mail.ftp.requireTls",
			"parley.mail.ftp.loginPermission", "parley.mail.ftp.root", "parley.mail.users", "parley.mail.root" };

	@TempDir
	Path tempDir;

	private final Map<String, String> saved = new HashMap<>();
	private ServerLauncher launcher;

	private FileSource dir() throws java.io.IOException {
		return TestFiles.local(tempDir);
	}

	@BeforeEach
	public void clear() {
		us.bringardner.parley.core.BaseObject.clearSettings();
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
		TestFiles.writeBytes(dir().getChild(name), (String.join("\n", lines).replace('\\', '/') + "\n").getBytes(StandardCharsets.UTF_8));
	}

	private static String login(int port, String user, String password) throws Exception {
		try (Socket s = new Socket("127.0.0.1", port)) {
			s.setSoTimeout(10000);
			BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
			OutputStream out = s.getOutputStream();
			in.readLine();
			out.write(("USER " + user + "\r\n").getBytes(StandardCharsets.US_ASCII));
			in.readLine();
			out.write(("PASS " + password + "\r\n").getBytes(StandardCharsets.US_ASCII));
			String reply = in.readLine();
			out.write("QUIT\r\n".getBytes(StandardCharsets.US_ASCII));
			return reply;
		}
	}

	@Test
	public void usesTheUsersFileAndOnlyAdminsLogIn() throws Exception {
		int port = freePort();
		FileSource mail = dir().getChild("mail");
		us.bringardner.parley.mail.store.MailLocks.writeStoreSettings(mail, us.bringardner.parley.net.lock.Sharing.OFF);
		write("users.txt", "boss@a.test , password-one , READ|WRITE|ADMIN", "clerk@a.test , password-two , READ|WRITE");
		write("ftp.properties", "parley.mail.root=" + mail.getPath(), "parley.mail.users=" + dir().getChild("users.txt").getPath(),
				"parley.mail.ftp.port=" + port, "parley.mail.ftp.requireTls=false", "parley.mail.ftp.loginPermission=admin",
				"parley.mail.ftp.root=ftp");
		System.setProperty("parley.mail.ftp.configFile", dir().getChild("ftp.properties").getPath());
		launcher = new ServerLauncher("FtpTest", new LaunchSettings(new Properties()), Arrays.asList("ftp"), new ServerContext());
		launcher.init();
		launcher.startAll();
		assertEquals(ServerLauncher.State.RUNNING, launcher.getState("ftp"), launcher.getServer("ftp").getError());
		assertTrue(login(port, "boss@a.test", "password-one").startsWith("230"), "an admin logs in with the users file");
		assertTrue(login(port, "clerk@a.test", "password-two").startsWith("530"), "a user without the permission doesn't");
		assertTrue(login(port, "boss@a.test", "wrong").startsWith("530"), "a wrong password doesn't");
	}
}
