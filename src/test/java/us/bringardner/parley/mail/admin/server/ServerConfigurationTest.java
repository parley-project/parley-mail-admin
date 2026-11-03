package us.bringardner.parley.mail.admin.server;

import us.bringardner.parley.net.server.ServerMain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Servers made from their own configuration: two servers of one launcher (one JVM) read the same
 * name from their own files and get different values; the system properties aren't touched; the
 * JVM's -D and the command line still win; a restart reads the files again.
 */
public class ServerConfigurationTest {

	/** What each configured server was made with, by id */
	static final Map<String, Properties> made = new ConcurrentHashMap<>();

	public abstract static class Configured implements ServerProvider {
		@Override
		public String description() {
			return "Configured " + id();
		}

		@Override
		public List<String> sharedSettingsFiles() {
			return Collections.singletonList("parley-cfgfamily.properties");
		}

		@Override
		public boolean usesConfiguration() {
			return true;
		}

		@Override
		public boolean enabled(Properties configuration) {
			return !"0".equals(configuration.getProperty("parley.cfg.port"));
		}

		@Override
		public String unavailable(Properties configuration) {
			return "true".equals(configuration.getProperty("parley.cfg.needsKey")) && configuration.getProperty("parley.cfg.key") == null
					? "it has no key (parley.cfg.key)"
					: null;
		}

		@Override
		public List<String> warnings(Properties configuration) {
			return configuration.getProperty("parley.cfg.old") == null ? Collections.emptyList()
					: Collections.singletonList("parley.cfg.old is no longer read");
		}

		@Override
		public ServerMain.Service create(ServerContext context, Properties configuration) {
			made.put(id(), configuration);
			return new ServerLauncherTest.FakeService(configuration.getProperty("parley.cfg.root"), null);
		}
	}

	public static class CfgA extends Configured {
		@Override
		public String id() {
			return "cfga";
		}
	}

	public static class CfgB extends Configured {
		@Override
		public String id() {
			return "cfgb";
		}
	}

	@TempDir
	File dir;

	ServerLauncher launcher;

	@AfterEach
	public void close() {
		if (launcher != null) {
			launcher.close();
		}
		made.clear();
	}

