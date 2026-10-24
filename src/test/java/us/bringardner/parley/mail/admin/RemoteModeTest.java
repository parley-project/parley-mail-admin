package us.bringardner.parley.mail.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

import us.bringardner.parley.mail.admin.suite.MailSuite;
import us.bringardner.parley.mail.admin.suite.ServiceId;
import us.bringardner.parley.net.server.LaunchSettings;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerLauncher;

/**
 * The servers run by a launcher "process" (one in this JVM, with its own MBean server behind a JMX
 * RMI connector), managed by the application over its JMX service URL.
 */
public class RemoteModeTest {

	@TempDir
	File dir;

	private static int freePort() throws IOException {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}

	@Test
	public void serversRunAsAServiceAreManagedOverJmx() throws Exception {
		File home = new File(dir, "home");
		File conf = new File(dir, "conf");
		conf.mkdirs();
		int registryPort = freePort();
		String url = "service:jmx:rmi:///jndi/rmi://localhost:" + registryPort + "/jmxrmi";
		int pop3Port = freePort();
		int smtpPort = freePort();
		String root = new File(dir, "mail").getAbsolutePath().replace('\\', '/');
		new File(dir, "mail/.parley-locks").mkdirs();
		Files.write(new File(conf, "parley-mail.properties").toPath(), ("parley.mail.root=" + root + "\n"
				+ "parley.mail.users=" + new File(dir, "users.txt").getAbsolutePath().replace('\\', '/') + "\n"
				+ "parley.mail.smtp.domains=a.test\n"
				+ "parley.mail.smtp.aliases=" + new File(dir, "aliases.txt").getAbsolutePath().replace('\\', '/') + "\n"
				+ "parley.mail.admin.jmxUrl=" + url + "\n").getBytes(StandardCharsets.UTF_8));
		Files.write(new File(conf, "parley-pop3.properties").toPath(), ("parley.mail.pop3.port=" + pop3Port + "\n").getBytes(StandardCharsets.UTF_8));
		Files.write(new File(conf, "parley-smtp.properties").toPath(), ("parley.mail.smtp.port=" + smtpPort + "\nparley.mail.submission.port=0\n").getBytes(StandardCharsets.UTF_8));
		Files.write(new File(dir, "users.txt").toPath(), "# users\n".getBytes(StandardCharsets.UTF_8));
		Files.write(new File(dir, "aliases.txt").toPath(), "# aliases\n".getBytes(StandardCharsets.UTF_8));

		// the Parley logger every test of this JVM uses (it's chosen once, when the first server logs): the application's
		if (System.getProperty(us.bringardner.parley.core.BaseObject.PROPERTY_LOGGER) == null) {
			System.setProperty(us.bringardner.parley.core.BaseObject.PROPERTY_LOGGER, us.bringardner.parley.mail.admin.log.AdminLogger.class.getName());
		}
		Set<String> before = new HashSet<>(System.getProperties().stringPropertyNames());
		MBeanServer mbs = MBeanServerFactory.newMBeanServer();
		Registry registry = LocateRegistry.createRegistry(registryPort);
		JMXConnectorServer cs = JMXConnectorServerFactory.newJMXConnectorServer(new JMXServiceURL(url), null, mbs);
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
			assertNull(suite.getConnectError());
			assertEquals(MailSuite.State.RUNNING, suite.getState(ServiceId.POP3));
			assertEquals(MailSuite.State.RUNNING, suite.getState(ServiceId.SMTP));
			assertEquals(MailSuite.State.UNAVAILABLE, suite.getState(ServiceId.IMAP), "not run there");
			Map<String, Object> st = suite.status(ServiceId.POP3);
			assertEquals(pop3Port, st.get("port"));
			assertEquals(url, st.get("where"));
			assertNotNull(suite.status(ServiceId.IMAP).get("error"));
			assertThrows(IllegalStateException.class, () -> suite.start(ServiceId.IMAP));
			assertEquals(1, suite.describeTargets().size());
			assertNull(suite.describeTargets().get(0).get("warning"), "it reads the files this application edits");

			// the queue and its history, over JMX
			assertNotNull(suite.getQueue());
			assertEquals(0, suite.getQueue().getMessageCount());
			assertEquals(new File(root, ".smtp-history").getAbsolutePath(), app.historyDirectory().getAbsolutePath());

			// started before the application saw it, with the files unchanged since: no restart needed
			assertFalse(suite.isRestartNeeded(ServiceId.POP3));
			app.getSettings().set("parley.mail.pop3.autologout", "700000");
			assertTrue(suite.isRestartNeeded(ServiceId.POP3));
			assertFalse(suite.isRestartNeeded(ServiceId.SMTP), "a POP3 setting");
			assertEquals(List.of(ServiceId.POP3), suite.restartChanged());
			assertFalse(suite.isRestartNeeded(ServiceId.POP3));
			assertEquals("700000", System.getProperty("parley.mail.pop3.autologout"), "the launcher read the files again");

			suite.stop(ServiceId.POP3);
			assertEquals("STOPPED", launcher.getServer("pop3").getState());
			suite.start(ServiceId.POP3);
			assertEquals("RUNNING", launcher.getServer("pop3").getState());

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
