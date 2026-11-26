package us.bringardner.parley.mail.admin.log;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;

/**
 * The Log4j appender that gives each entry to the {@link LogBuffer} the Logs page shows. It is named in
 * this application's {@code log4j2.xml} ({@code <LogPage name="LogPage"/>}); Log4j decides what reaches it
 * (the levels the Logs page sets are Log4j levels, see {@link LogPageHandler#setSourceLevel}).
 */
@Plugin(name = "LogPage", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public final class LogPageAppender extends AbstractAppender {

	private LogPageAppender(String name, Filter filter) {
		super(name, filter, null, true, Property.EMPTY_ARRAY);
	}

	@PluginFactory
	public static LogPageAppender create(@PluginAttribute("name") String name, @PluginElement("Filter") Filter filter) {
		return new LogPageAppender(name == null ? "LogPage" : name, filter);
	}

	@Override
	public void append(LogEvent event) {
		LogBuffer b = LogBuffer.get();
		if (b != null) {
			b.add(toPage(event.getLevel()), event.getLoggerName() == null ? "" : event.getLoggerName(),
					event.getMessage().getFormattedMessage(), event.getThrown());
		}
	}

	static LogBuffer.Level toPage(Level level) {
		if (level.isMoreSpecificThan(Level.ERROR)) {
			return LogBuffer.Level.ERROR;
		}
		if (level.isMoreSpecificThan(Level.WARN)) {
			return LogBuffer.Level.WARN;
		}
		if (level.isMoreSpecificThan(Level.INFO)) {
			return LogBuffer.Level.INFO;
		}
		if (level.isMoreSpecificThan(Level.DEBUG)) {
			return LogBuffer.Level.DEBUG;
		}
		return LogBuffer.Level.TRACE;
	}
}
