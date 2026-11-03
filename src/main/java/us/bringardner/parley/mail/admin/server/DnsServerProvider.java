package us.bringardner.parley.mail.admin.server;

import us.bringardner.parley.dns.server.DnsServer;

import java.util.Properties;

import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerMain;

/**
 * The DNS server for the suite's launcher, as {@code dns} in {@code parley.servers}: the launcher
 * reads {@code parley-dns.properties} (or the file {@code parley.dns.configFile} names), starts and
 * stops it, and shows it over JMX as {@code us.bringardner.parley:type=Server,name=dns} like every
 * Parley server; while it runs, {@code us.bringardner.parley:type=Dns,name=dns} ({@link DnsServerMXBean})
 * adds its zones and records.
 * <p>
 * The server is made from its own configuration, like the other servers, and reads only that (its
 * TCP and admin servers and the resolver too), never the JVM's system properties. The resolver and
 * its cache are one per JVM, as is the {@code dns} id, so one DNS server runs per launcher.
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
	public String configProperty() {
		return DnsServer.PROP_DNS_PROPERTIES;
	}

	@Override
	public boolean usesConfiguration() {
		return true;
	}

	@Override
	public ServerMain.Service create(ServerContext context, Properties configuration) throws Exception {
		DnsServer svr = BaseObject.makeWith(configuration, DnsServer::new);
		return DnsServer.service(svr, "DNS server", bean -> context.register("Dns", id(), bean), context::unregister);
	}
}
