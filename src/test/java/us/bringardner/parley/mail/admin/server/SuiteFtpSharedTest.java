package us.bringardner.parley.mail.admin.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/** The FTPS server falls back to the FTP server's shared settings; the FTP server reads only its own. */
public class SuiteFtpSharedTest {

	@Test
	public void ftpsFallsBackToFtp() {
		assertArrayEquals(new String[] { "parley.mail.ftp" }, SuiteFtp.sharedWith("parley.mail.ftps"));
	}

	@Test
	public void ftpHasNoFallback() {
		assertArrayEquals(new String[0], SuiteFtp.sharedWith("parley.mail.ftp"));
	}

	/** The FTP server's root is in the mail store's file system, and is made when it is missing */
	@Test
	public void rootsAreInTheMailStoresFileSystem() throws Exception {
		MemoryFileSourceFactory store = new MemoryFileSourceFactory();
		FileSource mailRoot = store.createFileSource("/mail");
		mailRoot.mkdirs();
		FileSource root = SuiteFtp.rootsFolder(mailRoot, "/ftp");
		assertSame(store, root.getFileSourceFactory());
		assertEquals("/ftp", root.getAbsolutePath());
		assertTrue(root.isDirectory());
		// a second start finds it
		assertTrue(SuiteFtp.rootsFolder(mailRoot, "/ftp").isDirectory());
	}

	/** A folder that is missing is made, in the mail store's file system, with the folders above it */
	@Test
	public void makesAMissingFolder() throws Exception {
		MemoryFileSourceFactory store = new MemoryFileSourceFactory();
		FileSource mailRoot = store.createFileSource("/mail");
		mailRoot.mkdirs();
		FileSource made = SuiteFtp.makeFolder(mailRoot, "/ftp/a.test");
		assertSame(store, made.getFileSourceFactory());
		assertTrue(made.isDirectory());
		assertTrue(SuiteFtp.makeFolder(mailRoot, "/ftp/a.test").isDirectory());
	}
}
