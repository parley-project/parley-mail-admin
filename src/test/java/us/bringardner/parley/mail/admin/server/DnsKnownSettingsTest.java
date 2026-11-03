package us.bringardner.parley.mail.admin.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.net.server.KnownSettings;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerMain;

/**
 * To-Do 2.16: parley-dns's list of settings (META-INF/parley/settings.list) has every name its code
 * spells out, and every name the DNS server reads while it starts (from its own configuration, as
 * its provider gives it), so a server's start-up check never warns about a real setting.
 */
public class DnsKnownSettingsTest {

	@TempDir
	File dir;

	@Test
	public void everyNameTheServerReadsIsListed() throws Exception {
		KnownSettings k = KnownSettings.load();
		// the server's own configuration, as the launcher gives it (To-Do 11.13): every name it reads is recorded
		KnownSettings.RecordingProperties system = new KnownSettings.RecordingProperties(new Properties());
		File zones = new File(dir, "zones");
		Files.createDirectories(zones.toPath());
		Files.write(new File(zones, "known.test.txt").toPath(), ("@\tIN\tSOA\tns1.known.test. postmaster.known.test. (\n"
				+ "\t\t\t1 ; serial\n\t\t\t3600 ; refresh\n\t\t\t1800 ; retry\n\t\t\t1209600 ; expire\n\t\t\t300 ) ; minimum\n\n"
				+ "\t\tNS\tns1\nns1\tIN\tA\t127.0.0.1\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		system.setProperty("parley.dns.zoneDir", zones.getPath());
		system.setProperty("parley.dns.dnsDir", dir.getPath());
		system.setProperty("parley.dns.recursionAvailable", "false");
		system.setProperty("parley.dns.masterZone", "known.test");
		system.setProperty("parley.dns.bindAddress", "127.0.0.1");
		system.setProperty("parley.dns.port", "" + freePort());
		system.setProperty("parley.dns.udpPort", "" + freePort());
		system.setProperty("parley.dns.tcpPort", "" + freePort());
		system.setProperty("parley.dns.adminPort", "" + freePort());
		ServerMain.Service s = new DnsServerProvider().create(new ServerContext(), system);
		s.startAndWait(10000);
		s.stop(10000);
		assertEquals(List.of(), k.undeclared(system.asked()));
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
