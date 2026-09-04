package ai.champi.actions

import ai.champi.core.actions.ActionSettingsRepository
import ai.champi.providers.api.ActionProvider
import ai.champi.providers.api.ToolCall
import ai.champi.providers.api.ToolResult
import ai.champi.providers.api.ToolSpec
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class CreateEventArgs(
    val title: String,
    val startEpochMs: Long,
    val durationMinutes: Int,
    val description: String? = null,
)

@Serializable
private data class EventCreatedResult(val status: String, val eventId: Long)

@Serializable
private data class EventHandoffResult(val status: String)

@Serializable
private data class EventErrorResult(val status: String, val message: String)

private const val PARAMS_SCHEMA = """{"type":"object","properties":{"title":{"type":"string"},""" +
    """"startEpochMs":{"type":"integer"},"durationMinutes":{"type":"integer"},""" +
    """"description":{"type":"string"}},"required":["title","startEpochMs","durationMinutes"]}"""

/**
 * `create_event` prefers [Intent.ACTION_INSERT] against the system calendar app — pre-filled but
 * user-reviewed, and needs no calendar permission. When WRITE_CALENDAR/READ_CALENDAR are already
 * granted it inserts directly via [CalendarContract.Events] instead.
 *
 * The confirmation dialog the spec (issue #40) wants in front of every direct insert isn't built
 * yet, so the direct-insert path currently fires unconfirmed whenever the permission happens to be
 * granted — same scope boundary as #41's missing per-action toggle (#38).
 */
@Singleton
class CalendarActionProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: ActionSettingsRepository,
) : ActionProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override val specs: List<ToolSpec> = listOf(
        ToolSpec("create_event", "Creates a calendar event", PARAMS_SCHEMA),
    )

    override suspend fun invoke(call: ToolCall): ToolResult {
        if (call.name != "create_event") {
            return error(call, "Unknown tool: ${call.name}")
        }
        if (!settings.calendarActionsEnabled.first()) {
            return error(call, "Calendar actions are disabled in settings.")
        }

        val args = runCatching { json.decodeFromString<CreateEventArgs>(call.argumentsJson) }
            .getOrElse { return error(call, "Invalid arguments: ${it.message}") }
        if (args.title.isBlank()) return error(call, "title must not be blank")
        if (args.durationMinutes <= 0) return error(call, "durationMinutes must be positive")

        return if (hasCalendarPermissions()) {
            insertDirectly(call, args)
        } else {
            openPrefilledCalendarIntent(args)
            ToolResult(callId = call.id, resultJson = json.encodeToString(EventHandoffResult("opened_calendar_app")))
        }
    }

    /** Exposed separately from [invoke] so instrumented tests can force this path without relying
     *  on ambient device permission state. */
    internal fun insertDirectly(call: ToolCall, args: CreateEventArgs): ToolResult {
        val calendarId = defaultCalendarId() ?: return error(call, "No writable calendar found on this device")
        val endEpochMs = args.startEpochMs + args.durationMinutes * 60_000L

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, args.title)
            put(CalendarContract.Events.DESCRIPTION, args.description)
            put(CalendarContract.Events.DTSTART, args.startEpochMs)
            put(CalendarContract.Events.DTEND, endEpochMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return error(call, "Calendar insert failed")
        val eventId = ContentUris.parseId(uri)

        return ToolResult(
            callId = call.id,
            resultJson = json.encodeToString(EventCreatedResult("created", eventId)),
        )
    }

    private fun openPrefilledCalendarIntent(args: CreateEventArgs) {
        val endEpochMs = args.startEpochMs + args.durationMinutes * 60_000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            setPackage(calendarAppPackage())
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, args.startEpochMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endEpochMs)
            putExtra(CalendarContract.Events.TITLE, args.title)
            putExtra(CalendarContract.Events.DESCRIPTION, args.description)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun calendarAppPackage(): String? {
        val probe = Intent(Intent.ACTION_INSERT).apply { data = CalendarContract.Events.CONTENT_URI }
        return context.packageManager
            .resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }

    private fun hasCalendarPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun defaultCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= " +
            "${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    private fun error(call: ToolCall, message: String): ToolResult =
        ToolResult(
            callId = call.id,
            resultJson = json.encodeToString(EventErrorResult("error", message)),
            isError = true,
        )
}
