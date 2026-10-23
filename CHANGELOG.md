# Changelog

## parley-mail-admin 2.0.0 (unreleased)

### Added

- The first version: a web application (war) that runs parley-smtp, parley-imap and parley-pop3 in one
  process and manages them from a browser: the services' status, start, stop and restart; domains (several,
  with DKIM keys and DNS records); users and aliases per domain; the SMTP queue and its history; the logs;
  every setting. Needs parley-smtp 2.0.0's queue events and virtual domains.
