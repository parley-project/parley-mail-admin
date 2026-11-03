package us.bringardner.parley.mail.admin.server;

import us.bringardner.parley.dns.server.DnsServer;
import us.bringardner.parley.dns.server.DnsServerMXBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.net.server.ServerContext;

/**
 * DNS run by parley-net's launcher, as "dns" in parley.servers: it shows over JMX as a Server like
 * every Parley server, with its Dns bean while it runs, and can be stopped and started again.
 */
public class TestDnsProvider {

	private File dir;
	private Properties saved;
	private ServerLauncher launcher;
	private MBeanServer mbs;

	private static int freePort() throws Exception {
		try(ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}

	@BeforeEach
	public void setup() throws Exception {
		saved = (Properties)System.getProperties().clone();
		dir = Files.createTempDirectory("dnsprovider").toFile();
		File zones = new File(dir, "zones");
		zones.mkdir();
		try(FileWriter w = new FileWriter(new File(zones, "primary.test.txt"))) {
			w.write(TestZones.primary(10, ""));
		}
		try(FileWriter w = new FileWriter(new File(zones, "child.test.txt"))) {
			w.write(TestZones.child(5, ""));
		}
		try(FileWriter w = new FileWriter(new File(dir, "parley-dns.properties"))) {
			w.write("parley.dns.dnsDir="+dir.getAbsolutePath()+"\n"
					+"parley.dns.zoneDir="+zones.getAbsolutePath()+"\n"
					+"parley.dns.masterZone=primary.test\n"
					+"parley.dns.bindAddress=127.0.0.1\n"
					+"parley.dns.port="+freePort()+"\n"
					+"parley.dns.adminPort="+freePort()+"\n"
					+"parley.dns.recursionAvailable=false\n");
		}
		mbs = MBeanServerFactory.newMBeanServer();
		launcher = new ServerLauncher("DnsTest", new LaunchSettings(new Properties()), List.of("dns"), dir, new ServerContext(mbs));
	}

	@AfterEach
	public void cleanup() {
		launcher.close();
		DnsServer.setShutdown(false);
		System.setProperties(saved);
		deleteAll(dir);
	}

	private static void deleteAll(File f) {
		File[] l = f.listFiles();
		if( l != null ) {
			for(File c : l) {
				deleteAll(c);
			}
		}
		f.delete();
	}

	/**
	 * Made from its own configuration (To-Do 11.13): it serves what parley-dns.properties says, and
	 * the file's settings don't become the JVM's system properties, which other servers in the JVM read.
	 */
	@Test
	public void readsItsConfigurationAndLeavesTheSystemPropertiesAlone() throws Exception {
		assertTrue(new DnsServerProvider().usesConfiguration());
		launcher.init();
		launcher.startAll();
		ObjectName dnsName = new ObjectName("us.bringardner.parley:type=Dns,name=dns");
		DnsServerMXBean dns = JMX.newMXBeanProxy(mbs, dnsName, DnsServerMXBean.class);
		assertEquals("RUNNING", dns.getState());
		assertEquals(2, dns.getZones().length, "the zones of the zoneDir its configuration names");
		assertFalse(dns.isRecursionAvailable());
		for(String name : new String[] {"parley.dns.dnsDir", "parley.dns.zoneDir", "parley.dns.masterZone",
				"parley.dns.port", "parley.dns.recursionAvailable"}) {
			assertEquals(null, System.getProperty(name), name+" was copied into the system properties");
		}
	}

	@Test
	public void runsUnderTheLauncher() throws Exception {
		launcher.init();
		launcher.startAll();
		ObjectName serverName = new ObjectName("us.bringardner.parley:type=Server,name=dns");
		ObjectName dnsName = new ObjectName("us.bringardner.parley:type=Dns,name=dns");
		assertTrue(mbs.isRegistered(serverName));
		ParleyServerMBean server = JMX.newMXBeanProxy(mbs, serverName, ParleyServerMBean.class);
		assertEquals("RUNNING", server.getState());
		assertTrue(mbs.isRegistered(dnsName));
		DnsServerMXBean dns = JMX.newMXBeanProxy(mbs, dnsName, DnsServerMXBean.class);
		assertEquals("RUNNING", dns.getState());
		assertEquals(server.getPort(), dns.getTcpPort());
		assertFalse(dns.isRecursionAvailable());
		assertEquals(2, dns.getZones().length);

		server.stop();
		assertEquals("STOPPED", server.getState());
		assertFalse(mbs.isRegistered(dnsName), "the Dns bean goes with the server");

		server.start();
		assertEquals("RUNNING", server.getState());
		assertTrue(mbs.isRegistered(dnsName), "and comes back when it starts again");
		assertEquals("RUNNING", JMX.newMXBeanProxy(mbs, dnsName, DnsServerMXBean.class).getState());
	}
}
