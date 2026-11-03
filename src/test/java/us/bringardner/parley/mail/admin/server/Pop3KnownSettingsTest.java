package us.bringardner.parley.mail.admin.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.mail.store.MailLocks;
import us.bringardner.parley.net.lock.Sharing;
import us.bringardner.parley.net.server.KnownSettings;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerMain;
import us.bringardner.parley.pop3.server.Pop3Server;

/**
 * To-Do 2.16: parley-pop3's list of settings (META-INF/parley/settings.list) has every name its code
 * spells out, and every name its servers read while they start and serve a session, so a server's
 * start-up check never warns about a real setting.
 */
public class Pop3KnownSettingsTest {

	@TempDir
	File dir;

	@Test
	public void everyNameTheServersReadIsListed() throws Exception {
		KnownSettings k = KnownSettings.load();
		assertTrue(k.prefixes().containsAll(List.of(Pop3Server.POP3_PREFIX, Pop3Server.POP3S_PREFIX)), k.prefixes().toString());
		File mail = new File(dir, "mail");
		MailLocks.writeStoreSettings(FileSourceFactory.getDefaultFactory().createFileSource(mail.getPath()), Sharing.OFF);
		File users = new File(dir, "users.txt");
		Files.write(users.toPath(), "tony , secret , READ|WRITE\n".getBytes(StandardCharsets.UTF_8));

		for (String id : List.of("pop3", "pop3s")) {
			KnownSettings.RecordingProperties c = new KnownSettings.RecordingProperties();
			c.setProperty("parley.mail.root", mail.getPath());
			c.setProperty("parley.mail.users", users.getPath());
			MailServerProvider p = (MailServerProvider) ServerLauncher.providers().get(id);
			int port = freePort();
			c.setProperty(p.prefix() + ".port", "" + port);
			p.enabled(c);
			p.unavailable(c);
			p.warnings(c);
			p.port(c);
			if (!p.implicitTls()) {
				ServerMain.Service s = p.create(new ServerContext(), c);
				s.startAndWait(5000);
				try {
					session(port);
				} finally {
					s.stop(5000);
				}
			}
			assertEquals(List.of(), k.undeclared(c.asked()), id + " read these");
		}
	}

	private static void session(int port) throws Exception {
		try (Socket s = new Socket("127.0.0.1", port)) {
			BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
			OutputStream out = s.getOutputStream();
			assertTrue(in.readLine().startsWith("+OK"));
			for (String cmd : List.of("CAPA", "USER tony", "PASS secret", "STAT", "LIST", "UIDL", "QUIT")) {
				out.write((cmd + "\r\n").getBytes(StandardCharsets.US_ASCII));
				String reply = in.readLine();
				assertTrue(reply.startsWith("+OK"), cmd + ": " + reply);
				if (cmd.equals("CAPA") || cmd.equals("LIST") || cmd.equals("UIDL")) {
					while (!".".equals(in.readLine())) {
						// the list
					}
				}
			}
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
