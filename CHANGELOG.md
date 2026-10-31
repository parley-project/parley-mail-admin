# Changelog

## parley-mail-admin 2.0.0 (unreleased)

### Added

- **Spam providers**: on the Settings page's SMTP tab (the SMTP server's own settings), "Check for spam" and a Spam
  provider list made of the providers this installation finds at run time (parley-smtp's `SpamProviders`: Rspamd,
  SpamAssassin and any plugin); choosing one shows what it is and its own settings (shown only while it is chosen, the
  rarely needed ones last), with a Test connection button that asks the scanner with the form's settings, saved or not.
  Beside them the refuse and Junk scores, size and timeout. The Domains page has a Spam provider column and button: a domain
  follows the choice, turns checking off, or uses another provider (kept in the queue's `spam.domains`, applied at
  once). New calls `GET /spam/providers` and `POST /spam/test`. The SMTP server reads its own spam settings when it
  starts, so changing them asks for a restart of the SMTP service. Needs parley-smtp's spam providers.
- **FTP**: the services `ftp` and `ftps` (parley-ftp's servers in the mail suite) are on the Dashboard with the other
  services (state, port, TLS, connections, start, stop, restart), have an FTP tab on the Settings page (`parley.mail.ftp.*`
  and `parley.mail.ftps.*`, in `parley-mail-ftp.properties`), and an FTP source on the Logs page. FTP logs in the mail
  users (`name@domain`) and serves each one's folder in the mail store. Needs parley-ftp.

### Changed

- The SMTP history can be on another file system than the mail: the queue's `historyFileSource` (a setting on the
  Settings page, e.g. `FileProxy` for local disk; it needs the history folder) is used to find the folder, as the queue
  does (`QueueHistory.folder`), so the History page reads the same files. Needs parley-smtp with `historyFileSource`.
- One DKIM key for every domain: the primary domain's key is the shared one (the queue's `dkim.shared`, which the admin
  keeps set to the primary domain while it has a key), and a domain without a key of its own signs with it; with Parley
  DNS it gets the key's record from the shared zone, so it needs no zone file. A key made for another
  domain is its own, and its record goes in that domain's zone. The Domains page shows which key each domain signs
  with, and its DNS records list the shared key's record for a domain without its own.
- Domains, aliases and DKIM keys are used at once, with no restart: after a change on the Domains or Aliases
  page the admin has the running SMTP servers read the queue's domains, aliases file and DKIM keys again
  (parley-smtp's `MailQueueMXBean.reload`, `MailSuite.reloadQueue`) and, for a domain or a DKIM key, the
  Parley DNS server its zones. Those changes no longer mark SMTP as needing a restart; when a server can't use
  one, the change is saved and the page says why.
- Administrators manage their own domain only, unless their domain is the primary one (the first
  administrator's, `parley.mail.admin.primaryDomain`), whose administrators manage every domain. A domain's
  administrator has its domain's users, aliases and DKIM keys, and the queue and history of its mail; the
  other pages and calls are the primary's (403). An administrator whose name has no domain can't log in, and
  the first administrator's name needs a domain.
  The primary domain's administrators may change it to another local domain that has an administrator.
- Each server is alone with its own settings (Parley's server design rule): the SMTP, Authentication, IMAP and
  POP3 tabs have a server picker and edit each server's own prefix (`parley.mail.submission.*` is no longer
  taken for the relay's), and a change marks only the servers that read it as needing a restart.
- New Store and Queue tabs edit the store's and the queue's own settings files, where the servers read them
  (`.parley-store.properties` in the mail root, `queue.properties` in the relay's queue folder, on the mail
  root's file system): the store's sharing and locks; the queue's domains, aliases, postmaster, delivery,
  smart host, history, DKIM signing, ARC sealing and DMARC reports. The Queue tab takes the place of Delivery,
  and takes the queue's settings from the SMTP and Authentication tabs. `parley.mail.sharedStore`,
  `parley.mail.lockDomain` and the `parley.mail.smtp.*` delivery settings are gone. Setting one of them makes
  its file; the Dashboard says when one is missing (the servers refuse to start).
- The host name (Settings > SMTP) is one field: the queue's and each SMTP server's.
- The Domains page writes the queue's `domains` and `dkim.keys`, the Aliases page edits the file the queue's
  `aliases` names (and names its own when the queue names none). Nothing is taken from another setting: no
  domain from the host name, no host name from the machine's.
- The Queue page and the Dashboard read the queue of the first running SMTP server on the relay's queue folder
  (`MailQueue,name=<id>`).
- `parley.mail.admin.jmxPasswordFile`: the JMX password from a file (the parley-servers package's
  `/etc/parley/jmx/admin.password`) when `parley.mail.admin.jmxPassword` isn't set.
- A file in a folder this process may not add files to, but may write (the store's settings in the mail root),
  is written in place.
- The theme picker, who is logged in and Log out are at the right of the top bar, on the page title's line, instead
  of at the bottom of the side bar.
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
  the queue's `historyDays` (30). It and the history's on/off and folder settings are on Settings > Queue (it was on Settings > Admin).
- The Logs page also shows log files: the application's, the servers' (`logFile` in a settings file, with its
  rotated copies) and those `parley.mail.admin.logFiles` names.
- DKIM key files made on the Domains page get the process's usual permissions instead of owner-only ones, so
  servers running as another user of the same group can read them.
- Files rewritten through a temporary file (settings, users, aliases, DKIM keys made again) keep the original's
  permissions and group.
- The Dashboard's connections dialog (client, user, TLS) comes from parley-net's `ParleyServerMBean.getConnectionList()`.

### Added

- The first version: a web application (war) that runs parley-smtp, parley-imap and parley-pop3 in one
  process and manages them from a browser: the services' status, start, stop and restart; domains (several,
  with DKIM keys and DNS records); users and aliases per domain; the SMTP queue and its history; the logs;
  every setting. Needs parley-smtp 2.0.0's queue events and virtual domains.
