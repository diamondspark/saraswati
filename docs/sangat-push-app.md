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
- The registration is keyed on `sha256(deviceId + "\n" + token)` and renewed after 7 days, so a re-pair registers again.
  The announced room codes are kept in DataStore and pruned to live rooms; neither is backed up.
