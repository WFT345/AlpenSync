# AlpenSync Privacy Policy

Effective date: 13 August 2026

This notice describes how the AlpenSync Android application (“AlpenSync”, “the app”) handles information. It is written for a client that you install yourself (F-Droid, GitHub, or a sideloaded APK). AlpenSync is not distributed on Google Play.

AlpenSync is free software under the GNU General Public License version 3 (GPL-3.0-only). The source is the product. You can read exactly what the app does.

If this notice and the GPL conflict on a licensing point, the GPL controls.

## 1. Who we are

AlpenSync is an independent, community open-source project. It is not a company, not a hosted service, and not affiliated with, endorsed by, or supported by Proton AG or the GrapheneOS project.

The people responsible for this notice are the copyright holders of AlpenSync as listed in the source repository.

Site: https://alpensync.org
Project: https://github.com/WFT345/AlpenSync

There is no AlpenSync server, account system, or analytics backend. We do not operate a service that receives, stores, or sells your contacts.

## 2. What AlpenSync is

AlpenSync is a local Android client. It signs you into your own Proton account, unlocks your keys on the device, and syncs Proton contacts with Android’s system Contacts provider so the stock dialer and Contacts app can use them.

Cryptography runs on the device. The app is a client, never a relay.

## 3. Information we do not collect

The AlpenSync project does not:

- run analytics, advertising, crash reporters, or “phone-home” telemetry
- use Google Play Services, Firebase, FCM, or a Google account
- sell, rent, or share information with data brokers
- operate accounts, mailing lists, or cloud storage of its own

The Android manifest, Gradle dependencies, and `network_security_config.xml` are auditable. Intended network destinations are Proton hosts only (`proton.me` and its subdomains, including `verify.proton.me` for Proton’s human-verification page). Cleartext traffic is disabled.

## 4. Information processed on your device

When you use AlpenSync, the following is processed **on the handset you control**:

- Proton username and password (and a TOTP code if you use 2FA), only long enough to sign in. The password is not written to logs, screenshots of state, or analytics.
- Session tokens and key material needed to stay signed in and to decrypt Proton contact cards. At rest these are wrapped with Android Keystore (AES-256-GCM). They are wiped on logout.
- Decrypted contact content, so it can be written into Android Contacts and used as the sync base. Canonical vCards and conflict copies kept by the app are Keystore-wrapped at rest and wiped on logout.
- Sync bookkeeping (IDs, hashes, timestamps, outbox state) in a local database.

We cannot read this information unless we are looking at a device we do not have. We do not receive a copy.

## 5. Information sent to Proton

To sign in and to sync, the app sends traffic to Proton’s systems, including:

- authentication material required by Proton’s login (SRP; not a reusable copy of your password stored by AlpenSync)
- two-factor codes when Proton asks for them
- requests to list, create, update, and delete Proton contacts
- encrypted contact cards in the form Proton’s API expects

Proton is an independent controller of the data it holds. Proton’s own terms and privacy policy apply to your Proton account:

- https://proton.me/legal/privacy
- https://proton.me/legal/terms

AlpenSync does not control Proton’s retention, staffing, or subprocessors.

## 6. Android Contacts and other apps

The point of a contacts sync client is that contacts become visible to the phone. After a sync, contact data lives **decrypted** in Android’s system Contacts provider.

Any other app you grant the contacts permission can read that provider. The dialer and messengers need this. AlpenSync cannot offer end-to-end secrecy for contacts that must appear in the stock Contacts app. This is the same class of exposure as any contacts sync client. It is a product limit, not an accident.

AlpenSync is written to read and write the rows it created under its own account. It is not designed to scan or upload contacts that belong to Phone, Google, Samsung, or other accounts.

## 7. Permissions

AlpenSync asks for:

- **Internet** — to reach Proton.
- **Read/write contacts** — to put Proton contacts in the stock Contacts app and to push changes you make there back to Proton.
- **Account and sync settings** — to register the AlpenSync account with Android’s sync framework.

You can refuse contacts permission. Sync will not run until you grant it. You can revoke it in system settings. You can log out to wipe AlpenSync’s session, wrapped contact store, and related local state.

## 8. Human verification

Proton may show a captcha or similar check (`verify.proton.me`) inside an in-app WebView. That page is Proton’s. The WebView enables the JavaScript Proton’s page requires. AlpenSync does not use that WebView for advertising or tracking of its own.

## 9. Retention

- **On your device:** until you log out, clear app data, or uninstall. Logout is intended to wipe session material, Keystore-wrapped contact ciphertext, and the related key alias.
- **On Proton:** according to Proton’s policies and your Proton account.
- **On AlpenSync project systems:** none. There is no AlpenSync user database.

Uninstalling the app removes its private data. It does not delete contacts that already exist in Android Contacts or on Proton. Delete those where they live (Contacts app / Proton) if that is what you want.

## 10. Legal bases (EEA / UK / similar)

If a data-protection law applies to this processing, the bases are:

- **Your request to use the app** (signing in, granting contacts permission, tapping Sync) — performing that request.
- **Your consent**, where a permission prompt or an explicit action is the consent.
- **Legitimate interests** of running a local, non-tracking open-source client that only talks to the service you asked it to talk to, balanced against your interest in a de-Googled phone that still has an address book.

You can withdraw consent by logging out, revoking permissions, and uninstalling.

## 11. Your rights

Because AlpenSync does not hold a copy of your data on a server, we cannot look up “your account” on our side.

You can:

- access and edit contacts in the stock Contacts app and on Proton
- stop processing by logging out and uninstalling
- export or delete Proton data through Proton’s own tools
- read the source to see what is processed
- open a repository issue or security advisory for questions about this notice

Statutory rights (access, erasure, portability, objection, complaint to a supervisory authority) against **Proton** are exercised with Proton. Statutory rights against **AlpenSync contributors** are limited by the fact that we do not store your contacts. Ask, and we will tell you honestly what we do and do not have.

## 12. International transfers

AlpenSync contributors do not transfer your contacts to themselves. The app on your phone communicates with Proton. Any cross-border transfer is Proton’s, under Proton’s terms.

## 13. Children

AlpenSync is not directed at children. Do not use it if you cannot agree to Proton’s terms or cannot grant Android permissions.

## 14. Security

Tokens and contact ciphertext the app keeps are Keystore-wrapped at rest. Transport is TLS to Proton. There is no certificate pinning, on purpose: you and other auditors must be able to inspect traffic.

Limits we state plainly (see also `THREAT_MODEL.md` in the source):

- contacts in the system provider are readable on an unlocked phone
- a compromised or rooted device is out of scope
- Proton and the network can see that a device syncs
- Proton’s unofficial API may change

Report vulnerabilities as described in `SECURITY.md`. Do not file public issues for security flaws.

## 15. Open source

The GPL-3.0-only source is the specification of this policy. If a build you did not compile yourself disagrees with the tagged source, trust the source and do not use that build.

## 16. Changes

If this notice changes in a material way, we will update the effective date and the copy in the source repository and in the app. Continued use after an update means you are using the app under the new notice.

## 17. Contact

General: https://github.com/WFT345/AlpenSync  
Security: `SECURITY.md` in that repository (private advisory; no public issue)

This notice is provided so you can decide whether to run the software. It is not a warranty and not legal advice to you.
