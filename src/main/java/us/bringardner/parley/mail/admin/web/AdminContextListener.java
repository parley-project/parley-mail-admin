package us.bringardner.parley.mail.admin.web;

import java.io.File;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import us.bringardner.parley.mail.admin.AdminApp;
import us.bringardner.parley.mail.admin.AdminHome;

/**
 * Starts the mail suite when the web application starts, and stops it when it stops (undeployed,
 * reloaded or the container shuts down). The admin home comes from the
 * {@code parley.mail.admin.home} system property or context parameter (see {@link AdminHome}).
 */
public class AdminContextListener implements ServletContextListener {

	/** The servlet context attribute holding the {@link AdminApp}. */
	public static final String APP = AdminApp.class.getName();

	@Override
	public void contextInitialized(ServletContextEvent event) {
		ServletContext ctx = event.getServletContext();
		// in code rather than web.xml's <cookie-config><attribute>, which Eclipse WTP's descriptor model can't read
		ctx.getSessionCookieConfig().setAttribute("SameSite", "Strict");
		File home = AdminHome.defaultDirectory(ctx.getInitParameter(AdminHome.HOME_PROPERTY));
		try {
			AdminApp app = AdminApp.start(home);
			ctx.setAttribute(APP, app);
			ctx.log("Parley mail admin started; home " + app.getHome().getDirectory());
		} catch (Exception e) {
			ctx.log("Parley mail admin can't start (home " + home + ")", e);
			throw new IllegalStateException("Parley mail admin can't start: " + e.getMessage(), e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		Object app = event.getServletContext().getAttribute(APP);
		if (app instanceof AdminApp) {
			((AdminApp) app).close();
		}
		event.getServletContext().removeAttribute(APP);
	}
}