	private void write(String name, String text) throws IOException {
		Files.write(new File(dir, name).toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	private void launcher(String... args) throws Exception {
		launcher = LauncherMain.launcher("CfgProcess", args, dir, "cfga", "cfgb");
		launcher.init();
	}

	/** One server that can't start is FAILED with its error; the others start and keep running */
	@Test
	public void oneServerFailingDoesNotStopTheOthers() throws Exception {
		write("parley-cfga.properties", "parley.cfg.root=/a\n");
		write("parley-cfgb.properties", "parley.cfg.root=fail\n");
		launcher();
		launcher.startAll();
		assertEquals(ServerLauncher.State.RUNNING, launcher.getState("cfga"));
		assertEquals(ServerLauncher.State.FAILED, launcher.getState("cfgb"));
		assertTrue(launcher.getServer("cfgb").getError().contains("can't listen"), launcher.getServer("cfgb").getError());
		write("parley-cfgb.properties", "parley.cfg.root=/b\n");
		launcher.restart("cfgb"); // fixed: it starts on its own, cfga untouched
		assertEquals(ServerLauncher.State.RUNNING, launcher.getState("cfgb"));
		assertEquals(ServerLauncher.State.RUNNING, launcher.getState("cfga"));
	}

	@Test
	public void oneNameTwoValues() throws Exception {
		write("parley-cfgfamily.properties", "parley.cfg.root=/family\nparley.cfg.shared=yes\n");
		write("parley-cfga.properties", "parley.cfg.root=/a\n");
		write("parley-cfgb.properties", "parley.cfg.root=/b\n");
		launcher();
		launcher.startAll();
		assertEquals("/a", made.get("cfga").getProperty("parley.cfg.root"));
		assertEquals("/b", made.get("cfgb").getProperty("parley.cfg.root"));
		assertEquals("yes", made.get("cfga").getProperty("parley.cfg.shared"));
		assertEquals("yes", made.get("cfgb").getProperty("parley.cfg.shared"));
		assertNull(System.getProperty("parley.cfg.root"), "the system properties aren't set");
		assertNull(System.getProperty("parley.cfg.shared"));
	}

	@Test
	public void aServerDoesntSeeAnotherServersFile() throws Exception {
		write("parley-cfga.properties", "parley.cfg.onlyA=1\n");
		launcher();
		launcher.startAll();
		assertEquals("1", made.get("cfga").getProperty("parley.cfg.onlyA"));
		assertNull(made.get("cfgb").getProperty("parley.cfg.onlyA"));
	}

	@Test
	public void commandLineWinsForEveryServer() throws Exception {
		write("parley-cfga.properties", "parley.cfg.root=/a\n");
		launcher("-Dparley.cfg.root=/cmd");
		launcher.startAll();
		assertEquals("/cmd", made.get("cfga").getProperty("parley.cfg.root"));
		assertEquals("/cmd", made.get("cfgb").getProperty("parley.cfg.root"));
	}

	@Test
	public void eachServerIsTurnedOffByItsOwnSettings() throws Exception {
		write("parley-cfgb.properties", "parley.cfg.port=0\n");
		launcher();
		launcher.startAll();
		assertEquals(ServerLauncher.State.RUNNING, launcher.getState("cfga"));
		assertEquals(ServerLauncher.State.OFF, launcher.getState("cfgb"));
	}

	@Test
	public void restartReadsTheFilesAgain() throws Exception {
		write("parley-cfga.properties", "parley.cfg.root=/one\n");
		launcher();
		launcher.startAll();
		write("parley-cfga.properties", "parley.cfg.root=/two\n");
		launcher.restart("cfga");
		assertEquals("/two", made.get("cfga").getProperty("parley.cfg.root"));
		assertEquals("/two", launcher.getConfiguration("cfga").getProperty("parley.cfg.root"));
		assertTrue(Arrays.asList(launcher.getSettingsFiles()).contains(new File(dir, "parley-cfgb.properties").getAbsolutePath()));
	}

	@Test
	public void aServerThatCantRunIsSkippedWithAWarning() throws Exception {
		write("parley-cfgb.properties", "parley.cfg.needsKey=true\n");
		launcher();
		launcher.startAll(); // doesn't fail: the others start
		assertEquals(ServerLauncher.State.RUNNING, launcher.getState("cfga"));
		assertEquals(ServerLauncher.State.OFF, launcher.getState("cfgb"));
		assertEquals("it has no key (parley.cfg.key)", launcher.getServer("cfgb").getError());
		IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> launcher.start("cfgb"));
		assertTrue(e.getMessage().contains("it has no key"), e.getMessage());

		write("parley-cfgb.properties", "parley.cfg.needsKey=true\nparley.cfg.key=k\n");
		launcher.reloadSettings();
		assertEquals(ServerLauncher.State.STOPPED, launcher.getState("cfgb"), "it can run now: start it");
		assertNull(launcher.getServer("cfgb").getError());
		launcher.start("cfgb");
		assertEquals(ServerLauncher.State.RUNNING, launcher.getState("cfgb"));
	}

	@Test
	public void warningsDontStopAServer() throws Exception {
		write("parley-cfga.properties", "parley.cfg.old=1\n");
		launcher();
		launcher.startAll();
		assertEquals(ServerLauncher.State.RUNNING, launcher.getState("cfga"));
	}

	@Test
	public void parleyServersIsReadAgainOnAReload() throws Exception {
		write(LauncherMain.LAUNCHER_FILE, "parley.servers=cfga\n");
		launcher = LauncherMain.launcher("CfgProcess", new String[0], dir);
		launcher.init();
		launcher.startAll();
		assertEquals(Arrays.asList("cfga"), Arrays.asList(launcher.getServers()));

		write(LauncherMain.LAUNCHER_FILE, "parley.servers=cfga,cfgb\n");
		launcher.reloadSettings();
		assertEquals(Arrays.asList("cfga", "cfgb"), Arrays.asList(launcher.getServers()));
		assertEquals(ServerLauncher.State.STOPPED, launcher.getState("cfgb"), "listed now, not started by the reload");
		assertTrue(java.lang.management.ManagementFactory.getPlatformMBeanServer()
				.isRegistered(new javax.management.ObjectName("us.bringardner.parley:type=Server,name=cfgb")), "its bean");
		launcher.start("cfgb");
		assertEquals(ServerLauncher.State.RUNNING, launcher.getState("cfgb"));

		write(LauncherMain.LAUNCHER_FILE, "parley.servers=cfga\n");
		launcher.reloadSettings();
		assertEquals(ServerLauncher.State.RUNNING, launcher.getState("cfgb"), "taken out of the list: it runs until it is stopped");
		launcher.stop("cfgb");
		assertEquals(ServerLauncher.State.OFF, launcher.getState("cfgb"));
		assertEquals("Not listed in parley.servers", launcher.getServer("cfgb").getError());
		org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> launcher.start("cfgb"));

		write(LauncherMain.LAUNCHER_FILE, "parley.servers=cfga,nosuchserver\n");
		IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> launcher.reloadSettings());
		assertTrue(e.getMessage().contains("nosuchserver"), e.getMessage());
	}

	@Test
	public void aListGivenOnTheCommandLineIsKept() throws Exception {
		write(LauncherMain.LAUNCHER_FILE, "parley.servers=cfga,cfgb\n");
		launcher = LauncherMain.launcher("CfgProcess", new String[] { "-Dparley.servers=cfga" }, dir);
		launcher.init();
		launcher.reloadSettings();
		assertEquals(Arrays.asList("cfga"), Arrays.asList(launcher.getServers()));
	}
}
