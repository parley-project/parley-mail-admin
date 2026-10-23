package us.bringardner.parley.mail.admin.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import us.bringardner.parley.brand.ParleyBrand;

/**
 * The Parley logo and Margot from parley-brand, under {@code /brand/<file>} (e.g.
 * {@code /brand/margot-smtp.svg}), so the pages carry no copies of them.
 */
public class BrandServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
		String name = req.getPathInfo() == null ? "" : req.getPathInfo().substring(1);
		if (!ParleyBrand.fileNames().contains(name)) {
			res.sendError(404);
			return;
		}
		URL url = ParleyBrand.resource(name);
		if (url == null) {
			res.sendError(404);
			return;
		}
		res.setContentType(name.endsWith(".svg") ? "image/svg+xml" : "image/png");
		res.setHeader("Cache-Control", "public, max-age=86400");
		try (InputStream in = url.openStream(); OutputStream out = res.getOutputStream()) {
			in.transferTo(out);
		}
	}
}
