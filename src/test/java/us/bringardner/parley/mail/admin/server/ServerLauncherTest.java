package us.bringardner.parley.mail.admin.server;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.mail.admin.TestFiles;
import us.bringardner.parley.net.server.ParleyConnection;
import us.bringardner.parley.net.server.ServerMain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ServerLauncher: providers found at run time, settings files in order (the family's, then the
 * server's), start, stop and restart (with JMX too), a server stopping by itself, the shared context.
 */
public class ServerLauncherTest {

	/** A server that keeps the setting it was made with; it "listens" until stopped. */
	public static class FakeService implements ServerMain.Service {
		final String value;
		final CountDownLatch stopped = new CountDownLatch(1);
		final Object shared;

		/** the thread that started it */
		volatile String startedOn;

		FakeService(String value, Object shared) {
			this.value = value;
			this.shared = shared;
		}

		@Override
		public String name() {
			return "Fake";
		}

		@Override
		public void startAndWait(long timeoutMillis) throws IOException {
			startedOn = Thread.currentThread().getName();
			if ("fail".equals(value)) {
				throw new IOException("can't listen");
			}
			if ("die".equals(value)) {
				Thread t = new Thread(() -> {
					try {
						Thread.sleep(300);
					} catch (InterruptedException e) {
						// stop now
					}
					stopped.countDown();
				});
				t.setDaemon(true);
				t.start();
			}
		}

		@Override
		public int port() {
			return 4242;
		}

		@Override
		public void stop(long timeoutMillis) {
			stopped.countDown();
		}

		@Override
		public void awaitStop() throws InterruptedException {
			stopped.await();
		}

		@Override
		public int connections() {
			return 3;
		}

		@Override
		public List<ParleyConnection> connectionList() {
			return List.of(new ParleyConnection("127.0.0.1:50000", "tony", true), new ParleyConnection("10.0.0.2:50001", null, false));
		}
	}

	static final AtomicReference<FakeService> last = new AtomicReference<>();

	public static class FakeProvider implements ServerProvider {
		@Override
		public String id() {
			return "fake";
		}

		@Override
		public String description() {
			return "Fake server";
		}

		@Override
		public List<String> sharedSettingsFiles() {
			return Collections.singletonList("parley-family.properties");
		}

		@Override
		public boolean enabled() {
			return !"off".equals(System.getProperty("parley.fake.value"));
		}

		@Override
		public ServerMain.Service create(ServerContext context) throws Exception {
			Object shared = context.shared("fake.shared", Object.class, Object::new);
			FakeService s = new FakeService(System.getProperty("parley.fake.value"), shared);
			last.set(s);
			return s;
		}
	}

	@TempDir
	Path tempDir;

	/** The @TempDir, on the local file system */
	private FileSource dir() throws java.io.IOException {
		return TestFiles.local(tempDir);
	}

	ServerLauncher launcher;

	@AfterEach
	public void close() {
		if (launcher != null) {
			launcher.close();
		}
		System.clearProperty("parley.fake.value");
		System.clearProperty("parley.fake.family");
	}

	private void write(String name, String text) throws IOException {
		TestFiles.writeBytes(dir().getChild(name), text.getBytes(StandardCharsets.UTF_8));
	}

	private ServerLauncher launcher(String... args) throws Exception {
		launcher = LauncherMain.launcher("FakeProcess", args, dir(), "fake");
		launcher.init();
		return launcher;
	}

	@Test
	public void unknownServer() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new ServerLauncher("x", new LaunchSettings(new java.util.Properties()), Arrays.asList("nope"), dir(), new ServerContext()));
		assertTrue(e.getMessage().contains("nope") && e.getMessage().contains("fake"), e.getMessage());
	}

	@Test
	public void noServersListed() {
		IOException e = assertThrows(IOException.class, () -> LauncherMain.launcher("x", new String[0], dir()));
		assertTrue(e.getMessage().contains("parley.servers"), e.getMessage());
	}

	@Test
	public void serversFromLauncherFile() throws Exception {
		write(LauncherMain.LAUNCHER_FILE, "parley.servers=fake\n");
		launcher = LauncherMain.launcher("x", new String[0], dir());
		assertEquals(Arrays.asList("fake"), Arrays.asList(launcher.getServers()));
	}

	@Test
	public void settingsInOrder() throws Exception {
		write("parley-family.properties", "parley.fake.value=family\nparley.fake.family=yes\n");
		write("parley-fake.properties", "parley.fake.value=own\n");
		launcher();
		launcher.startAll();
		assertEquals("own", last.get().value);
		assertEquals("yes", System.getProperty("parley.fake.family"));
		assertEquals(2, launcher.getSettingsFiles().length);
	}

	@Test
	public void commandLineWins() throws Exception {
		write("parley-fake.properties", "parley.fake.value=own\n");
		launcher("-Dparley.fake.value=cmd");
		launcher.startAll();
		assertEquals("cmd", last.get().value);
	}

	@Test
	public void startStopRestartWithJmx() throws Exception {
		write("parley-fake.properties", "parley.fake.value=one\n");
		launcher();
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		ObjectName server = new ObjectName("us.bringardner.parley:type=Server,name=fake");
		ObjectName proc = new ObjectName("us.bringardner.parley:type=Launcher,name=FakeProcess");
		assertTrue(mbs.isRegistered(server));
		assertTrue(mbs.isRegistered(proc));
		assertEquals("STOPPED", mbs.getAttribute(server, "State"));

		mbs.invoke(server, "start", null, null);
		assertEquals("RUNNING", mbs.getAttribute(server, "State"));
		assertEquals("FakeProcess-control", last.get().startedOn, "started on the launcher's thread, not the JMX caller's");
		assertEquals(4242, mbs.getAttribute(server, "Port"));
		assertEquals(3, mbs.getAttribute(server, "Connections"));
		// an MXBean: open types for a generic client, ParleyConnection through an MXBean proxy
		javax.management.openmbean.CompositeData[] raw = (javax.management.openmbean.CompositeData[]) mbs.getAttribute(server, "ConnectionList");
		assertEquals(raw.length, launcher.getServer("fake").getConnectionList().length);
		if (raw.length > 0) {
			assertTrue(raw[0].containsKey("remote") && raw[0].containsKey("user") && raw[0].containsKey("secure"));
		}
		ParleyConnection[] conns = javax.management.JMX.newMXBeanProxy(mbs, server, ParleyServerMBean.class).getConnectionList();
		assertEquals(2, conns.length);
		assertEquals("127.0.0.1:50000", conns[0].getRemote());
		assertEquals("tony", conns[0].getUser());
		assertTrue(conns[0].isSecure());
		assertNull(conns[1].getUser());
		FakeService first = last.get();

		write("parley-fake.properties", "parley.fake.value=two\n");
		mbs.invoke(server, "restart", null, null);
		FakeService second = last.get();
		assertEquals("two", second.value);
		assertEquals(0, first.stopped.getCount());
		assertSame(first.shared, second.shared, "the shared object lives as long as the launcher");

		mbs.invoke(server, "stop", null, null);
		assertEquals("STOPPED", mbs.getAttribute(server, "State"));
		assertEquals(0, ((javax.management.openmbean.CompositeData[]) mbs.getAttribute(server, "ConnectionList")).length);
		assertNull(mbs.getAttribute(server, "Error"));

		String[] servers = (String[]) mbs.getAttribute(proc, "Servers");
		assertEquals(Arrays.asList("fake"), Arrays.asList(servers));
		launcher.close();
		launcher = null;
		assertTrue(!mbs.isRegistered(server));
	}

	@Test
	public void stopsByItself() throws Exception {
		launcher();
		CountDownLatch failed = new CountDownLatch(1);
		launcher.setOnFailure(id -> failed.countDown());
		launcher.startAll();
		last.get().stopped.countDown(); // not asked to stop
		assertTrue(failed.await(5, TimeUnit.SECONDS));
		assertEquals(ServerLauncher.State.FAILED, launcher.getState("fake"));
		assertNotNull(launcher.getServer("fake").getError());
		assertTrue(!launcher.isAnyRunning());
	}

	@Test
	public void startFails() throws Exception {
		launcher("-Dparley.fake.value=fail");
		launcher.startAll(); // a server that can't start is FAILED: nothing is thrown
		assertEquals(ServerLauncher.State.FAILED, launcher.getState("fake"));
		assertTrue(launcher.getServer("fake").getError().contains("can't listen"), launcher.getServer("fake").getError());
		assertTrue(!launcher.isAnyRunning());
	}

	@Test
	public void turnedOffBySettings() throws Exception {
		write("parley-fake.properties", "parley.fake.value=off\n");
		launcher();
		assertEquals(ServerLauncher.State.OFF, launcher.getState("fake"));
		launcher.startAll(); // skipped, not an error
		assertEquals(ServerLauncher.State.OFF, launcher.getState("fake"));
		assertThrows(IOException.class, () -> launcher.start("fake"));
		write("parley-fake.properties", "parley.fake.value=on\n");
		launcher.restart("fake"); // the settings are read again
		assertEquals(ServerLauncher.State.RUNNING, launcher.getState("fake"));
	}

	@Test
	public void processExitsWithOneWhenEveryServerIsOff() throws Exception {
		Process p = process("-Dparley.servers=fake", "-Dparley.fake.value=off");
		try {
			assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the process ends");
			assertEquals(1, p.exitValue());
			assertTrue(read(p).contains("nothing to run"));
		} finally {
			p.destroyForcibly();
		}
	}

	@Test
	public void secondLauncherGetsInstanceName() throws Exception {
		launcher();
		ServerLauncher other = LauncherMain.launcher("FakeProcess", new String[0], dir(), "fake");
		try {
			other.init();
			assertTrue(ManagementFactory.getPlatformMBeanServer()
					.isRegistered(new ObjectName("us.bringardner.parley:type=Server,name=fake,instance=2")));
		} finally {
			other.close();
		}
	}

	// ------------------------------------------------------------------ as a process (ServerMain.main)

	private Process process(String... args) throws Exception {
		FileSource javaCommand = FileSourceFactory.fileProxyFactory.createFileSource(System.getProperty("java.home")).getChild("bin").getChild("java");
		List<String> cmd = new java.util.ArrayList<>(Arrays.asList(javaCommand.getPath(), "-cp", System.getProperty("java.class.path")));
		// the process uses this JVM's file system (To-Do 18.17), unless the command line names one
		String factory = System.getProperty(FileSourceFactory.PROP_DEFAULT_FACTORY);
		String option = "-D" + FileSourceFactory.PROP_DEFAULT_FACTORY + "=";
		if (factory != null && Arrays.stream(args).noneMatch(a -> a.startsWith(option))) {
			cmd.add(option + factory);
		}
		cmd.add(LauncherMain.class.getName());
		cmd.addAll(Arrays.asList(args));
		ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
		pb.environment().put("PARLEY_CONF", dir().getChild("parley.properties").getPath());
		return pb.start();
	}

	private static String read(Process p) throws IOException {
		return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
	}

	@Test
	public void processRunsUntilStopped() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(!FileSourceFactory.isWindows(), "SIGTERM: not on Windows");
		write(LauncherMain.LAUNCHER_FILE, "parley.servers=fake\n");
		Process p = process();
		try {
			java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder();
			String line;
			boolean started = false;
			while (!started && (line = r.readLine()) != null) {
				out.append(line).append('\n');
				started = line.equals("Fake server (fake) started on port 4242");
			}
			assertTrue(started, out.toString());
			Thread.sleep(500);
			assertTrue(p.isAlive(), "the process lives while the server runs");
			p.destroy(); // SIGTERM
			assertTrue(p.waitFor(20, TimeUnit.SECONDS), "SIGTERM stops the server and the process");
		} finally {
			p.destroyForcibly();
		}
	}

	@Test
	public void processExitsWithOneWhenTheServerStopsByItself() throws Exception {
		Process p = process("-Dparley.servers=fake", "-Dparley.fake.value=die");
		try {
			assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the process ends");
			assertEquals(1, p.exitValue(), "a failure, for the service manager");
		} finally {
			p.destroyForcibly();
		}
	}

	@Test
	public void processExitsWithOneWhenNoServerStarts() throws Exception {
		Process p = process("-Dparley.servers=fake", "-Dparley.fake.value=fail");
		try {
			assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the process ends");
			assertEquals(1, p.exitValue(), "nothing runs: a failure, for the service manager");
		} finally {
			p.destroyForcibly();
		}
	}

	/** One server that can't start doesn't stop the others, or the process */
	@Test
	public void processRunsTheOthersWhenOneCantStart() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(!FileSourceFactory.isWindows(), "SIGTERM: not on Windows");
		write("parley-cfga.properties", "parley.cfg.root=/a\n");
		Process p = process("-Dparley.servers=fake,cfga", "-Dparley.fake.value=fail");
		try {
			java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder();
			String line;
			boolean failed = false;
			boolean started = false;
			while (!(failed && started) && (line = r.readLine()) != null) {
				out.append(line).append('\n');
				failed |= line.startsWith("Fake server (fake) failed: can't listen");
				started |= line.startsWith("Configured cfga (cfga) started");
			}
			assertTrue(failed && started, out.toString());
			Thread.sleep(1000);
			assertTrue(p.isAlive(), "the process lives while cfga runs: " + out);
			p.destroy(); // SIGTERM
			assertTrue(p.waitFor(20, TimeUnit.SECONDS));
		} finally {
			p.destroyForcibly();
		}
	}

	@Test
	public void processExitsWithOneForAnUnknownServer() throws Exception {
		Process p = process("parley.servers", "fake,nope");
		try {
			assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the process ends");
			assertEquals(1, p.exitValue());
			String out = read(p);
			assertTrue(out.contains("Unknown server [nope]") && out.contains("known: [fake"), out);
		} finally {
			p.destroyForcibly();
		}
	}

	/**
	 * A server started by an authenticated JMX client (a remote admin, as the packages' mail service
	 * has it) starts: its threads aren't made in the client's security context.
	 */
	@Test
	public void startedByAnAuthenticatedJmxClient() throws Exception {
		write("parley-fake.properties", "parley.fake.value=one\n");
		javax.management.MBeanServer mbs = javax.management.MBeanServerFactory.newMBeanServer();
		launcher = new ServerLauncher("AuthProcess", new LaunchSettings(new java.util.Properties()), List.of("fake"), dir(), new ServerContext(mbs));
		launcher.init();
		int port;
		try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
			port = ss.getLocalPort();
		}
		java.rmi.registry.Registry registry = java.rmi.registry.LocateRegistry.createRegistry(port);
		javax.management.remote.JMXServiceURL url = new javax.management.remote.JMXServiceURL("service:jmx:rmi:///jndi/rmi://localhost:" + port + "/jmxrmi");
		java.util.Map<String, Object> env = new java.util.HashMap<>();
		env.put(javax.management.remote.JMXConnectorServer.AUTHENTICATOR, (javax.management.remote.JMXAuthenticator) c -> {
			javax.security.auth.Subject s = new javax.security.auth.Subject();
			s.getPrincipals().add(new javax.management.remote.JMXPrincipal("admin"));
			return s;
		});
		javax.management.remote.JMXConnectorServer cs = javax.management.remote.JMXConnectorServerFactory.newJMXConnectorServer(url, env, mbs);
		cs.start();
		try (javax.management.remote.JMXConnector c = javax.management.remote.JMXConnectorFactory.connect(url,
				java.util.Map.of(javax.management.remote.JMXConnector.CREDENTIALS, new String[] { "admin", "pw" }))) {
			ParleyServerMBean bean = javax.management.JMX.newMXBeanProxy(c.getMBeanServerConnection(),
					new ObjectName("us.bringardner.parley:type=Server,name=fake"), ParleyServerMBean.class);
			bean.start();
			assertEquals("RUNNING", bean.getState());
			assertEquals("AuthProcess-control", last.get().startedOn);
			bean.restart();
			assertEquals("RUNNING", bean.getState());
			bean.stop();
			assertEquals("STOPPED", bean.getState());
		} finally {
			cs.stop();
			java.rmi.server.UnicastRemoteObject.unexportObject(registry, true);
		}
	}
}
