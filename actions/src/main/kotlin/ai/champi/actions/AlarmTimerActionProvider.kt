package ai.champi.actions

import ai.champi.providers.api.ActionProvider
import ai.champi.providers.api.ToolCall
import ai.champi.providers.api.ToolResult
import ai.champi.providers.api.ToolSpec
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class AlarmTimerArgs(val hours: Int, val minutes: Int, val label: String? = null)

@Serializable
private data class AlarmSuccessResult(val status: String, val label: String?, val triggersAt: Long)

@Serializable
private data class AlarmErrorResult(val status: String, val message: String)

private const val PARAMS_SCHEMA = """{"type":"object","properties":{"hours":{"type":"integer"}""" +
    ""","minutes":{"type":"integer"},"label":{"type":"string"}},"required":["hours","minutes"]}"""

/**
 * `set_alarm` schedules the next occurrence of a time-of-day; `set_timer` schedules a duration
 * from now — both share the same (hours, minutes, label?) argument shape and dispatch mechanism,
 * differing only in how [invoke] interprets hours/minutes for each tool name.
 */
@Singleton
class AlarmTimerActionProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ActionProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val nextAlarmId = AtomicInteger(1)

    override val specs: List<ToolSpec> = listOf(
        ToolSpec("set_alarm", "Sets an alarm for a specific time of day", PARAMS_SCHEMA),
        ToolSpec("set_timer", "Starts a countdown timer for a duration", PARAMS_SCHEMA),
    )

    override suspend fun invoke(call: ToolCall): ToolResult {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            return error(call, "Exact alarms aren't allowed — grant \"Alarms & reminders\" in system settings.")
        }

        val args = runCatching { json.decodeFromString<AlarmTimerArgs>(call.argumentsJson) }
            .getOrElse { return error(call, "Invalid arguments: ${it.message}") }
        if (args.hours !in 0..23 || args.minutes !in 0..59) {
            return error(call, "hours must be 0-23 and minutes 0-59")
        }

        val triggersAt = when (call.name) {
            "set_alarm" -> nextOccurrenceOf(args.hours, args.minutes)
            "set_timer" -> System.currentTimeMillis() + (args.hours * 60 + args.minutes) * 60_000L
            else -> return error(call, "Unknown tool: ${call.name}")
        }

        val alarmId = nextAlarmId.getAndIncrement()
        val receiverIntent = Intent().apply {
            setClass(context, AlarmReceiver::class.java)
            setPackage(context.packageName)
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_LABEL, args.label)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId,
            receiverIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggersAt, pendingIntent)

        return ToolResult(
            callId = call.id,
            resultJson = json.encodeToString(AlarmSuccessResult("set", args.label, triggersAt)),
        )
    }

    private fun nextOccurrenceOf(hours: Int, minutes: Int): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hours)
            set(Calendar.MINUTE, minutes)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    private fun error(call: ToolCall, message: String): ToolResult =
        ToolResult(
            callId = call.id,
            resultJson = json.encodeToString(AlarmErrorResult("error", message)),
            isError = true,
        )
}
