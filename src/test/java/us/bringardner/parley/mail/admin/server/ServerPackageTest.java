package us.bringardner.parley.mail.admin.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.admin.TestFiles;

/**
 * The server side of the application (this package, parley-mail-admin-VERSION-server.jar) runs in
 * the parley-mail service without the web application: it uses no web classes (the servlet API,
 * JSON) and nothing else of parley-mail-admin.
 */
public class ServerPackageTest {

	private static final String[] FORBIDDEN = { "jakarta/servlet", "javax/servlet", "com/fasterxml", "org/apache/catalina" };
	private static final String ADMIN = "us/bringardner/parley/mail/admin/";
	private static final String SERVER = ADMIN + "server/";

	@Test
	public void usesNoWebClasses() throws Exception {
		// the classes' folder (a file: URI: on the local disk)
		FileSource folder = FileSource.of(ServerLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		FileSource pkg = folder;
		for (String seg : SERVER.split("/")) {
			pkg = pkg.getChild(seg);
		}
		FileSource[] classes = pkg.listFiles(f -> f.getName().endsWith(".class"));
		assertTrue(classes != null && classes.length > 10, "the package's classes in " + pkg.getPath());
		List<String> found = new ArrayList<>();
		for (FileSource c : classes) {
			// the class file's names (its constant pool) are ISO-8859-1 readable for ASCII names
			String text = new String(TestFiles.bytes(c), StandardCharsets.ISO_8859_1);
			for (String f : FORBIDDEN) {
				if (text.contains(f)) {
					found.add(c.getName() + " uses " + f);
				}
			}
			int i = 0;
			while ((i = text.indexOf(ADMIN, i)) >= 0) {
				if (!text.startsWith(SERVER, i)) {
					found.add(c.getName() + " uses " + text.substring(i, Math.min(text.length(), i + 60)).split("[^\\w/$]")[0]);
				}
				i += ADMIN.length();
			}
		}
		assertEquals(List.of(), found);
	}
}
