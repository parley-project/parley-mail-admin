package us.bringardner.parley.mail.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.admin.server.StoreSetup;
import us.bringardner.parley.mail.store.MailLocks;

/**
 * StoreSetup makes the store's and the queue's settings in the store, on the file system the
 * servers' settings name (parley-servers-setup uses it for a store that isn't on the local disk),
 * found the way a server finds it, and changes nothing that is there.
 */
public class TestStoreSetup {

	@TempDir
	Path dir;

	@AfterEach
	public void close() {
		RecordingFileSourceFactory.last = null;
	}

	private Path file(String name, String text) throws IOException {
		Path p = dir.resolve(name);
		Files.writeString(p, text, StandardCharsets.UTF_8);
		return p;
	}

	private String run(String... args) throws IOException {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		new StoreSetup(new PrintStream(b, true, StandardCharsets.UTF_8)).run(args);
		return b.toString(StandardCharsets.UTF_8);
	}

	private static String read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private Path[] templates() throws IOException {
		return new Path[] { file("store.tmpl", "# the store\nsharing=cluster\n"),
				file("queue.tmpl", "# the queue\nhostname=\ndomains=\n#smartHost=x\n") };
	}

	@Test
	public void itWritesIntoTheFileSystemTheSettingsName() throws Exception {
		Path[] t = templates();
		Path settings = file("parley-mail.properties", "parley.mail.root=/mail\nparley.mail.fileSource=recording\n"
				+ "parley.mail.fileSource.host=db1.example.test\nparley.mail.fileSource.password=@file:/etc/parley/db.secret\n");
		String out = run("--settings", settings.toString(), "--store-template", t[0].toString(), "--queue-template", t[1].toString(),
				"--fill", "hostname=mx1.example.test", "--fill", "domains=", "--require", "hostname", "--require", "domains");

		RecordingFileSourceFactory f = RecordingFileSourceFactory.last;
		assertEquals("db1.example.test", f.settings.getProperty("host"), "the connection settings, as a server gives them");
		assertEquals("@file:/etc/parley/db.secret", f.settings.getProperty("password"));
		FileSource root = f.createFileSource("/mail");
		assertEquals("# the store\nsharing=cluster\n", read(root.getChild(MailLocks.STORE_SETTINGS_FILE)));
		assertEquals("# the queue\nhostname=mx1.example.test\ndomains=\n#smartHost=x\n",
				read(root.getChild(".smtp-queue").getChild("queue.properties")));
		assertTrue(out.contains("made /mail on recording .parley-store.properties"), out);
		assertTrue(out.contains("set hostname=mx1.example.test in /mail on recording .smtp-queue/queue.properties"), out);
		assertTrue(out.contains("missing domains= in"), out);
		assertFalse(out.contains("missing hostname"), out);
	}

	@Test
	public void itChangesNothingThatIsThere() throws Exception {
		Path[] t = templates();
		Path store = dir.resolve("mail");
		Path settings = file("parley-mail.properties", "parley.mail.root=" + store + "\nparley.mail.fileSource=fileproxy\n");
		run("--settings", settings.toString(), "--store-template", t[0].toString(), "--queue-template", t[1].toString(),
				"--fill", "hostname=a.example.test", "--fill", "domains=example.test");
		Files.writeString(store.resolve(".parley-store.properties"), "sharing=host\n");

		Path other = file("other.tmpl", "sharing=off\n");
		String out = run("--settings", settings.toString(), "--store-template", other.toString(), "--queue-template", other.toString(),
				"--fill", "hostname=b.example.test", "--fill", "domains=other.test", "--require", "hostname");
		assertEquals("", out, "nothing to do");
		assertEquals("sharing=host\n", Files.readString(store.resolve(".parley-store.properties")));
		String q = Files.readString(store.resolve(".smtp-queue/queue.properties"));
		assertTrue(q.contains("hostname=a.example.test\n") && q.contains("domains=example.test\n"), q);
	}

	@Test
	public void aQueueElsewhereUnderTheRoot() throws Exception {
		Path[] t = templates();
		Path store = dir.resolve("mail");
		Path settings = file("parley-mail.properties", "parley.mail.root=" + store + "\nparley.mail.fileSource=fileproxy\n");
		run("--settings", settings.toString(), "--queue", "queues/relay", "--queue-template", t[1].toString());
		assertTrue(Files.isRegularFile(store.resolve("queues/relay/queue.properties")));
		assertThrows(IOException.class, () -> run("--settings", settings.toString(), "--queue", "../out", "--queue-template", t[1].toString()));
	}

	@Test
	public void aLaterSettingsFileWins() throws Exception {
		Path[] t = templates();
		Path first = file("a.properties", "parley.mail.root=/nowhere\nparley.mail.fileSource=nosuch\n");
		Path second = file("b.properties", "parley.mail.root=filesource:/mail?sourcetype=recording&host=db2.example.test\n");
		run("--settings", first.toString(), "--settings", second.toString(), "--store-template", t[0].toString());
		assertTrue(RecordingFileSourceFactory.last.createFileSource("/mail").getChild(MailLocks.STORE_SETTINGS_FILE).isFile());
	}

	@Test
	public void aStoreThatCantBeOpenedSaysWhyWithoutTheUrlsSettings() throws Exception {
		Path settings = file("parley-mail.properties", "parley.mail.root=filesource:/mail?sourcetype=nosuch&password=hunter2\n");
		IOException e = assertThrows(IOException.class, () -> run("--settings", settings.toString()));
		assertTrue(e.getMessage().startsWith("Can't open the mail store filesource:/mail?..."), e.getMessage());
		assertFalse(e.getMessage().contains("hunter2"), e.getMessage());

		Path unknown = file("u.properties", "parley.mail.root=/mail\nparley.mail.fileSource=nosuch\n");
		e = assertThrows(IOException.class, () -> run("--settings", unknown.toString()));
		assertTrue(e.getMessage().contains("There is no file system nosuch (parley.mail.fileSource)"), e.getMessage());
	}
}
