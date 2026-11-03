package us.bringardner.parley.mail.admin.server;

/** Zone files for the DNS provider's tests (as parley-dns's TestInheritedZones writes them) */
final class TestZones {

	private TestZones() {
	}

	static String primary(int serial, String extra) {
		return "$TTL 300\n@\tIN\tSOA\tns1 postmaster ( "+serial+" 3600 1800 1209600 300 )\n"
				+"\t\tNS\tns1\n"
				+"\t\tMX\t10 mx1\n"
				+"\t\tTXT\t\"v=spf1 mx -all\"\n"
				+"ns1\tIN\tA\t10.0.0.53\n"
				+"mx1\tIN\tA\t10.0.0.25\n"
				+"www\tIN\tA\t10.0.0.80\n"
				+"webmail\tIN\tCNAME\twww\n"
				+"_dmarc\tIN\tTXT\t\"v=DMARC1; p=quarantine\"\n"
				+"*.wild\tIN\tA\t10.0.0.99\n"
				+extra;
	}

	static String child(int serial, String extra) {
		return "$INHERIT\n"
				+"$TTL 300\n@\tIN\tSOA\tns1.primary.test. postmaster ( "+serial+" 3600 1800 1209600 300 )\n"
				+"\t\tMX\t20 other.example.\n"
				+"special\tIN\tA\t10.1.0.1\n"
				+"webmail\tIN\tA\t10.1.0.2\n"
				+"www\tIN\tCNAME\tspecial\n"
				+extra;
	}
}
