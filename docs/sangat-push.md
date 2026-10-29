# Sangat push notifications

When someone starts a Sangat (a listen-together room), especially from the car, the other
family phones can get a "tap to join" notification even if the Saraswati app is closed. The
hub sends it through Firebase Cloud Messaging (FCM).

## What the push contains

Nothing but "something changed". The message is a data push whose only field is
`type: "sangat_changed"`. It has no names, no room codes and no songs. When the app wakes, it
asks the hub itself (`GET /sangat/live`) what is going on and builds the notification from
that answer.

The hub sends one when:

- someone creates a room (to every other phone);
- a host's phone is marked as being in the car (`PUT /sangat/car` with `carMode: true`);
- a host leaves on purpose and that ends or empties the room, so the other phones can clear
  their notification.

It never sends one because a connection dropped or came back (cars lose signal in tunnels),
and never to the phone that caused it.

## What the hub stores

One FCM token per phone, in the `push_tokens` table (device id, token, time). The app registers
it with `PUT /push/token` (`{"token": "..."}`) and removes it with `DELETE /push/token`. The
token is deleted when:

- the phone is revoked;
- the phone unregisters;
- another phone registers the same token (an app install belongs to one phone, so a phone that registers a token already held by another phone takes it over; the token is only an address, not a secret that grants anything);
- FCM reports the token as dead (app uninstalled or token expired).

Only approved phones are ever pushed to.

## Setting and rotating the secret

The hub needs a Firebase service account with permission to send messages. In the Firebase
console: Project settings, Service accounts, Generate new private key. That downloads a JSON
file. Then, from the `worker` directory:

```
npx wrangler secret put FCM_SERVICE_ACCOUNT < key.json
```

Delete `key.json` afterwards and never commit it. To rotate: generate a new key in the
console, run the same command with the new file, then delete the old key in the console. The
hub drops its cached access token when the secret changes, so no restart is needed.

## When the secret is not set

Push is silently off. The hub makes no network call at all, the token routes still work (so
phones can register ahead of time), and nothing else changes.

## Limits

- Pushes about a Sangat starting (a new room, or a phone going into car mode): at most 6 per
  phone and 30 for the whole family per 10 minutes. Both limits are checked before either is
  used, so a refusal by one never costs the other. Over a limit the push is skipped quietly.
- Switching car mode on for a room that is already in car mode sends nothing.
- Pushes about a Sangat ending (the host leaves and the room empties, or a revoked car phone)
  have no limit, so passengers always get the chance to clear their notification.
- Token registration: 20 per hour per phone.
- Each request to Google has a 5 second limit. The hub never waits for a push before answering
  the phone or handling a socket message, and a failed push is not retried.
- Messages are high priority and expire after 5 minutes; a newer one replaces an older one
  that has not been delivered yet.
- A token FCM calls unregistered, or one that belongs to a different sender, is deleted. A
  plain "not found" (for example a wrong project id in the secret) deletes nothing.
- Nothing is sent while the `sangat` feature switch is off.
