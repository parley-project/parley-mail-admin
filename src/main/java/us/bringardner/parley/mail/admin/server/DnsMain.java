package us.bringardner.parley.mail.admin.server;

/**
 * The DNS server alone in its process, run by the launcher ({@link LauncherMain}), so a
 * management tool such as parley-mail-admin finds it with JMX like any other server of the suite
 * (type=Server,name=dns: start, stop, restart; and its Dns bean). Its settings are
 * parley-dns.properties in the settings folder, or the file {@code parley.dns.configFile} names.
 *
 * @since 2.0.0 (parley-dns's DnsServer.main ran it this way until the launcher moved here)
 */
public final class DnsMain {

	private DnsMain() {
	}

	public static void main(String[] args) throws Exception {
		// Log dead threads; on a JVM error (e.g. OutOfMemoryError) stop the process so a supervisor
		// restarts it (as DnsServer.main does)
		us.bringardner.parley.net.FatalErrorHandler.install(!"false".equalsIgnoreCase(
				System.getProperty(us.bringardner.parley.dns.server.DnsServer.PROP_EXIT_ON_FATAL_ERROR, "true").trim()));
		LauncherMain.launch("DnsServer", args, "dns");
	}
}
