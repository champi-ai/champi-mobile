package ai.champi.core.persistence

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromRole(role: MessageRole): String = role.name

    @TypeConverter
    fun toRole(value: String): MessageRole = MessageRole.valueOf(value)

    @TypeConverter
    fun fromRoutingStage(stage: RoutingStage): String = stage.name

    @TypeConverter
    fun toRoutingStage(value: String): RoutingStage = RoutingStage.valueOf(value)

    @TypeConverter
    fun fromRoutingReason(reason: RoutingReason): String = reason.name

    @TypeConverter
    fun toRoutingReason(value: String): RoutingReason = RoutingReason.valueOf(value)
}
