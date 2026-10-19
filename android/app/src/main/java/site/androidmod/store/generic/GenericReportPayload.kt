package site.androidmod.store.generic

/**
 * The report the customer sends about [record]'s patch: what the record says of the app, their
 * choice and note, and this store and phone. The note is stripped, and a blank one is left out.
 */
fun reportPayload(
    record: GenericRecord,
    worked: Boolean,
    note: String,
    storeVersionCode: Long,
    sdkInt: Int,
): GenericReportRequest = GenericReportRequest(
    packageName = record.packageName,
    appName = record.label,
    versionName = record.versionName,
    versionCode = record.versionCode,
    worked = worked,
    note = note.trim().ifEmpty { null },
    storeVersionCode = storeVersionCode,
    androidSdk = sdkInt,
)

/** Every field of [request], one per line: exactly what the report screen shows under "This is what will be sent:". */
fun payloadLines(request: GenericReportRequest): List<String> = listOfNotNull(
    "App: ${request.appName}",
    "Package: ${request.packageName}",
    "Version: ${request.versionName} (${request.versionCode})",
    "Result: ${if (request.worked) "It works" else "It doesn't work"}",
    request.note?.let { "Note: $it" },
    "Store version: ${request.storeVersionCode}",
    "Android SDK: ${request.androidSdk}",
)
