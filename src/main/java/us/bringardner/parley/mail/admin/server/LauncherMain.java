package us.bringardner.parley.mail.admin.server;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerMain;

/**
 * Runs the servers of the mail suite (parley-mail-admin's application) in one process, as a service
 * (systemd, launchd) or from a shell: the servers {@code parley.servers} lists, found as
 * {@link ServerProvider}s, each made from its own configuration, managed with JMX
 * ({@link ServerLauncher}). The settings folder is parley-net's {@link ServerMain#confDir()}.
 * <p>
 * Each server stands alone: one that can't start, or stops by itself, is FAILED with its error and
 * the others keep running. The process exits only when no server is running.
 *
 * @since 2.0.0 (parley-net's ServerMain.main and launch until they moved here)
 */
public final class LauncherMain {

	private LauncherMain() {
	}

	/** The launcher's own settings file in CONF: parley.servers and any other settings */
	public static final String LAUNCHER_FILE = "parley-servers.properties";
	/** names the launcher's settings file instead of CONF/parley-servers.properties */
	public static final String LAUNCHER_CONFIG_PROPERTY = "parley.servers.configFile";

	/**
	 * The generic launcher: runs the servers {@code parley.servers} lists (comma separated ids, e.g.
	 * {@code smtp,submission,imap,pop3}), set on the command line, with java -D, or in
	 * CONF/parley-servers.properties. See {@link #launch(String, String[], String...)}.
	 */
	public static void main(String[] args) throws InterruptedException {
		launch("Parley", args);
	}

	/**
	 * Run servers in this process until it is stopped, as a service or from a shell.
	 * <ol>
	 * <li>The servers are the {@link ServerProvider}s with these ids (none: those
	 * {@code parley.servers} lists); an unknown id is an error that lists the ids there are.</li>
	 * <li>Settings, a later one winning: the launcher's file when ids aren't given
	 * (CONF/parley-servers.properties, or the file {@code parley.servers.configFile} names), each
	 * server's shared files and its own file in CONF (or the file its configFile property names),
	 * the JVM's {@code -D} options, and the command line ({@code -Dname=value} or
	 * {@code name value}).</li>
	 * <li>Each server and the launcher can be managed with JMX ({@link ServerLauncher}). A server
	 * stopped with JMX doesn't end the process; the launcher's shutdown does (exit 0), as does
	 * stopping the process (SIGTERM, a service stop). When the last running server stops by itself
	 * the process exits with 1, so a service manager can restart it.</li>
	 * <li>A server that can't start is FAILED, with its error (JMX shows both), and the others run:
	 * one server never stops the others. The process exits with 1 if the settings can't be read or
	 * no server is running.</li>
	 * </ol>
	 *
	 * @param processName for the messages and the launcher's JMX name, e.g. Pop3Server
	 */
	public static void launch(String processName, String[] args, String... ids) throws InterruptedException {
		ServerLauncher launcher;
		try {
			launcher = launcher(processName, args, ServerMain.confDir(), ids);
			launcher.getSettings().print(processName);
			launcher.init();
		} catch (Exception e) {
			failed(processName, e);
			return;
		}
		java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
		launcher.setOnFailure(id -> {
			if (!launcher.isAnyRunning()) {
				System.err.println(processName + " stopped: no server is running");
				exitLater(1);
			}
		});
		launcher.setOnShutdown(() -> exitLater(0));
		try {
			// each server starts on its own: one that can't start is FAILED, the others run
			launcher.startAll();
		} catch (Exception e) {
			launcher.close();
			failed(processName, e);
			return;
		}
		boolean any = false;
		for (String id : launcher.getServers()) {
			ParleyServerMBean s = launcher.getServer(id);
			if (launcher.getState(id) == ServerLauncher.State.RUNNING) {
				any = true;
				System.out.println(s.getDescription() + " (" + id + ") started on port " + s.getPort());
			} else {
				String why = s.getError();
				String state = launcher.getState(id) == ServerLauncher.State.FAILED ? "failed" : "is off";
				System.out.println(s.getDescription() + " (" + id + ") " + state + (why == null ? "" : ": " + why));
			}
		}
		if (!any) {
			launcher.close();
			failed(processName, new IOException("No server is running: nothing to run"));
			return;
		}
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			launcher.close();
			done.countDown();
		}, processName + "-shutdown"));
		// (the servers' threads may be daemons: the process lives as long as this waits)
		done.await();
	}

	/**
	 * A launcher for these servers, or (no ids) those parley.servers lists, with its settings
	 * files; not started.
	 */
	static ServerLauncher launcher(String processName, String[] args, File confDir, String... ids) throws IOException {
		LaunchSettings settings = new LaunchSettings(LaunchSettings.parse(args));
		List<String> list = new ArrayList<String>();
		for (String id : ids) {
			list.add(id);
		}
		// the launcher's file, when parley.servers comes from it: read again with each reload
		File listFile = null;
		if (list.isEmpty()) {
			String named = settings.early(LAUNCHER_CONFIG_PROPERTY);
			File file = named != null ? new File(named) : confDir == null ? null : new File(confDir, LAUNCHER_FILE);
			String servers = settings.early(ServerLauncher.SERVERS_PROPERTY);
			if (file != null) {
				settings.addFile(file, named != null);
				if (servers == null && file.isFile()) {
					servers = LaunchSettings.load(file).getProperty(ServerLauncher.SERVERS_PROPERTY);
					listFile = file;
				} else if (named != null && !file.isFile()) {
					throw new IOException("Can't read the settings file " + file);
				}
			}
			list.addAll(ids(servers));
			if (list.isEmpty()) {
				throw new IOException("No servers to run: set " + ServerLauncher.SERVERS_PROPERTY + " (one or more of "
						+ ServerLauncher.providers().keySet() + ")");
			}
		}
		ServerLauncher ret = new ServerLauncher(processName, settings, list, confDir, new ServerContext());
		if (listFile != null) {
			File f = listFile;
			ret.setServerList(() -> {
				if (!f.isFile()) {
					throw new IOException("Can't read the settings file " + f);
				}
				return ids(LaunchSettings.load(f).getProperty(ServerLauncher.SERVERS_PROPERTY));
			});
		}
		return ret;
	}

	/** The ids a parley.servers value lists */
	static List<String> ids(String servers) {
		List<String> ret = new ArrayList<String>();
		if (servers != null) {
			for (String id : servers.split("[,\\s]+")) {
				if (!id.isEmpty()) {
					ret.add(id);
				}
			}
		}
		return ret;
	}

	/** exit from another thread, so a JMX call or a server's thread isn't held by the shutdown hook */
	private static void exitLater(int status) {
		Thread t = new Thread(() -> System.exit(status), "parley-exit");
		t.start();
	}

	private static void failed(String name, Exception e) {
		System.err.println(name+" not started: "+(e.getMessage() != null ? e.getMessage() : e.toString()));
		System.exit(1);
	}

}
