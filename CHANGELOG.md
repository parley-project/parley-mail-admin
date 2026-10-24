# Changelog

## parley-mail-admin 2.0.0 (unreleased)

### Changed

- The servers are parley-net launcher servers managed with JMX (`ParleyServerMBean`, `ParleyLauncherMBean`,
  parley-smtp's `MailQueueMXBean`) instead of servers the application made itself. They run in the application
  (a `ServerLauncher` in its JVM) or, with `parley.mail.admin.jmxUrl` set to one or more JMX service URLs, as
  services of their own that the application manages; it then starts nothing itself and leaves them running when
  it stops. The Dashboard says where they run; a server no launcher runs is "unavailable"; a service turned off
  by its port is "off".
- The settings are the files the servers read, in the settings folder (`parley.mail.admin.settingsDirectory`,
  else parley-net's `ServerMain.confDir()`, /etc/parley or the folder of PARLEY_CONF's file, when it exists, else
  the admin home): `parley-mail.properties`, then `parley-smtp.properties`, `parley-imap.properties` and
  `parley-pop3.properties`. A new setting goes to its protocol's file. The application no longer sets system
  properties; files changed outside the pages are read again. A home made by an earlier build keeps its
  `parley-mail.properties` only when the settings folder is the home.
- The users file is read again by the servers themselves (parley-mail's shared `FileBasedAcl`); `SuiteAcl` is gone.
- The SMTP History page reads the history the SMTP servers write (`<mail root>/.smtp-history`); the application's
  own history listener and `history/` folder are gone, and "Keep SMTP history (days)" is now
  `parley.mail.smtp.queue.historyDays` (30). The history's on/off and folder settings are on Settings > Admin.
- The Logs page also shows log files: the application's, the servers' (`logFile` in a settings file, with its
  rotated copies) and those `parley.mail.admin.logFiles` names.
- DKIM key files made on the Domains page get the process's usual permissions instead of owner-only ones, so
  servers running as another user of the same group can read them.
- Files rewritten through a temporary file (settings, users, aliases, DKIM keys made again) keep the original's
  permissions and group.
- The connections dialog of the Dashboard is gone: JMX gives the number of connections, not who they are.

### Added

- The first version: a web application (war) that runs parley-smtp, parley-imap and parley-pop3 in one
  process and manages them from a browser: the services' status, start, stop and restart; domains (several,
  with DKIM keys and DNS records); users and aliases per domain; the SMTP queue and its history; the logs;
  every setting. Needs parley-smtp 2.0.0's queue events and virtual domains.
