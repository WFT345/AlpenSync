<p align="center">
  <img src="docs/public/icon.webp" width="112" alt="AlpenSync mark">
</p>

<h1 align="center">AlpenSync</h1>

<p align="center"><strong>Proton contacts sync app for GrapheneOS.</strong></p>

<p align="center">
  AlpenSync acts as a contacts sync client for Proton Mail on GrapheneOS<br>
  and other de-Googled Android. Two-way sync through the stock Contacts app<br>
  and dialer. Like Google Contacts sync or iCloud Contacts, without Google or Apple.
</p>

<p align="center">
  <a href="https://alpensync.org">alpensync.org</a>
</p>

<p align="center">
  <a href="https://alpensync.org"><img src="https://img.shields.io/badge/site-alpensync.org-9bb4c4?style=flat-square&labelColor=07090c" alt="alpensync.org"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-9bb4c4?style=flat-square&labelColor=07090c" alt="GPL-3.0"></a>
  <a href="#status"><img src="https://img.shields.io/badge/contacts-ready-9bb4c4?style=flat-square&labelColor=07090c" alt="Contacts ready"></a>
  <a href="#status"><img src="https://img.shields.io/badge/status-in%20development-9bb4c4?style=flat-square&labelColor=07090c" alt="In development"></a>
  <a href="#status"><img src="https://img.shields.io/badge/android-8%2B-9bb4c4?style=flat-square&labelColor=07090c" alt="Android 8+"></a>
  <a href="#status"><img src="https://img.shields.io/badge/play%20services-none-9bb4c4?style=flat-square&labelColor=07090c" alt="No Play Services"></a>
</p>

<p align="center">
  <a href="#what-it-does">What it does</a> ·
  <a href="#how-sync-works">How sync works</a> ·
  <a href="#read-this-first">Read this first</a> ·
  <a href="#build">Build</a> ·
  <a href="PRIVACY.md">Privacy</a> ·
  <a href="TERMS.md">Terms</a> ·
  <a href="LICENSE">License</a> ·
  <a href="SECURITY.md">Security</a>
</p>

<p align="center">
  <img src="docs/public/social.jpg" width="640" alt="AlpenSync">
</p>

Site: [alpensync.org](https://alpensync.org). Source: this repository.

AlpenSync is an independent GPL-3.0 Android app that acts as a **Proton Mail contacts sync client** for [GrapheneOS](https://grapheneos.org), LineageOS, and other de-Googled Android. GrapheneOS has no Google account and no Google Contacts. AlpenSync is the missing sync contacts app: it signs you into Proton, unlocks keys on the device, and writes Proton contacts into Android's system Contacts provider so the stock Contacts app, phone dialer, and messengers see them as native contacts.

There is no Google account, no Play Services, no Firebase, and no AlpenSync server. The durable copy of the address book is Proton, not the handset.

It is **not affiliated with, endorsed by, or supported by Proton AG** or the GrapheneOS project.

## What it does

- **Sync Proton contacts to GrapheneOS.** Pull the Proton Mail address book onto the phone.
- **Two-way contact sync.** Add or edit someone in the Android Contacts app; AlpenSync pushes that to Proton. Change a number in Proton Mail; the next pull brings it down.
- **Stock Contacts app and dialer.** Contacts live as native Android contacts, not in a private AlpenSync silo.
- **Survive a wipe.** Lose or factory-reset the GrapheneOS phone and the book is still in Proton. Sign in again and pull it back.
- **De-Google / leave iPhone.** Same job as Google Contacts sync or iCloud Contacts, for people who moved to Proton and a de-Googled phone.

Cryptography runs on the device. The app is a client, never a relay. It does not change how GrapheneOS stores contacts. It decrypts Proton's encrypted vCards on the phone, then writes ordinary Contacts provider rows under the AlpenSync account so the dialer works.

Calendar and mail are later. Same core, still without Google. Same idea if you searched for a GrapheneOS contacts app, a Proton contacts Android client, or a way to sync Proton Mail contacts without Play Services.

## How sync works

1. Sign in to Proton in the app (password, 2FA, and Proton's human check when it asks).
2. Grant Contacts access so the stock app and dialer can see the book.
3. Pull your Proton contacts onto the phone.
4. Edits on the phone go up on their own. They do not wait for the interval.
5. Changes on the web or another device come down on your schedule. **Sync now** pulls immediately.

If Proton drops the session, AlpenSync tells you and keeps a notice up until you sign in again. Contacts already on the phone stay put.

AlpenSync only writes its own account on the device. It does not scan Phone, Google, or Samsung contact stores, and it does not auto-merge people you already had on the handset.

## Read this first

1. **Unofficial API.** AlpenSync talks to Proton through Proton's undocumented API. Sync may break when Proton changes something.
2. **Decrypted on the phone.** Contacts live decrypted in Android's system provider. That is how the dialer sees them. Same exposure as any contacts sync app.
3. **No telemetry.** Traffic goes only to Proton hosts. You can read that in the source.
4. **Independent.** Not Proton. Not GrapheneOS. Not a store listing. The source is the product.
5. **GrapheneOS is the target.** Other OEM Contacts apps (Samsung in particular) may not offer "save to AlpenSync" for new contacts. Two-way update of an already-pulled AlpenSync contact still works. Create and delete of brand-new local contacts is verified on GrapheneOS.

Two-password Proton accounts (a separate mailbox password) are not supported yet.

## Status

Contacts sync is ready to use on GrapheneOS. Two-way pull, push of edits, create, and delete, plus a persistent outbox. A first pull has been verified on a Pixel running GrapheneOS.

The project is still in development. The Proton API is unofficial and can change. There is no Play Store or F-Droid build yet. Calendar and mail come later. Install from source.

| | |
| --- | --- |
| Package | `app.alpensync` |
| Min Android | 8.0 (API 26) |
| Play Services | None |
| FCM / Firebase | None |
| Telemetry | None |
| Network | Proton hosts only |
| License | [GPL-3.0-only](LICENSE) |

## Build

You need a current Android SDK (compile SDK 36) and JDK 17+.

```powershell
git clone https://github.com/WFT345/AlpenSync.git
cd AlpenSync
.\gradlew.bat :app:installDebug
```

On macOS or Linux, use `./gradlew :app:installDebug`.

The debug APK is a sideload. Review [privacy](PRIVACY.md) and [terms](TERMS.md) before you sign in with a real Proton account.

## Privacy and terms

- [Privacy policy](PRIVACY.md). No telemetry. No AlpenSync account. Traffic only to Proton.
- [Terms of use](TERMS.md). GPL-3.0 wins if a term conflicts with the license.
- [llms.txt](llms.txt). Short machine-readable summary for crawlers and assistants.
- [Security](SECURITY.md). Report vulnerabilities privately. Do not open a public issue.

## License

AlpenSync is licensed under the [GNU General Public License v3.0](LICENSE).

The bundled Inter font is under the [SIL Open Font License](app/third_party/inter/OFL.txt).

Proton and related marks belong to Proton AG. GrapheneOS is a trademark of the GrapheneOS project. AlpenSync uses those names only to describe compatibility.
