# Tally

A dark, gradient-heavy Android expense tracker that **logs your Google Wallet tap-to-pay
purchases automatically**. You can also add expenses by hand and set your balance yourself.

Built with native Kotlin + Jetpack Compose. All data stays on your phone in one private JSON file.

## How the auto-tracking works

Google Wallet has no public API for your transaction history. After every tap it posts a
notification like *"Starbucks · $4.50 with Visa ••1234"*. Tally registers a
`NotificationListenerService`, ignores every app except Google Wallet (and Play services, which
posted these alerts on older setups), and pulls out the merchant, the amount and the card's last 4
digits. That becomes an expense, gets a category guessed from the merchant name, and is subtracted
from your balance.

- **Balance** is whatever you last entered, minus every expense dated after you entered it.
- **Auto-categories**: built-in keyword rules guess the category from the merchant name
  (Starbucks → Food, Uber → Transport…). Change a payment's category once and Tally **learns** that
  place: future taps there are filed the same way, older untagged payments from it are re-filed, and
  other branches of the same brand follow. The newest tag wins straight away, so it adapts if you
  change your mind. You can see and forget what it learned under Settings → Learned places.
- **Manual expenses**: tap **+**. Tap any row to edit it; swipe left to delete it (you get an Undo).
- **Captured notifications** (Settings): the last 40 Wallet notifications and what Tally made of
  them. If a tap didn't show up, look here first.

## Install

1. Open the latest successful run under **Actions → Build APK** and download the `Tally-apk`
   artifact. Unzip it to get `app-release.apk`.
2. Install it on your phone. You'll need to allow installs from your browser or file manager.
3. Open Tally → **Connect Google Wallet** → turn on notification access for Tally.
   - **Toggle greyed out?** Android 13+ blocks this for sideloaded apps. Go to Settings → Apps →
     Tally → ⋮ → **Allow restricted settings**, then try again.
4. In Google Wallet, make sure **Transactions** notifications are switched on.
5. Tap the balance card to set your balance.

Every build is signed with the same throwaway key (`app/debug.keystore`). That means a new APK
installs over the old one and you keep your data. It's fine for personal sideloading. Don't use it
for a Play Store release.

## Build locally

You need JDK 17 and the Android SDK (API 35).

```
./gradlew testDebugUnitTest   # parser tests
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

## Code map

| Path | What |
| --- | --- |
| `parser/WalletParser.kt` | Notification text → amount, merchant, card; keyword category rules |
| `parser/Classifier.kt` | Learns merchant → category from your tagging |
| `service/WalletListenerService.kt` | Notification listener, Wallet packages only |
| `data/ExpenseStore.kt` | State and JSON persistence; skips duplicate notifications |
| `ui/` | Home, add/edit sheet, settings, theme |
