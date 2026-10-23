package us.bringardner.parley.mail.admin;

import java.io.File;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.scan.StandardJarScanner;

/**
 * Runs the web application in an embedded Tomcat, straight from the project: Run As (or Debug As)
 * &gt; Java Application in Eclipse, then open http://localhost:8080/parley-mail-admin/.
 * Breakpoints in the application and in the Parley projects open in the workspace work as usual.
 * <p>
 * System properties: {@code port} (8080), {@code contextPath} (/parley-mail-admin) and
 * {@code parley.mail.admin.home} (~/.parley/mail-admin). The pages (src/main/webapp) are served
 * from the project, so a change to them shows on the next reload; the classes come from the
 * launch's class path.
 */
public final class DevServer {

	private final Tomcat tomcat = new Tomcat();
	private final Context context;

	public DevServer(int port, String contextPath, File webapp, File baseDir) {
		tomcat.setBaseDir(baseDir.getAbsolutePath());
		tomcat.setPort(port);
		tomcat.getConnector(); // make the HTTP connector
		context = tomcat.addWebapp(contextPath, webapp.getAbsolutePath());
		// one copy of every class: all from the launch's class path (as one war would have them),
		// so the Parley logger (made by parley-core) and the application share the log buffer
		context.setParentClassLoader(DevServer.class.getClassLoader());
		((StandardContext) context).setDelegate(true);
		StandardJarScanner scanner = (StandardJarScanner) context.getJarScanner();
		scanner.setScanClassPath(false);
		scanner.setScanManifest(false);
	}

	public void start() throws LifecycleException {
		tomcat.start();
	}

	public void stop() throws LifecycleException {
		tomcat.stop();
		tomcat.destroy();
	}

	public int getPort() {
		return tomcat.getConnector().getLocalPort();
	}

	public Context getContext() {
		return context;
	}

	/** The project's src/main/webapp (the working directory is the project in Eclipse and Maven). */
	public static File webappDirectory() {
		File f = new File("src/main/webapp");
		if (!f.isDirectory()) {
			throw new IllegalStateException("Run DevServer with the parley-mail-admin project as the working directory (can't find " + f.getAbsolutePath() + ")");
		}
		return f;
	}

	public static void main(String[] args) throws Exception {
		int port = Integer.getInteger("port", 8080);
		String path = System.getProperty("contextPath", "/parley-mail-admin");
		File base = new File(System.getProperty("java.io.tmpdir"), "parley-mail-admin-tomcat");
		DevServer server = new DevServer(port, path, webappDirectory(), base);
		server.start();
		System.out.println("Parley Mail Admin: http://localhost:" + server.getPort() + path + "/");
		server.tomcat.getServer().await();
	}
}
