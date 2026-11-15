package ai.pleos.fornow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GleoContractTest {
    private fun parse(raw: String) = ForNowVoiceCommand.parse(ForNowVoiceCommand.TEMPORARY,GleoParameters.parse(raw))
    private fun rejects(status: String="asking", block:()->Unit) {
        try { block(); fail("Invalid command accepted") }
        catch(e:GleoInput){ assertEquals(status,e.status) }
    }
    @Test fun parsesPackedDurationAndSetting() {
        assertEquals(ForNowVoiceCommand.Temporary("fan",180_000),parse("""{"control":"fan","durationSeconds":180}"""))
    }
    @Test fun parsesJsonEncodedPayloadFromOfficialExample() {
        val raw=JSONObject.quote("""{"control":"seatCool","durationSeconds":60}""")
        assertEquals(ForNowVoiceCommand.Temporary("seatCool",60_000),parse(raw))
    }
    @Test fun acceptsTypedIndividualExtras() {
        val p=GleoParameters.parse(null,mapOf("control" to "ambient","durationSeconds" to 300))
        assertEquals(ForNowVoiceCommand.Temporary("ambient",300_000),ForNowVoiceCommand.parse(ForNowVoiceCommand.TEMPORARY,p))
    }
    @Test fun missingDurationAsksRatherThanInventingATimer() { rejects { parse("""{"control":"fan"}""") } }
    @Test fun refusesUnboundedFractionalAndStringDurations() {
        listOf("0","-60","61","60.5","3600","\"60\"","null","true").forEach { value -> rejects { parse("""{"control":"fan","durationSeconds":$value}""") } }
    }
    @Test fun unsupportedAudioOrMultipleSettingsCannotReachHardware() {
        listOf("media","windows","fan,seatHeat").forEach { rejects { parse("""{"control":"$it","durationSeconds":60}""") } }
    }
    @Test fun automaticModeRequiresExplicitBoolean() {
        rejects { ForNowVoiceCommand.parse(ForNowVoiceCommand.AUTOMATIC,GleoParameters.parse("{}")) }
        rejects { ForNowVoiceCommand.parse(ForNowVoiceCommand.AUTOMATIC,GleoParameters.parse("""{"enabled":"true"}""")) }
        assertEquals(ForNowVoiceCommand.Automatic(false),ForNowVoiceCommand.parse(ForNowVoiceCommand.AUTOMATIC,GleoParameters.parse("""{"enabled":false}""")))
    }
    @Test fun rejectsAmbiguousOrMalformedTransport() {
        listOf("[]","true","{} garbage","{","null", " ".repeat(8193)).forEach{rejects{GleoParameters.parse(it)}}
        rejects{GleoParameters.parse("{}",mapOf("enabled" to true))}
    }
    @Test fun rejectsUnknownFieldsAndFunctions() {
        rejects { parse("""{"control":"fan","durationSeconds":60,"force":true}""") }
        rejects("fail"){ForNowVoiceCommand.parse("WIPE_SETTINGS",GleoParameters.parse("{}"))}
    }
    @Test fun restoreNeedsNoInventedParameters() {
        assertEquals(ForNowVoiceCommand.Restore,ForNowVoiceCommand.parse(ForNowVoiceCommand.RESTORE,GleoParameters.parse(null)))
    }
    @Test fun callbackDataIsAJsonArrayEncodedAsAString() {
        val result=JSONObject(GleoResult("success","The timer is running.",listOf(mapOf("durationSeconds" to 60))).json(ForNowVoiceCommand.TEMPORARY))
        assertEquals(ForNowVoiceCommand.TEMPORARY,result.getString("functionName"))
        assertEquals(60,JSONArray(result.getString("data")).getJSONObject(0).getInt("durationSeconds"))
        assertEquals("voice",result.getString("mode"))
    }
    @Test fun installedSchemaMatchesHandlersAndManifest() {
        val schema=JSONObject(File("src/main/assets/schema.json").readText())
        val functions=schema.getJSONArray("actionCalls")
        assertTrue(functions.length()<=3)
        val names=(0 until functions.length()).map{functions.getJSONObject(it).getJSONObject("function").getString("name")}.toSet()
        assertEquals(ForNowVoiceCommand.actions,names)
        assertEquals("ai.pleos.fornow",schema.getString("packageName"))
        val manifest=File("src/main/AndroidManifest.xml").readText()
        names.forEach{assertTrue(manifest.contains("android:name=\"$it\""))}
    }
}
