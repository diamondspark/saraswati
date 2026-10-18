# Add project specific ProGuard rules here.
#
# SARASWATI: Task 5 — the installer app has no reflection, no JNI, and no
# serialization of its own classes (update-core does its JSON parsing
# through kotlinx.serialization's compiler-generated serializers, which
# that module's own consumers already keep working under R8 elsewhere in
# this repo), so this file intentionally stays close to empty.
-keep class dev.diamondspark.saraswati.updater.InstallResultReceiver
