package us.bringardner.parley.mail.admin.server;

import us.bringardner.parley.dns.server.DnsServer;

import us.bringardner.parley.net.server.ServerMain;

/**
 * The DNS server for the suite's launcher, as {@code dns} in {@code parley.servers}: the launcher
 * starts and stops it (the server reads {@code parley-dns.properties}, or the file {@code parley.dns.configFile} names, when it starts), and shows it over JMX as {@code us.bringardner.parley:type=Server,name=dns} like every
 * Parley server; while it runs, {@code us.bringardner.parley:type=Dns,name=dns} ({@link DnsServerMXBean})
 * adds its zones and records.
 * <p>
 * The resolver and its cache are one per JVM, as is the {@code dns} id, so one DNS server runs per launcher.
 *
 * @since 2.0.0
 */
public class DnsServerProvider implements ServerProvider {

	@Override
	public String id() {
		return "dns";
	}

	@Override
	public String description() {
		return "DNS";
	}

	@Override
	public ServerMain.Service create(ServerContext context) throws Exception {
		DnsServer svr = new DnsServer();
		return DnsServer.service(svr, "DNS server", bean -> context.register("Dns", id(), bean), context::unregister);
	}
}
