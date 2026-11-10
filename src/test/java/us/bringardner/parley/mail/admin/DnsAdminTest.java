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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

import us.bringardner.parley.dns.server.DnsServer;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.admin.dns.DnsAdmin;
import us.bringardner.parley.mail.admin.server.LaunchSettings;
import us.bringardner.parley.mail.admin.server.ServerContext;
import us.bringardner.parley.mail.admin.server.ServerLauncher;
import us.bringardner.parley.mail.admin.suite.MailSuite;

/**
 * The admin and a Parley DNS server run by a launcher "process" (in this JVM, behind a JMX RMI
 * connector with a password): the server shows on the generic Servers list with its Dns page, and
 * the DNS page makes a domain's zone, which inherits the shared zone's records, and changes its
 * records with signed dynamic updates.
 */
public class DnsAdminTest {

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

	private static void write(FileSource f, String text) throws IOException {
		f.getParentFile().mkdirs();
		TestFiles.writeBytes(f, text.getBytes(StandardCharsets.UTF_8));
	}

	private static Map<String, String> rec(String type, String name, String value) {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("type", type);
		m.put("name", name);
		m.put("value", value);
		return m;
	}

	@SuppressWarnings("unchecked")
	private static List<String> records(Map<String, Object> zone, String name, String type) {
		List<String> ret = new ArrayList<>();
		for (Map<String, Object> r : (List<Map<String, Object>>) zone.get("records")) {
			if (r.get("name").equals(name) && r.get("type").equals(type)) {
				ret.add(r.get("data") + (Boolean.TRUE.equals(r.get("inherited")) ? " (inherited)" : ""));
			}
		}
		java.util.Collections.sort(ret);
		return ret;
	}

	@SuppressWarnings("unchecked")
	private static List<Long> ttls(Map<String, Object> zone, String name, String type) {
		List<Long> ret = new ArrayList<>();
		for (Map<String, Object> r : (List<Map<String, Object>>) zone.get("records")) {
			if (r.get("name").equals(name) && r.get("type").equals(type)) {
				ret.add(((Number) r.get("ttl")).longValue());
			}
		}
		return ret;
	}

	@SuppressWarnings("unchecked")
	private static List<String> mail(Map<String, Object> zone) {
		List<String> ret = new ArrayList<>();
		for (Map<String, Object> r : (List<Map<String, Object>>) zone.get("mail")) {
			ret.add(r.get("name") + " " + r.get("type") + " " + r.get("status"));
		}
		return ret;
	}

