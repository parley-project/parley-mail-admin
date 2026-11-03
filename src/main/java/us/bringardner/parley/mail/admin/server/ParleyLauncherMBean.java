package us.bringardner.parley.mail.admin.server;

/**
 * A {@link ServerLauncher} as JMX sees it ({@code us.bringardner.parley:type=Launcher,name=NAME}).
 *
 * @since 2.0.0
 */
public interface ParleyLauncherMBean {

	/** The ids of the servers it runs */
	String[] getServers();

	/** The settings files it reads, in order */
	String[] getSettingsFiles();

	/** When it started (ms since the epoch) */
	long getStarted();

	/** Read the settings files again (the servers use the new settings when they are restarted) */
	void reloadSettings() throws Exception;

	void startAll() throws Exception;

	void stopAll() throws Exception;

	/** Stop every server and end the process normally (when it's run by ServerMain) */
	void shutdown();
}
