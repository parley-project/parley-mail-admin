package us.bringardner.parley.mail.admin.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.net.server.FileUserRegistry;

/** The Users page keeps the lines that keep groups with no members (FileUserRegistry's group: lines). */
public class UserStoreGroupLinesTest {

	@TempDir
	File dir;

	@Test
	public void groupLinesSurviveAnEditFromTheUsersPage() throws Exception {
		File f = new File(dir, "users.txt");
		Files.write(f.toPath(), ("# users\n"
				+ "tony@a.test , secret , READ\n"
				+ "group: support@a.test\n").getBytes(StandardCharsets.UTF_8));
		UserStore store = new UserStore(f);
		store.load();
		store.update("tony@a.test", null, Arrays.asList("READ", "WRITE"), null);

		String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
		assertTrue(text.contains("group: support@a.test\n"), text);
		assertTrue(text.startsWith("# users\n"), text);

		FileUserRegistry registry = new FileUserRegistry();
		registry.initialize(f);
		ParleyPrincipal support = registry.findPrincipal("support@a.test");
		assertNotNull(support, "the group is still there");
		assertTrue(support.isGroup());
		assertEquals(2, store.get("tony@a.test").getPermissions().size());
	}
}
