# Sangat push (app side)

How a closed phone learns a family Sangat started. The hub sends a data-only FCM message `{"type":"sangat_changed"}`;
`SangatPushService` (gms flavor only) asks the hub for `/sangat/live` and `SangatPushHandler` posts or cancels the
"X started a Sangat" notifications. `SangatPushRegistrar` registers this phone's FCM token with `PUT /push/token`.

- Firebase is initialised by hand in `Application.onCreate` from `app/firebase.properties` (via BuildConfig), and only
  for the two family application ids. `FirebaseInitProvider` and token auto-init are removed.
- The Firebase Android API key in `app/firebase.properties` is public by design (every APK carries it). It is restricted
  in Google Cloud to the Firebase Installations API and the FCM Registration API only (done 2026-10-29).
- Delivery-metrics / Firelog upload is disabled by meta-data in the gms manifest, and is also blocked by the network
  security config (the firebaselogging hosts are not allowlisted).
- foss and izzy have no Firebase: their same-named `SangatPushRegistrar` does nothing.
- The registration is keyed on `sha256(deviceId + "\n" + token)` and renewed after 24 hours, so a re-pair registers
  again and a token the hub dropped (prune, restore) is back within a day; registration is also retried on every foreground.
  The announced room codes are kept in DataStore and pruned to live rooms (by the push handler and by the foreground poll);
  neither is backed up.
- A Saral phone does not register. If it had registered before being switched to Saral, it calls `DELETE /push/token` once
  and clears the stored fingerprint (a failed call is retried on the next foreground).
- The app never shows an FCM notification-type message itself: the gms manifest sets
  `firebase_messaging_notification_delegation_enabled` to false, and `SangatPushService.handleIntent` (public, not final in
  firebase-messaging 26.0.0) drops any intent with `gcm.n.*` / `gcm.notification.*` extras or `gcm.n.e` = 1 before the SDK
  sees it. Only data messages reach `onMessageReceived`.
