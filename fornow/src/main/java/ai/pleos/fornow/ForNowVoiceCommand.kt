package ai.pleos.fornow

sealed interface ForNowVoiceCommand {
    data class Temporary(val control: String, val durationMs: Long) : ForNowVoiceCommand
    data class Automatic(val enabled: Boolean) : ForNowVoiceCommand
    data object Restore : ForNowVoiceCommand
    companion object {
        const val TEMPORARY = "FORNOW_TEMPORARY_SETTING"
        const val RESTORE = "FORNOW_RESTORE_SETTINGS"
        const val AUTOMATIC = "FORNOW_AUTOMATIC_MODE"
        val actions = setOf(TEMPORARY, RESTORE, AUTOMATIC)
        val parameters = setOf("control", "durationSeconds", "enabled")
        fun parse(action: String, input: GleoParameters): ForNowVoiceCommand = when(action) {
            TEMPORARY -> {
                input.only("control", "durationSeconds")
                Temporary(input.text("control", setOf("fan", "seatHeat", "seatCool", "wheel", "ambient")), input.seconds("durationSeconds") * 1000)
            }
            RESTORE -> { input.only(); Restore }
            AUTOMATIC -> { input.only("enabled"); Automatic(input.flag("enabled")) }
            else -> throw GleoInput("fail", "This function is not supported by ForNow.")
        }
    }
}
