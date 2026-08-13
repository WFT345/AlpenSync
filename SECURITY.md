# Security

AlpenSync is a local Android client. It talks only to Proton hosts, stores
session material in the Android Keystore, and does not run a backend.

## Report a vulnerability

Do **not** open a public issue for a security bug.

Use a private GitHub security advisory:

https://github.com/WFT345/AlpenSync/security/advisories/new

Include:

- What you found, in plain language
- Affected version or commit
- Steps to reproduce
- What an attacker would need (physical phone, another app, network, GitHub)

We will acknowledge the report and say whether we can reproduce it.

## What is in scope

- Theft or export of Proton session tokens or mailbox key material
- Bypass of the Keystore wrap, or tokens landing in AccountManager plaintext
- Contacts from other accounts on the device being read or written
- WebView human-verification escaping `verify.proton.me`
- Secrets committed to this repository
- Privilege issues in the exported authenticator or sync adapter

## What is not a surprise

- Contacts sit decrypted in Android's Contacts provider after a successful
  sync. That is how the stock dialer sees them.
- The unofficial Proton API can change or break. That is a reliability
  issue, not a reportable vulnerability by itself.
- The source is public. Design notes in comments are not secrets.

## Release builds

Release APKs are minified. There is no Play signing key in this repository.
Do not send private keys or Proton passwords in a report.
