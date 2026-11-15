package ai.pleos.fornow

import android.content.*
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<ForNowService?>(null)
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = (binder as ForNowService.LocalBinder).service }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { service?.reconnect() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val connected = service
            val state = if (connected != null) connected.state.collectAsStateWithLifecycle().value else ForNowState()
            ForNowScreen(state, { duration, ids ->
                startForegroundService(Intent(this, ForNowService::class.java).setAction(ForNowService.START)
                    .putExtra(ForNowService.DURATION, duration).putStringArrayListExtra(ForNowService.CONTROLS, ArrayList(ids)))
            }, { action ->
                val intent = Intent(this, ForNowService::class.java).setAction(action)
                if (action == ForNowService.ARM) startForegroundService(intent) else startService(intent)
            }, { connected?.reconnect() })
        }
        val needed = listOf("android.car.permission.CAR_POWERTRAIN", "android.permission.POST_NOTIFICATIONS",
            "android.car.permission.CAR_SPEED", "android.permission.READ_PHONE_STATE", "pleos.car.permission.CAR_WINDOWS", "pleos.car.permission.CONTROL_CAR_WINDOWS",
            "pleos.car.permission.CAR_CLIMATE", "pleos.car.permission.CAR_SEATS", "pleos.car.permission.CAR_DRIVE",
            "pleos.car.permission.CAR_LIGHTS", "pleos.car.permission.CONTROL_CAR_CLIMATE",
            "pleos.car.permission.CONTROL_CAR_SEATS", "pleos.car.permission.CONTROL_CAR_LIGHTS").filter { permission ->
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED && runCatching {
                packageManager.getPermissionInfo(permission, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
            }.getOrDefault(false)
        }
        if (savedInstanceState == null) receiveGleo(intent, needed.isNotEmpty())
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveGleo(intent, false)
    }
    private fun receiveGleo(incoming: Intent, setupRequired: Boolean) {
        if (incoming.action !in ForNowVoiceCommand.actions) return
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        val call = GleoCall(this, incoming)
        try {
            ForNowVoiceCommand.parse(call.action, call.parameters(ForNowVoiceCommand.parameters))
            if (setupRequired) {
                call.reply(GleoResult("fail", "Complete initial permission setup while parked, then repeat the voice command."))
                return
            }
            startForegroundService(Intent(incoming).setComponent(ComponentName(this, ForNowService::class.java)))
        } catch (e: GleoInput) { call.reply(GleoResult(e.status, e.message)) }
        catch (_: Exception) { call.reply(GleoResult("fail", "ForNow could not start the requested operation.")) }
    }
    override fun onStart() {
        super.onStart(); bound = bindService(Intent(this, ForNowService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    override fun onStop() {
        if (bound) { unbindService(connection); bound = false }; service = null
        super.onStop()
    }
}

private val Ink = Color(0xFF203A35)
private val Muted = Color(0xFF64736C)
private val Paper = Color(0xFFF3F4F1)
private val Green = Color(0xFF245447)
private val Pale = Color(0xFFE4ECDD)
private val Line = Color(0xFFDCE2D8)
private val Asta = FontFamily(Font(R.font.asta_sans))

@Composable
private fun ForNowScreen(state: ForNowState, start: (Long, Set<String>) -> Unit, command: (String) -> Unit, reconnect: () -> Unit) {
    var minutes by rememberSaveable { mutableIntStateOf(1) }
    var selected by rememberSaveable { mutableStateOf(listOf("fan")) }
    var details by remember { mutableStateOf(false) }
    LaunchedEffect(state.connecting, state.controls.map { it.id to it.available }, state.active, state.busy) {
        if (!state.connecting && !state.active && !state.busy) {
            selected = selected.filter { id -> state.controls.any { it.id == id && it.available } }
        }
    }
    val selection = if (state.active || state.busy) state.selected else selected.toSet()
    val editable = state.parked && !state.active && !state.busy && !state.connecting && !state.automatic.armed
    val canStart = editable && selection.isNotEmpty() && selection.all { id -> state.controls.any { it.id == id && it.available } }
    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Paper, surface = Color.White, onSurface = Ink, onBackground = Ink),
        typography = Typography(bodyLarge = TextStyle(fontFamily = Asta, fontSize = 20.sp), bodyMedium = TextStyle(fontFamily = Asta, fontSize = 17.sp),
            titleLarge = TextStyle(fontFamily = Asta, fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
            labelLarge = TextStyle(fontFamily = Asta, fontSize = 20.sp, fontWeight = FontWeight.SemiBold))) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Green), contentAlignment = Alignment.Center) {
                        Text("Ⅱ", color = Pale, fontSize = 27.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("ForNow", fontFamily = Asta, fontWeight = FontWeight.Bold, fontSize = 27.sp)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(8.dp).background(if (state.connected) Green else Color(0xFFB9A57A), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.connecting) "차량 기능 확인 중" else if (state.parked) "P · 주차 중" else "주행 중 · 화면 조작 잠금", color = Muted, fontFamily = Asta, fontSize = 17.sp)
                }
                Spacer(Modifier.height(24.dp))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val wide = maxWidth > 820.dp
                    val grid = maxWidth > 530.dp
                    if (wide) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                Hero(state, minutes)
                                Spacer(Modifier.height(24.dp))
                                DurationPicker(minutes, editable) { minutes = it }
                                Spacer(Modifier.height(20.dp))
                                MessageCard(state)
                                if (state.history.isNotEmpty()) { Spacer(Modifier.height(20.dp)); Text("최근 기록", color = Muted, fontSize = 15.sp, fontFamily = Asta)
                                    state.history.take(2).forEach { Text(it, modifier = Modifier.padding(top = 10.dp), fontSize = 16.sp, color = Muted, fontFamily = Asta) } }
                            }
                            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                AutomaticModeCard(state, command)
                                Controls(state, selection, editable, true) { id -> selected = toggle(selected, id) }
                                Footnote(state, details, { details = !details }, reconnect)
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            Hero(state, minutes)
                            Spacer(Modifier.height(20.dp)); DurationPicker(minutes, editable) { minutes = it }
                            Spacer(Modifier.height(20.dp)); MessageCard(state)
                            Spacer(Modifier.height(24.dp)); AutomaticModeCard(state, command)
                            Controls(state, selection, editable, grid) { id -> selected = toggle(selected, id) }
                            Footnote(state, details, { details = !details }, reconnect)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (state.active || state.automatic.armed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = { command(ForNowService.RESTORE) }, enabled = !state.busy && !state.automatic.busy,
                            modifier = Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(20.dp)) { Text("지금 되돌리기") }
                        OutlinedButton(onClick = { command(ForNowService.KEEP) }, enabled = !state.busy && !state.automatic.busy,
                            modifier = Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(20.dp)) { Text("이대로 유지") }
                    }
                } else {
                    Button(onClick = { start(minutes * 60_000L, selection) }, enabled = canStart,
                        modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(20.dp)) {
                        Text(if (state.busy) "설정을 적용하고 있어요" else if (!state.parked && !state.connecting) "음성 요청 가능 · 화면 조작은 주차 후" else "$minutes" + "분만 적용하기")
                    }
                }
            }
        }
    }
}

