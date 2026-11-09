package us.bringardner.parley.mail.admin.dns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.dns.server.SharedZoneText;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.admin.TestFiles;

/** The zone files the admin writes (the DNS server only reads them): the shared zone's edits and a new inheriting zone (To-Do 15.5) */
public class ZoneFilesTest {

	static final String SHARED = "$TTL 300\n@\tIN\tSOA\tns1 postmaster ( 10 3600 1800 1209600 300 )\n"
			+ "\t\tNS\tns1\n\t\tMX\t10 mx1\nns1\tIN\tA\t10.0.0.53\nmx1\tIN\tA\t10.0.0.25\nwww\tIN\tA\t10.0.0.80\n"
			+ "_dmarc\tIN\tTXT\t\"v=DMARC1; p=quarantine\"\n";

	@TempDir
	Path tempDir;
	FileSource dir;
	FileSource f;
	final AtomicInteger reloads = new AtomicInteger();
	final Runnable reload = reloads::incrementAndGet;

	@BeforeEach
	public void setup() throws Exception {
		dir = TestFiles.local(tempDir);
		f = TestFiles.write(dir.getChild("shared.zone"), SHARED);
	}

	private List<String> shared() throws Exception {
		List<String> ret = new ArrayList<>();
		for (SharedZoneText.Record r : ZoneFiles.sharedRecords(f)) {
			ret.add(r.getName() + " " + r.getType() + " " + r.getTtl() + " " + r.getData());
		}
		return ret;
	}

	@Test
	public void editingTheSharedZone() throws Exception {
		// added: the serial raised, the file before kept, the server told
		ZoneFiles.addShared(f, "FTP", "a", 600, List.of("10.0.0.21", " 10.0.0.22 ", ""), reload);
		assertTrue(shared().contains("ftp A 600 10.0.0.21") && shared().contains("ftp A 600 10.0.0.22"), shared().toString());
		assertTrue(shared().get(0).contains(" postmaster 11 "), shared().get(0));
		assertTrue(dir.getChild("shared.zone.previous").exists());
		assertEquals(1, reloads.get());
		assertTrue(TestFiles.read(f).startsWith(ZoneFiles.HEADER));
		// adding one it has changes nothing but the TTL of the set
		ZoneFiles.addShared(f, "ftp", "A", 1200, List.of("10.0.0.21"), reload);
		assertTrue(shared().contains("ftp A 1200 10.0.0.22"), shared().toString());

		// every field of a record changed: name, type, TTL and data
		ZoneFiles.changeShared(f, "ftp", "A", "10.0.0.22", "files", "CNAME", 900, "www", reload);
		assertTrue(shared().contains("files CNAME 900 www"), shared().toString());
		assertFalse(shared().contains("ftp A 1200 10.0.0.22"), shared().toString());

		// the SOA: changed; a serial that isn't higher is raised past the file's
		String soa = ZoneFiles.sharedRecords(f).get(0).getData();
		ZoneFiles.changeShared(f, "@", "SOA", soa, "@", "SOA", 300, "ns1 hostmaster 5 3600 1800 1209600 600", reload);
		String now = shared().get(0);
		assertTrue(now.contains(" hostmaster 14 3600 1800 1209600 600"), now);

		ZoneFiles.deleteShared(f, "files", "CNAME", "www", reload);
		assertFalse(shared().toString().contains("files"), shared().toString());

		// refused, and the file left as it was
		String before = TestFiles.read(f);
		int r = reloads.get();
		assertThrows(IllegalArgumentException.class, () -> ZoneFiles.addShared(f, "x", "MX", 300, List.of("ten mail"), reload), "not MX data");
		assertThrows(IllegalArgumentException.class, () -> ZoneFiles.addShared(f, "x.example.", "A", 300, List.of("10.0.0.1"), reload), "absolute");
		assertThrows(IllegalArgumentException.class, () -> ZoneFiles.addShared(f, "@", "SOA", 300, List.of(soa), reload), "a second SOA");
		assertThrows(IllegalArgumentException.class, () -> ZoneFiles.addShared(f, "x", "RRSIG", 300, List.of("x"), reload), "signing makes it");
		assertThrows(IllegalArgumentException.class, () -> ZoneFiles.addShared(f, "x", "BOGUS", 300, List.of("x"), reload));
		assertThrows(IllegalArgumentException.class, () -> ZoneFiles.addShared(f, "x", "A", 300, List.of(" "), reload), "no value");
		assertThrows(IllegalArgumentException.class, () -> ZoneFiles.deleteShared(f, "@", "SOA", now.substring(now.indexOf("SOA ") + 8), reload));
		assertThrows(IllegalArgumentException.class, () -> ZoneFiles.changeShared(f, "www", "A", "10.0.0.80", "@", "SOA", 300, "a b 1 1 1 1 1", reload));
		assertThrows(IllegalStateException.class, () -> ZoneFiles.deleteShared(f, "nothere", "A", "10.0.0.1", reload), "not there");
		assertEquals(before, TestFiles.read(f));
		assertEquals(r, reloads.get(), "the server isn't told of a refused change");
	}

	@Test
	public void aFileThatIsntHereIsSaidSo() throws Exception {
		FileSource missing = dir.getChild("nowhere.zone");
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> ZoneFiles.addShared(missing, "x", "A", 300, List.of("10.0.0.1"), reload));
		assertTrue(e.getMessage().contains("same file system"), e.getMessage());
	}

	@Test
	public void aNewInheritingZone() throws Exception {
		FileSource z = ZoneFiles.createInheriting(dir, f, "New.Example.");
		assertEquals(dir.getChild("new.example.txt"), z);
		String text = TestFiles.read(z);
		assertTrue(text.contains("$INHERIT\n"), text);
		assertTrue(text.contains("ns1.new.example."), text);

		assertThrows(IllegalArgumentException.class, () -> ZoneFiles.createInheriting(dir, f, "new.example"), "there already");
		// an old journal of the name would be replayed into the new zone: refused
		TestFiles.write(dir.getChild("old.example.txt.jnl"), "base 1\n");
		assertThrows(IllegalArgumentException.class, () -> ZoneFiles.createInheriting(dir, f, "old.example"));
		assertFalse(dir.getChild("old.example.txt").exists());
		assertThrows(IllegalArgumentException.class, () -> ZoneFiles.createInheriting(dir, f, "../evil"));
		assertThrows(IllegalArgumentException.class, () -> ZoneFiles.createInheriting(dir, f, "bad_name.example"));
	}
}
