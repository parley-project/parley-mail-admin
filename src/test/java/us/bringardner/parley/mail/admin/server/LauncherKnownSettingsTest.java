package us.bringardner.parley.mail.admin.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.admin.TestFiles;

/** The launcher warns about settings nothing reads (parley-net's KnownSettings), at start and on a reload */
public class LauncherKnownSettingsTest {

	@TempDir
	Path tempDir;

	/** The @TempDir, on the local file system */
	private FileSource dir() throws java.io.IOException {
		return TestFiles.local(tempDir);
	}

	private ServerLauncher launcher;

	@AfterEach
	public void close() {
		if (launcher != null) {
			launcher.close();
		}
	}

	private interface Action {
		void run() throws Exception;
	}

	private static String stderr(Action a) throws Exception {
		PrintStream before = System.err;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		System.setErr(new PrintStream(out, true, "UTF-8"));
		try {
			a.run();
		} finally {
			System.setErr(before);
		}
		return out.toString("UTF-8");
	}

	@Test
	public void theLauncherWarnsAtStartAndOnAReload() throws Exception {
		FileSource own = dir().getChild("parley-fake.properties");
		TestFiles.writeBytes(own, "parley.fake.value=own\nparley.fake.valeu=typo\nJPop3.port=110\n".getBytes(StandardCharsets.UTF_8));
		String err = stderr(() -> {
			launcher = LauncherMain.launcher("FakeProcess", new String[] { "-Dparley.fake.famly=x" }, dir(), "fake");
			launcher.init();
		});
		assertTrue(err.contains("FakeProcess: warning: parley.fake.valeu (" + own.getPath() + ") is not a setting"), err);
		assertTrue(err.contains("FakeProcess: warning: JPop3.port (" + own.getPath() + ") looks like a 1.x setting"), err);
		assertTrue(err.contains("FakeProcess: warning: parley.fake.famly (the command line) is not a setting"), err);
		assertFalse(err.contains("parley.fake.value "), "a known name isn't warned about: " + err);

		TestFiles.writeBytes(own, "parley.fake.value=own\nparley.fake.other=x\n".getBytes(StandardCharsets.UTF_8));
		err = stderr(() -> launcher.reloadSettings());
		assertTrue(err.contains("parley.fake.other (" + own.getPath() + ") is not a setting"), err);
		assertFalse(err.contains("valeu"), "fixed in the file: " + err);
	}
}