@Composable private fun AutomaticModeCard(state: ForNowState, command: (String) -> Unit) {
    val automatic = state.automatic
    Surface(color = if (automatic.armed) Pale else Color.White, shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("상황에 맞춰 잠깐", style = MaterialTheme.typography.titleLarge)
            Text("핸즈프리 통화 · 드라이브스루·정산소", color = Green, fontFamily = Asta, fontSize = 20.sp)
            Text(automatic.message, fontFamily = Asta, fontSize = 17.sp, lineHeight = 24.sp)
            if (automatic.armed) {
                Text(AutomaticModes.label(automatic.mode) + " · " + automatic.callStatus, color = Green, fontFamily = Asta, fontSize = 16.sp)
                Text("아래 ‘지금 되돌리기’로 복원하고 자동 감지를 끌 수 있어요.", color = Muted, fontFamily = Asta, fontSize = 15.sp)
            } else {
                Text("통화가 끝나면 1.5초 뒤 복원해요. 창구에서는 D 기어·서행 중 창문을 빠르게 내리면 작동해요.",
                    color = Muted, fontFamily = Asta, fontSize = 16.sp, lineHeight = 23.sp)
                Button(onClick = { command(ForNowService.ARM) },
                    enabled = state.parked && !state.active && !state.busy && !state.connecting && automatic.ready,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("통화·창구 자동 모드 켜기") }
            }
            if (state.parked) {
                if (!automatic.armed) Text(automatic.callStatus, color = Muted, fontFamily = Asta, fontSize = 15.sp)
                if (automatic.support.isNotBlank()) Text("이 차량의 지원 기능: ${automatic.support}", color = Muted, fontFamily = Asta, fontSize = 15.sp)
                Text("통화: 창문 닫기·바람 줄이기·열선/통풍 보조\n창구: 내기 순환·열선/통풍 보조\n운전자가 직접 바꾼 설정은 유지해요. 앱 안내는 화면으로 표시해요.",
                    color = Muted, fontFamily = Asta, fontSize = 15.sp, lineHeight = 23.sp)
                Text(automatic.detail, color = Muted, fontFamily = Asta, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
    }
}

private fun toggle(old: List<String>, id: String): List<String> {
    if (id in old) return old - id
    val filtered = when (id) { "seatHeat" -> old - "seatCool"; "seatCool" -> old - "seatHeat"; else -> old }
    return filtered + id
}

@Composable private fun Hero(state: ForNowState, minutes: Int) {
    if (state.automatic.armed) {
        Text("필요할 때 바꾸고,", fontFamily = Asta, fontSize = 21.sp, color = Muted)
        Text("끝나면 되돌려요.", fontFamily = Asta, fontSize = 36.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text(AutomaticModes.label(state.automatic.mode), color = Green, fontFamily = Asta, fontSize = 25.sp, modifier = Modifier.padding(top = 20.dp))
        Text(state.automatic.callStatus, color = Muted, fontFamily = Asta, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
        return
    }
    val count = if (state.active) state.remainingSeconds else minutes * 60L
    Text(if (state.active) "원래대로 돌아가기까지" else "지금 필요한 설정만,", fontFamily = Asta, fontSize = 21.sp, color = Muted)
    if (!state.active) Text("잠깐 바꿔요.", fontFamily = Asta, fontSize = 42.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
        Text("${count / 60}:${(count % 60).toString().padStart(2, '0')}", fontFamily = Asta,
            fontSize = if (state.active) 76.sp else 58.sp, fontWeight = FontWeight.Medium, color = Green)
        Spacer(Modifier.width(15.dp)); ControlGlyph("fan", state.active, Modifier.size(56.dp), Green)
    }
    if (state.active) {
        LinearProgressIndicator(progress = { (count * 1_000f / state.durationMs).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(5.dp).clip(CircleShape), color = Green, trackColor = Line)
    }
}

@Composable private fun DurationPicker(minutes: Int, enabled: Boolean, choose: (Int) -> Unit) {
    Text("얼마 동안 바꿀까요?", fontFamily = Asta, fontSize = 18.sp, color = Muted)
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(1, 3, 5).forEach { value ->
            Surface(onClick = { choose(value) }, enabled = enabled, shape = RoundedCornerShape(16.dp),
                color = if (value == minutes) Green else Color.White, modifier = Modifier.weight(1f).height(58.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("${value}분", color = if (value == minutes) Color.White else Ink, fontFamily = Asta, fontSize = 22.sp) }
            }
        }
    }
}

@Composable private fun MessageCard(state: ForNowState) {
    Surface(color = Pale, shape = RoundedCornerShape(20.dp)) {
        Text(if (state.automatic.armed) state.automatic.message else state.message, Modifier.padding(20.dp), fontFamily = Asta, color = Ink, fontSize = 18.sp, lineHeight = 27.sp)
    }
}

@Composable private fun Controls(state: ForNowState, selected: Set<String>, enabled: Boolean, twoColumns: Boolean, choose: (String) -> Unit) {
    Text(if (state.active) "복원할 설정" else "잠깐 바꿀 기능", style = MaterialTheme.typography.titleLarge)
    Text("여러 개를 함께 선택할 수 있어요", color = Muted, fontFamily = Asta, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
    if (state.controls.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Green) }
    }
    state.controls.chunked(if (twoColumns) 2 else 1).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { control ->
                ControlCard(control, control.id in selected, enabled && control.available, state.active && control.phase == "복원 예약",
                    Modifier.weight(1f)) { choose(control.id) }
            }
            if (twoColumns && row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable private fun ControlCard(info: ControlInfo, selected: Boolean, enabled: Boolean, animate: Boolean, modifier: Modifier, click: () -> Unit) {
    val color = if (!info.available) Color(0xFF8B938E) else Green
    Surface(onClick = click, enabled = enabled, modifier = modifier.heightIn(min = 160.dp), shape = RoundedCornerShape(22.dp),
        color = if (selected && info.available) Color(0xFFE5EEE4) else Color.White,
        border = BorderStroke(if (selected && info.available) 2.dp else 1.dp, if (selected && info.available) Green else Line)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ControlGlyph(info.id, animate, Modifier.size(32.dp), color)
                Spacer(Modifier.weight(1f))
                Text(if (!info.available) "사용 불가" else if (selected) "✓" else "+", fontFamily = Asta,
                    color = color, fontSize = if (!info.available) 12.sp else 25.sp)
            }
            Spacer(Modifier.height(13.dp))
            Text(info.title, color = if (info.available) Ink else Muted, fontFamily = Asta, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(if (info.available) info.description else info.reason.ifBlank { "현재 차량에서 사용할 수 없어요." },
                color = Muted, fontFamily = Asta, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp))
            if (info.available) Text(info.phase.ifBlank { "현재 ${info.current}" }, color = color,
                fontFamily = Asta, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable private fun Footnote(state: ForNowState, details: Boolean, toggle: () -> Unit, reconnect: () -> Unit) {
    Text("직접 변경한 항목은 자동으로 복원하지 않아요.\n경고음·내비 안내·통화·하이패스 음성은 유지해요.",
        fontFamily = Asta, fontSize = 15.sp, lineHeight = 23.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
    if (!state.active && !state.busy && !state.automatic.armed) TextButton(onClick = reconnect, enabled = !state.connecting) { Text("연결 다시 확인", fontSize = 16.sp) }
    if (state.detail.isNotBlank()) {
        TextButton(onClick = toggle) { Text(if (details) "상세 닫기" else "처리 결과 자세히", fontSize = 16.sp) }
        if (details) Text(state.detail, fontSize = 15.sp, fontFamily = Asta, color = Muted)
    }
}

@Composable private fun ControlGlyph(id: String, animate: Boolean, modifier: Modifier, color: Color) {
    val angle = if (animate && id == "fan") {
        val transition = rememberInfiniteTransition(label = "quiet-fan")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(5_000, easing = LinearEasing)), label = "rotation").value
    } else 20f
    Canvas(modifier) {
        val w = size.width; val h = size.height; val line = w * .065f
        when (id) {
            "fan" -> rotate(if (animate) angle else 20f) {
                repeat(3) { i -> rotate(i * 120f) {
                    drawOval(color.copy(alpha = .85f), Offset(w * .41f, h * .02f), Size(w * .28f, h * .48f))
                } }
                drawCircle(color, w * .12f, center)
            }
            "media" -> {
                val p = Path().apply { moveTo(w*.12f,h*.38f); lineTo(w*.32f,h*.38f); lineTo(w*.55f,h*.16f); lineTo(w*.55f,h*.84f); lineTo(w*.32f,h*.62f); lineTo(w*.12f,h*.62f); close() }
                drawPath(p,color)
                drawLine(color,Offset(w*.72f,h*.36f),Offset(w*.94f,h*.64f),line,StrokeCap.Round)
                drawLine(color,Offset(w*.94f,h*.36f),Offset(w*.72f,h*.64f),line,StrokeCap.Round)
            }
            "wheel" -> {
                drawCircle(color,w*.41f,center,style=Stroke(line))
                drawLine(color,Offset(w*.12f,h*.48f),Offset(w*.88f,h*.48f),line)
                drawLine(color,center,Offset(w*.5f,h*.88f),line)
                drawCircle(color,w*.1f,center)
            }
            "ambient" -> {
                drawCircle(color,w*.25f,Offset(w*.5f,h*.35f),style=Stroke(line))
                drawLine(color,Offset(w*.36f,h*.65f),Offset(w*.64f,h*.65f),line,StrokeCap.Round)
                drawLine(color,Offset(w*.4f,h*.8f),Offset(w*.6f,h*.8f),line,StrokeCap.Round)
            }
            else -> {
                drawLine(color,Offset(w*.25f,h*.16f),Offset(w*.25f,h*.66f),line*1.5f,StrokeCap.Round)
                drawLine(color,Offset(w*.25f,h*.66f),Offset(w*.8f,h*.66f),line*1.5f,StrokeCap.Round)
                drawLine(color,Offset(w*.32f,h*.8f),Offset(w*.8f,h*.8f),line,StrokeCap.Round)
                repeat(3) { i ->
                    val x=w*(.45f+i*.17f)
                    drawLine(color,Offset(x,h*.5f),Offset(x,h*.18f),line*.65f,StrokeCap.Round)
                }
            }
        }
    }
}
