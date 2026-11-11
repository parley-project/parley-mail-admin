package us.bringardner.parley.mail.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.admin.server.LaunchSettings;
import us.bringardner.parley.mail.admin.server.ServerContext;
import us.bringardner.parley.mail.admin.server.ServerLauncher;
import us.bringardner.parley.mail.admin.settings.SettingsCatalog;
import us.bringardner.parley.mail.admin.suite.MailSuite;
import us.bringardner.parley.mail.admin.suite.ServiceId;

/**
 * The servers run by a launcher "process" (one in this JVM, with its own MBean server behind a JMX
 * RMI connector), managed by the application over its JMX service URL.
 */
public class RemoteModeTest {

	@TempDir
	Path tempDir;

	/** The @TempDir, on the local file system */
	private FileSource dir() throws java.io.IOException {
		return TestFiles.local(tempDir);
	}

	private static int freePort() throws IOException {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}

	@Test
	public void serversRunAsAServiceAreManagedOverJmx() throws Exception {
		FileSource home = dir().getChild("home");
		FileSource conf = dir().getChild("conf");
		conf.mkdirs();
		int registryPort = freePort();
		String url = "service:jmx:rmi:///jndi/rmi://localhost:" + registryPort + "/jmxrmi";
		int pop3Port = freePort();
		int smtpPort = freePort();
		String root = dir().getChild("mail").getAbsolutePath().replace('\\', '/');
		dir().getChild("mail").getChild(".parley-locks").mkdirs();
		dir().getChild("mail").getChild(".smtp-queue").mkdirs();
		// the store's and the queue's own settings, as parley-servers-setup makes them
		TestFiles.writeBytes(dir().getChild("mail").getChild(".parley-store.properties"), "sharing=host\n".getBytes(StandardCharsets.UTF_8));
		TestFiles.writeBytes(dir().getChild("mail").getChild(".smtp-queue").getChild("queue.properties"), ("hostname=mx.a.test\ndomains=a.test\naliases="
				+ dir().getChild("aliases.txt").getAbsolutePath().replace('\\', '/') + "\n").getBytes(StandardCharsets.UTF_8));
		// the JMX password in a file, as the parley-servers package keeps it
		TestFiles.writeBytes(dir().getChild("admin.password"), "s3cret-jmx\n".getBytes(StandardCharsets.UTF_8));
		TestFiles.writeBytes(conf.getChild("parley-mail.properties"), ("parley.mail.root=" + root + "\n"
				+ "parley.mail.users=" + dir().getChild("users.txt").getAbsolutePath().replace('\\', '/') + "\n"
				+ "parley.mail.admin.jmxUrl=" + url + "\n"
				+ "parley.mail.admin.jmxUser=parleyadmin\n"
				+ "parley.mail.admin.jmxPasswordFile=" + dir().getChild("admin.password").getAbsolutePath().replace('\\', '/') + "\n").getBytes(StandardCharsets.UTF_8));
		TestFiles.writeBytes(conf.getChild("parley-pop3.properties"), ("parley.mail.pop3.port=" + pop3Port + "\n").getBytes(StandardCharsets.UTF_8));
		TestFiles.writeBytes(conf.getChild("parley-smtp.properties"), ("parley.mail.smtp.port=" + smtpPort + "\nparley.mail.smtp.hostname=mx.a.test\n").getBytes(StandardCharsets.UTF_8));
		TestFiles.writeBytes(dir().getChild("users.txt"), "# users\n".getBytes(StandardCharsets.UTF_8));
		TestFiles.writeBytes(dir().getChild("aliases.txt"), "# aliases\n".getBytes(StandardCharsets.UTF_8));

		// the Parley logger every test of this JVM uses (it's chosen once, when the first server logs): the application's
		AdminApp.installLogger();
		Set<String> before = new HashSet<>(System.getProperties().stringPropertyNames());
		MBeanServer mbs = MBeanServerFactory.newMBeanServer();
		Registry registry = LocateRegistry.createRegistry(registryPort);
		// a password, as the parley-mail service's JMX has
		Map<String, Object> env = new java.util.HashMap<>();
		env.put(JMXConnectorServer.AUTHENTICATOR, (javax.management.remote.JMXAuthenticator) credentials -> {
			String[] c = credentials instanceof String[] ? (String[]) credentials : new String[0];
			if (c.length == 2 && c[0].equals("parleyadmin") && c[1].equals("s3cret-jmx")) {
				return new javax.security.auth.Subject();
			}
			throw new SecurityException("wrong JMX user or password");
		});
		JMXConnectorServer cs = JMXConnectorServerFactory.newJMXConnectorServer(new JMXServiceURL(url), env, mbs);
		ServerLauncher launcher = null;
		AdminApp app = null;
		try {
			cs.start();
			// the "service": SMTP and POP3, reading the same settings folder
			launcher = new ServerLauncher("MailService", new LaunchSettings(new Properties()), List.of("smtp", "pop3"), conf, new ServerContext(mbs));
			launcher.init();
			launcher.startAll();

			app = AdminApp.start(home, conf);
			MailSuite suite = app.getSuite();
			assertFalse(suite.isLocal());
			assertNull(suite.getConnectError(), "logged in with the password file's password");
			assertEquals(MailSuite.State.RUNNING, suite.getState(ServiceId.POP3));
			assertEquals(MailSuite.State.RUNNING, suite.getState(ServiceId.SMTP));
			assertEquals(MailSuite.State.UNAVAILABLE, suite.getState(ServiceId.IMAP), "not run there");
			Map<String, Object> st = suite.status(ServiceId.POP3);
			assertEquals(pop3Port, st.get("port"));
			assertEquals(url, st.get("where"));
			assertNotNull(suite.status(ServiceId.IMAP).get("error"));
			assertThrows(IllegalStateException.class, () -> suite.start(ServiceId.IMAP));
			// who is connected, over JMX
			try (java.net.Socket client = new java.net.Socket("127.0.0.1", pop3Port)) {
				new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII)).readLine(); // +OK
				List<Map<String, Object>> conns = suite.connections(ServiceId.POP3);
				assertEquals(1, conns.size());
				assertTrue(String.valueOf(conns.get(0).get("remote")).startsWith("127.0.0.1:"), conns.toString());
				assertEquals(false, conns.get(0).get("tls"));
			}
			assertEquals(1, suite.describeTargets().size());
			assertNull(suite.describeTargets().get(0).get("warning"), "it reads the files this application edits");

