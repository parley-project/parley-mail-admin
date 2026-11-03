package us.bringardner.parley.mail.admin.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

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
		File folder = new File(ServerLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		File pkg = new File(folder, SERVER);
		File[] classes = pkg.listFiles((d, n) -> n.endsWith(".class"));
		assertTrue(classes != null && classes.length > 10, "the package's classes in " + pkg);
		List<String> found = new ArrayList<>();
		for (File c : classes) {
			// the class file's names (its constant pool) are ISO-8859-1 readable for ASCII names
			String text = new String(Files.readAllBytes(c.toPath()), StandardCharsets.ISO_8859_1);
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