	@Test
	@SuppressWarnings("unchecked")
	public void dnsServerIsManagedGenericallyAndItsZonesFromTheDnsPage() throws Exception {
		FileSource home = dir().getChild("home");
		FileSource conf = dir().getChild("conf");
		FileSource dnsDir = dir().getChild("dns");
		int registryPort = freePort();
		String url = "service:jmx:rmi:///jndi/rmi://127.0.0.1:" + registryPort + "/jmxrmi";
		String root = dir().getChild("mail").getAbsolutePath().replace('\\', '/');
		dir().getChild("mail").getChild(".parley-locks").mkdirs();
		write(dir().getChild("mail").getChild(".parley-store.properties"), "sharing=host\n");
		write(dir().getChild("mail").getChild(".smtp-queue").getChild("queue.properties"), "hostname=mx.a.test\ndomains=a.test,b.test\n");
		write(dir().getChild("admin.password"), "s3cret-jmx\n");
		// the DNS server: the primary domain's zone, a key the admin may update with
		String secret = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
		write(dnsDir.getChild("admin.key"), "parley-admin hmac-sha256 " + secret + "\n");
		write(dnsDir.getChild("zones").getChild("a.test.txt"), "$TTL 3600\n@ IN SOA ns1.a.test. hostmaster.a.test. ( 7 3600 1800 1209600 300 )\n"
				+ "  NS ns1.a.test.\n  MX 10 mx.a.test.\n  TXT \"site-verification=abc\"\n  TXT \"v=spf1 mx -all\"\n"
				+ "ns1 A 192.0.2.53\nmx A 192.0.2.25\nwww A 192.0.2.80\n");
		// the shared zone the other domains inherit: the same records, written for any domain
		write(dnsDir.getChild("shared.zone"), "$TTL 3600\n@ IN SOA ns1.a.test. hostmaster.a.test. ( 7 3600 1800 1209600 300 )\n"
				+ "  NS ns1.a.test.\n  MX 10 mx.a.test.\n  TXT \"site-verification=abc\"\n  TXT \"v=spf1 mx -all\"\n"
				+ "www A 192.0.2.80\n");
		write(conf.getChild("parley-dns.properties"), "parley.dns.dnsDir=" + dnsDir.getAbsolutePath().replace('\\', '/') + "\n"
				+ "parley.dns.sharedZone=" + dnsDir.getChild("shared.zone").getAbsolutePath().replace('\\', '/') + "\n"
				+ "parley.dns.zoneDir=" + dnsDir.getChild("zones").getAbsolutePath().replace('\\', '/') + "\n"
				+ "parley.dns.masterZone=a.test\nparley.dns.bindAddress=127.0.0.1\nparley.dns.port=" + freePort() + "\n"
				+ "parley.dns.recursionAvailable=false\n"
				+ "parley.dns.tsigKeyFile=admin.key\nparley.dns.updateKeys=parley-admin\n");
		write(conf.getChild("parley-mail.properties"), "parley.mail.root=" + root + "\n"
				+ "parley.mail.users=" + dir().getChild("users.txt").getAbsolutePath().replace('\\', '/') + "\n"
				+ "parley.mail.admin.jmxUrl=" + url + "\nparley.mail.admin.jmxUser=parleyadmin\n"
				+ "parley.mail.admin.jmxPasswordFile=" + dir().getChild("admin.password").getAbsolutePath().replace('\\', '/') + "\n"
				+ "parley.mail.admin.primaryDomain=a.test\n"
				+ "parley.mail.admin.dnsKeyFile=" + dnsDir.getChild("admin.key").getAbsolutePath().replace('\\', '/') + "\n");
		write(dir().getChild("users.txt"), "# users\n");

		AdminApp.installLogger();
		Set<String> before = new HashSet<>(System.getProperties().stringPropertyNames());
		MBeanServer mbs = MBeanServerFactory.newMBeanServer();
		Registry registry = LocateRegistry.createRegistry(registryPort);
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
			launcher = new ServerLauncher("DnsService", new LaunchSettings(new Properties()), List.of("dns"), conf, new ServerContext(mbs));
			launcher.init();
			launcher.startAll();

			app = AdminApp.start(home, conf);
			MailSuite suite = app.getSuite();
			assertNull(suite.getConnectError());

			// the generic part: any server the launcher runs, with the pages its beans give
			List<Map<String, Object>> servers = suite.servers();
			assertEquals(1, servers.size(), servers.toString());
			Map<String, Object> s = servers.get(0);
			assertEquals("dns", s.get("id"));
			assertEquals("RUNNING", s.get("state"));
			assertEquals(false, s.get("mail"));
			assertEquals(List.of("Dns"), s.get("types"));
			assertEquals(url, s.get("where"));
			assertEquals("DnsService", s.get("launcher"));
			assertTrue(suite.types().contains("Dns"));

			DnsAdmin dns = app.getDns();
			Map<String, Object> st = dns.status();
			assertEquals(true, st.get("available"));
			assertEquals(true, st.get("keyReady"), String.valueOf(st.get("keyProblem")));
			assertEquals(false, st.get("recursion"));

			List<Map<String, String>> want = List.of(rec("MX", "b.test.", "10 mx.a.test."), rec("TXT", "b.test.", "v=spf1 mx -all"),
					rec("TXT", "_dmarc.b.test.", "v=DMARC1; p=none; rua=mailto:postmaster@b.test"));
			Map<String, Object> z = dns.zone("b.test", "a.test", want);
			assertEquals(false, z.get("exists"));
			assertEquals(true, z.get("primaryServed"));
			assertThrows(IllegalStateException.class, () -> dns.createZone("a.test", "a.test"), "the primary's zone is made by hand");

			// a zone for b.test: every record of the shared zone, under b.test
			dns.createZone("b.test", "a.test");
			z = dns.zone("b.test", "a.test", want);
			assertEquals(true, z.get("exists"));
			assertEquals("shared", z.get("inheritFrom"));
			assertEquals(List.of("192.0.2.80 (inherited)"), records(z, "www.b.test", "A"));
			assertEquals(List.of("b.test. MX served", "b.test. TXT served", "_dmarc.b.test. TXT missing"), mail(z));

			// override an inherited record set; it is the domain's own now
			dns.setRecords("b.test", "www", "A", 300, List.of("198.51.100.80"));
			z = dns.zone("b.test", "a.test", want);
			assertEquals(List.of("198.51.100.80"), records(z, "www.b.test", "A"));

			// publish the mail records: only the missing DMARC record is added
			List<Map<String, String>> changed = dns.publishMail("b.test", want);
			assertEquals(1, changed.size(), changed.toString());
			z = dns.zone("b.test", "a.test", want);
			assertEquals(List.of("b.test. MX served", "b.test. TXT served", "_dmarc.b.test. TXT served"), mail(z));

			// a different SPF: publishing replaces it and keeps the other TXT record at the name
			dns.setRecords("b.test", "@", "TXT", 3600, List.of("\"site-verification=abc\"", "\"v=spf1 a -all\""));
			z = dns.zone("b.test", "a.test", want);
			assertEquals(List.of("b.test. MX served", "b.test. TXT different", "_dmarc.b.test. TXT served"), mail(z));
			dns.publishMail("b.test", want);
			z = dns.zone("b.test", "a.test", want);
			assertEquals(List.of("\"site-verification=abc\"", "\"v=spf1 mx -all\""), records(z, "b.test", "TXT"));

			// deleting the own record: the inherited one shows through again
			dns.deleteRecord("b.test", "www.b.test", "A", "198.51.100.80");
			z = dns.zone("b.test", "a.test", want);
			assertEquals(List.of("192.0.2.80 (inherited)"), records(z, "www.b.test", "A"));
			assertThrows(IllegalArgumentException.class, () -> dns.setRecords("b.test", "x", "SOA", 300, List.of("x")));
			assertThrows(IllegalArgumentException.class, () -> dns.setRecords("b.test", "www.c.test.", "A", 300, List.of("192.0.2.1")));

			// a change to the primary doesn't reach the domain: it inherits the shared zone
			dns.setRecords("a.test", "ftp", "A", 300, List.of("192.0.2.21"));
			z = dns.zone("b.test", "a.test", want);
			assertEquals(List.of(), records(z, "ftp.b.test", "A"));

			// the record types and their examples come from the DNS server: SOA and RP too, not the DNSSEC ones
			List<String> types = new ArrayList<>();
			for (Map<String, Object> t : (List<Map<String, Object>>) z.get("types")) {
				types.add((String) t.get("name"));
				assertNotNull(t.get("example"), t.toString());
			}
			assertTrue(types.containsAll(List.of("A", "SOA", "RP", "SPF", "HINFO", "DS", "TXT", "MX")), types.toString());
			assertFalse(types.contains("RRSIG"), types.toString());

			// added to a set: the records there stay, the TTL is the set's
			dns.addRecords("b.test", "ftp", "A", 600, List.of("192.0.2.21"));
			dns.addRecords("b.test", "ftp", "A", 900, List.of("192.0.2.22"));
			z = dns.zone("b.test", "a.test", want);
			assertEquals(List.of("192.0.2.21", "192.0.2.22"), records(z, "ftp.b.test", "A"));
			assertEquals(List.of(900L, 900L), ttls(z, "ftp.b.test", "A"));
			// every field of a record changed: name, type, TTL and value
			dns.changeRecord("b.test", "ftp", "A", "192.0.2.22", "files", "CNAME", 1200, "www.b.test.");
			z = dns.zone("b.test", "a.test", want);
			assertEquals(List.of("192.0.2.21"), records(z, "ftp.b.test", "A"));
			assertEquals(List.of("www.b.test."), records(z, "files.b.test", "CNAME"));
			assertEquals(List.of(1200L), ttls(z, "files.b.test", "CNAME"));
			assertThrows(IllegalStateException.class, () -> dns.changeRecord("b.test", "ftp", "A", "192.0.2.99", "ftp", "A", 300, "192.0.2.98"), "not there");
			// the SOA, listed first: changed, its serial raised past the zone's own though it was lower
			List<Map<String, Object>> recs = (List<Map<String, Object>>) z.get("records");
			assertEquals("SOA", recs.get(0).get("type"));
			long own = ((Number) z.get("ownSerial")).longValue();
			dns.changeRecord("b.test", "@", "SOA", (String) recs.get(0).get("data"), "@", "SOA", 300, "ns1.a.test. hostmaster.b.test. 1 3600 1800 1209600 600");
			z = dns.zone("b.test", "a.test", want);
			assertEquals(own + 1, ((Number) z.get("ownSerial")).longValue());
			assertTrue(records(z, "b.test", "SOA").get(0).contains("hostmaster.b.test. "), records(z, "b.test", "SOA").toString());
			assertThrows(IllegalArgumentException.class, () -> dns.deleteRecord("b.test", "@", "SOA", "x"));
			assertThrows(IllegalArgumentException.class, () -> dns.addRecords("b.test", "@", "SOA", 300, List.of("a. b. 1 1 1 1 1")));
			assertThrows(IllegalArgumentException.class, () -> dns.addRecords("b.test", "x", "RRSIG", 300, List.of("x")));
			dns.deleteRecord("b.test", "files", "CNAME", "www.b.test.");
			dns.deleteRecord("b.test", "ftp", "A", "192.0.2.21");

			// the shared zone, edited the same way: b.test serves the change at once
			assertTrue(String.valueOf(st.get("sharedZone")).endsWith("shared.zone"), String.valueOf(st.get("sharedZone")));
			Map<String, Object> sh = dns.sharedZone();
			assertEquals(st.get("sharedZone"), sh.get("file"));
			List<String> shared = new ArrayList<>();
			for (Map<String, Object> r : (List<Map<String, Object>>) sh.get("records")) {
				shared.add(r.get("name") + " " + r.get("type") + " " + r.get("data"));
			}
			assertTrue(shared.get(0).startsWith("@ SOA "), shared.toString());
			assertTrue(shared.contains("www A 192.0.2.80"), shared.toString());
			dns.addSharedRecords("ftp", "A", 300, List.of("192.0.2.21"));
			z = dns.zone("b.test", "a.test", want);
			assertEquals(List.of("192.0.2.21 (inherited)"), records(z, "ftp.b.test", "A"));
			dns.changeSharedRecord("ftp", "A", "192.0.2.21", "ftp", "AAAA", 300, "2001:db8::21");
			z = dns.zone("b.test", "a.test", want);
			assertEquals(List.of(), records(z, "ftp.b.test", "A"));
			assertEquals(List.of("2001:db8::21 (inherited)"), records(z, "ftp.b.test", "AAAA"));
			dns.deleteSharedRecord("ftp", "AAAA", "2001:db8::21");
			z = dns.zone("b.test", "a.test", want);
			assertEquals(List.of(), records(z, "ftp.b.test", "AAAA"));
			assertTrue(dnsDir.getChild("shared.zone.previous").exists());

			// stop it from the Servers list: its page has nothing to show; start it again
			suite.serverAction("dns", "stop");
			assertFalse(suite.types().contains("Dns"));
			assertEquals(false, dns.status().get("available"));
			assertThrows(IllegalStateException.class, () -> dns.zone("b.test", "a.test", want));
			suite.serverAction("dns", "start");
			assertTrue(suite.types().contains("Dns"));
			z = dns.zone("b.test", "a.test", want);
			assertEquals(List.of("192.0.2.80 (inherited)"), records(z, "www.b.test", "A"), "the journal kept the changes");

			// without a usable key the page says why
			app.getSettings().set("parley.mail.admin.dnsKeyFile", dir().getChild("nothing.key").getAbsolutePath());
			assertNotNull(dns.status().get("keyProblem"));
			assertThrows(IllegalStateException.class, () -> dns.setRecords("b.test", "www", "A", 300, List.of("192.0.2.1")));
		} finally {
			if (app != null) {
				app.close();
			}
			if (launcher != null) {
				launcher.close();
			}
			DnsServer.setShutdown(false);
			cs.stop();
			UnicastRemoteObject.unexportObject(registry, true);
			for (String k : System.getProperties().stringPropertyNames()) {
				if (!before.contains(k) && k.startsWith("parley.")) {
					System.clearProperty(k);
				}
			}
		}
	}
}