			// the queue and its history, over JMX
			assertNotNull(suite.getQueue());
			assertEquals(0, suite.getQueue().getMessageCount());
			assertEquals(dir().getChild("mail").getChild(".smtp-history").getAbsolutePath(), app.historyDirectory().getAbsolutePath());

			// started before the application saw it, with the files unchanged since: no restart needed
			assertFalse(suite.isRestartNeeded(ServiceId.POP3));
			app.getSettings().set("parley.mail.pop3.autologout", "700000");
			assertTrue(suite.isRestartNeeded(ServiceId.POP3));
			assertFalse(suite.isRestartNeeded(ServiceId.SMTP), "a POP3 setting");
			long started = launcher.getServer("pop3").getStarted();
			assertEquals(List.of(ServiceId.POP3), suite.restartChanged());
			assertFalse(suite.isRestartNeeded(ServiceId.POP3));
			assertTrue(launcher.getServer("pop3").getStarted() >= started, "restarted");

			// the queue's own settings: the SMTP server needs a restart, POP3 doesn't
			app.getSettings().set(SettingsCatalog.QUEUE_PREFIX + "retry", "10,20");
			assertTrue(suite.isRestartNeeded(ServiceId.SMTP));
			assertFalse(suite.isRestartNeeded(ServiceId.POP3));
			assertEquals(List.of(ServiceId.SMTP), suite.restartChanged());
			assertTrue(TestFiles.read(dir().getChild("mail").getChild(".smtp-queue").getChild("queue.properties")).contains("retry=10,20"));

			suite.stop(ServiceId.POP3);
			assertEquals("STOPPED", launcher.getServer("pop3").getState());
			suite.start(ServiceId.POP3);
			assertEquals("RUNNING", launcher.getServer("pop3").getState());

			// a service turned off and on: the launcher reads parley.servers from its file again (as
			// ServerMain sets it up when the list is in the launcher's settings file)
			FileSource shared = conf.getChild("parley-mail.properties");
			launcher.setServerList(() -> {
				Properties p = new Properties();
				try (java.io.Reader r = new java.io.InputStreamReader(shared.getInputStream(), StandardCharsets.UTF_8)) {
					p.load(r);
				}
				return List.of(p.getProperty("parley.servers", "").split(","));
			});
			app.getSettings().set("parley.servers", "smtp,pop3");
			suite.setListed(ServiceId.POP3, false);
			assertEquals("OFF", launcher.getServer("pop3").getState());
			assertEquals("Not listed in parley.servers", launcher.getServer("pop3").getError());
			assertTrue(TestFiles.read(shared).contains("parley.servers=smtp\n"));
			assertEquals(MailSuite.State.OFF, suite.getState(ServiceId.POP3));
			suite.setListed(ServiceId.POP3, true);
			assertEquals("RUNNING", launcher.getServer("pop3").getState());
			assertTrue(TestFiles.read(shared).contains("parley.servers=smtp,pop3\n"));
			// a launcher that doesn't run the service yet: the change needs it restarted
			launcher.setServerList(null);
			IOException later = assertThrows(IOException.class, () -> suite.setListed(ServiceId.IMAP, true));
			assertTrue(later.getMessage().contains("restart the launcher"), later.getMessage());
			suite.setListed(ServiceId.IMAP, false);

			// the service restarts (a new JMX connector at the same address): the very first call
			// after it still finds the service's beans, so its pages (Queue, DNS) don't go missing
			assertTrue(suite.types().contains("MailQueue"), suite.types().toString());
			cs.stop();
			cs = JMXConnectorServerFactory.newJMXConnectorServer(new JMXServiceURL(url), env, mbs);
			cs.start();
			assertTrue(suite.types().contains("MailQueue"), "the first call after the restart");
			assertEquals(MailSuite.State.RUNNING, suite.getState(ServiceId.POP3));

			// the application stops: the service keeps running
			app.close();
			app = null;
			assertEquals("RUNNING", launcher.getServer("pop3").getState());
		} finally {
			if (app != null) {
				app.close();
			}
			if (launcher != null) {
				launcher.close();
			}
			cs.stop();
			UnicastRemoteObject.unexportObject(registry, true);
			// the launcher set the files' settings as system properties: the next test's launcher mustn't take them for -D
			for (String k : System.getProperties().stringPropertyNames()) {
				if (!before.contains(k) && k.startsWith("parley.")) {
					System.clearProperty(k);
				}
			}
		}
	}
}
