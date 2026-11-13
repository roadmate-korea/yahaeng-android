package com.yahaeng.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import com.yahaeng.android.R
import com.yahaeng.android.night.NightUi
import com.yahaeng.android.night.SettingsState
import com.yahaeng.android.rules.Passage
import com.yahaeng.android.voice.GuidanceSpeaker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val CardShape = RoundedCornerShape(28.dp)

@Composable
private fun T(text: String, style: TextStyle, color: Color = Night.text, modifier: Modifier = Modifier) =
    Text(text, style = style, color = color, modifier = modifier)

@Composable
private fun Card(modifier: Modifier = Modifier, border: Color? = null, content: @Composable () -> Unit) {
    Column(
        modifier
            .clip(CardShape)
            .background(Night.surface)
            .let { if (border != null) it.border(BorderStroke(2.dp, border), CardShape) else it }
            .padding(horizontal = 36.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(Modifier.clip(CircleShape).background(color.copy(alpha = 0.18f)).padding(horizontal = 20.dp, vertical = 8.dp)) {
        T(text, NightType.caption, color)
    }
}

/** 머리글: 달 아이콘, 앱 이름, 상태, 주행 중이면 잠금 배지. */
@Composable
fun AppHeader(status: String, locked: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(R.drawable.ic_moon), null, tint = Night.moon, modifier = Modifier.size(36.dp))
        T(stringResource(R.string.app_name), NightType.title)
        T(status, NightType.caption, Night.sub, Modifier.weight(1f))
        if (locked) Badge(stringResource(R.string.locked_badge), Night.sub)
    }
}

/** 지금 말하고 있는 문장. */
@Composable
fun CaptionBar(caption: GuidanceSpeaker.Caption?) {
    if (caption == null) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Night.surface)
            .border(BorderStroke(2.dp, Night.moon), RoundedCornerShape(24.dp)).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(painterResource(R.drawable.ic_speaker), null, tint = if (caption.voiced) Night.moon else Night.sub, modifier = Modifier.size(36.dp))
        T(caption.text, NightType.body)
    }
}

// ---------------- 첫 실행 ----------------

@Composable
fun OnboardingScreen(parked: Boolean, dataRegions: Int, dataSize: Int, onStart: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Night.bg)) {
        val wide = maxWidth >= 1200.dp
        val intro: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(40.dp)) {
                AppHeader(stringResource(R.string.status_setup), locked = false)
                T(stringResource(R.string.onboard_title), NightType.headline)
                for ((t, d, c) in listOf(
                    Triple(R.string.onboard_auto_title, R.string.onboard_auto_desc, Night.moon),
                    Triple(R.string.onboard_flash_title, R.string.onboard_flash_desc, Night.yellow),
                    Triple(R.string.onboard_rain_title, R.string.onboard_rain_desc, Night.rain),
                )) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Box(Modifier.padding(top = 14.dp).size(20.dp).clip(CircleShape).background(c))
                        Column { T(stringResource(t), NightType.title); T(stringResource(d), NightType.body, Night.sub) }
                    }
                }
            }
        }
        val permCard: @Composable (Modifier) -> Unit = { m ->
            Column(m.clip(CardShape).background(Night.surface).padding(44.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                T(stringResource(R.string.onboard_perm_title), NightType.title)
                for ((t, d) in listOf(R.string.onboard_perm_location to R.string.onboard_perm_location_desc, R.string.onboard_perm_speed to R.string.onboard_perm_speed_desc)) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Night.raised).padding(horizontal = 28.dp, vertical = 22.dp)) {
                        T(stringResource(t), NightType.body); T(stringResource(d), NightType.caption, Night.sub)
                    }
                }
                Box(
                    Modifier.fillMaxWidth().clip(CircleShape).background(if (parked) Night.moon else Night.raised)
                        .clickable(enabled = parked, onClick = onStart).padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) { T(stringResource(R.string.onboard_start), NightType.title, if (parked) Night.bg else Night.sub) }
                T(
                    if (parked) stringResource(R.string.onboard_data, dataRegions, dataSize) else stringResource(R.string.onboard_parked_only),
                    NightType.caption, Night.sub,
                )
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp), horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.CenterVertically) {
                intro(Modifier.weight(1f)); permCard(Modifier.width(640.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp), verticalArrangement = Arrangement.spacedBy(48.dp)) {
                intro(Modifier.fillMaxWidth()); permCard(Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------------- 주행 화면(내비 옆 반쪽에 맞춤) ----------------

private fun hhmm(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

@Composable
fun DrivePanel(ui: NightUi, caption: GuidanceSpeaker.Caption?, modifier: Modifier = Modifier) {
    val e = ui.engine
    val status = when {
        ui.approaching != null -> stringResource(R.string.status_night)
        e.wetLimit != null -> stringResource(R.string.status_rain)
        !e.hasLocation -> stringResource(R.string.status_no_location)
        e.night -> stringResource(R.string.status_night)
        else -> stringResource(R.string.status_day)
    }
    Column(modifier.fillMaxSize().background(Night.bg).padding(48.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        AppHeader(status, ui.vehicle.locked)
        if (ui.vehicle.error) T(stringResource(R.string.vehicle_unavailable), NightType.caption, Night.red)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            when {
                ui.approaching != null -> ApproachContent(ui)
                e.wetLimit != null -> RainContent(ui)
                else -> StandbyContent(ui)
            }
        }
        CaptionBar(caption)
    }
}

@Composable
private fun ColumnScope.StandbyContent(ui: NightUi) {
    val e = ui.engine
    val sun = e.sun
    if (sun != null) {
        Card(Modifier.fillMaxWidth()) {
            T(stringResource(if (e.night) R.string.sunrise_label else R.string.sunset_label), NightType.body, Night.sub)
            T(hhmm(if (e.night) sun.sunriseMinute else sun.sunsetMinute), NightType.display, Night.moon)
            if (!e.night) T(stringResource(R.string.sunset_desc), NightType.body)
        }
    }
    val nearby = e.nearbyTonight
    if (nearby != null) {
        Card(Modifier.fillMaxWidth()) {
            T(stringResource(R.string.nearby_label), NightType.body, Night.sub)
            if (nearby > 0) {
                T(stringResource(R.string.count_places, nearby), NightType.headline, Night.yellow)
                T(ui.regions.joinToString(", ") { "${it.region.substringAfter(' ')} (${it.baseDate.take(7)})" }, NightType.caption, Night.sub)
            } else {
                T(stringResource(R.string.nearby_none), NightType.body)
            }
        }
    }
    Spacer(Modifier.weight(1f))
    T(stringResource(if (e.night) R.string.night_note else R.string.day_note), NightType.caption, Night.sub)
}

@Composable
private fun ColumnScope.ApproachContent(ui: NightUi) {
    val p = ui.approaching ?: return
    Column {
        Badge(stringResource(R.string.flash_badge, p.window.label()), Night.yellow)
        ui.distanceMeters?.let { T(stringResource(R.string.distance_m, it), NightType.display, Night.yellow) }
        T(p.name.ifBlank { stringResource(R.string.unnamed_intersection) }, NightType.headline)
    }
    RuleRow(Night.yellow, stringResource(R.string.rule_yellow), stringResource(R.string.rule_yellow_do))
    RuleRow(Night.red, stringResource(R.string.rule_red), stringResource(R.string.rule_red_do))
    if (ui.turnLeft || ui.turnRight) {
        Card(Modifier.fillMaxWidth(), border = Night.moon) {
            T(stringResource(if (ui.turnLeft) R.string.turn_left_label else R.string.turn_right_label), NightType.caption, Night.moon)
            T(stringResource(if (ui.turnLeft) R.string.say_flash_turn_left else R.string.say_flash_turn_right), NightType.body)
        }
    }
}

@Composable
private fun RuleRow(color: Color, label: String, action: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Night.raised).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(color))
        Column { T(label, NightType.caption, Night.sub); T(action, NightType.title) }
    }
}

@Composable
private fun ColumnScope.RainContent(ui: NightUi) {
    val e = ui.engine
    val wet = e.wetLimit ?: return
    Badge(stringResource(R.string.wiper_badge), Night.rain)
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
        Column { T(stringResource(R.string.limit_label), NightType.body, Night.sub); T("${e.speedLimit}", NightType.headline, Night.sub) }
        Column { T(stringResource(R.string.wet_label), NightType.body, Night.rain); T("$wet", NightType.display, Night.rain) }
    }
    ui.cruiseTarget?.let { target ->
        val over = target - wet
        Card(Modifier.fillMaxWidth(), border = if (over > 0) Night.yellow else null) {
            T(stringResource(R.string.cruise_label), NightType.caption, if (over > 0) Night.yellow else Night.sub)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
                T("$target", NightType.headline, if (over > 0) Night.yellow else Night.text)
                T(if (over > 0) stringResource(R.string.cruise_over, over) else stringResource(R.string.cruise_ok), NightType.body)
            }
        }
    }
    T(stringResource(R.string.rain_note), NightType.caption, Night.sub)
}

// ---------------- 정차 중(P) 화면 ----------------

@Composable
fun ParkedScreen(ui: NightUi, caption: GuidanceSpeaker.Caption?, settings: SettingsState, onSettings: ((SettingsState) -> SettingsState) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Night.bg)) {
        val wide = maxWidth >= 1200.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(R.string.tab_now, R.string.tab_records, R.string.tab_settings).forEachIndexed { i, res ->
                    val on = tab == i
                    Box(
                        Modifier.clip(CircleShape).background(if (on) Night.moon else Night.raised).clickable { tab = i }
                            .padding(horizontal = 28.dp, vertical = 14.dp),
                    ) { T(stringResource(res), NightType.body, if (on) Night.bg else Night.text) }
                }
            }
            when (tab) {
                0 -> DrivePanel(ui, caption, Modifier.widthIn(max = 900.dp))
                1 -> RecordsScreen(ui)
                else -> DataSettingsScreen(ui, settings, onSettings, wide = wide)
            }
        }
    }
}

@Composable
private fun RecordsScreen(ui: NightUi) {
    val list = ui.passages
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
        T(stringResource(R.string.records_title), NightType.headline)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            val count = { o: Passage.Outcome -> list.count { it.outcome == o } }
            for ((label, n, c) in listOf(
                Triple(stringResource(R.string.records_passed), list.size, Night.text),
                Triple(stringResource(R.string.outcome_stopped), count(Passage.Outcome.STOPPED), Night.stopped),
                Triple(stringResource(R.string.outcome_slowed), count(Passage.Outcome.SLOWED), Night.moon),
                Triple(stringResource(R.string.outcome_passed), count(Passage.Outcome.PASSED), Night.yellow),
            )) {
                Card(Modifier.weight(1f)) { T(label, NightType.body, Night.sub); T(stringResource(R.string.count_places, n), NightType.headline, c) }
            }
        }
        if (list.isEmpty()) T(stringResource(R.string.records_empty), NightType.body, Night.sub)
        list.forEach { PassageRow(it) }
        T(stringResource(R.string.records_note), NightType.caption, Night.sub)
    }
}

@Composable
private fun PassageRow(p: Passage) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") } }
    val (label, color) = when (p.outcome) {
        Passage.Outcome.STOPPED -> stringResource(R.string.outcome_stopped) to Night.stopped
        Passage.Outcome.SLOWED -> stringResource(R.string.outcome_slowed) to Night.moon
        Passage.Outcome.PASSED -> stringResource(R.string.outcome_passed) to Night.yellow
        Passage.Outcome.UNKNOWN -> stringResource(R.string.outcome_unknown) to Night.sub
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Night.surface).padding(horizontal = 36.dp, vertical = 26.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        T(fmt.format(Date(p.atMillis)), NightType.title, Night.sub)
        Column(Modifier.weight(1f)) {
            T(p.name.ifBlank { stringResource(R.string.unnamed_intersection) }, NightType.title)
            T(stringResource(R.string.records_window, p.window.label()), NightType.caption, Night.sub)
        }
        T(p.minKmh?.let { stringResource(R.string.records_min_speed, it) } ?: stringResource(R.string.records_min_speed_unknown), NightType.body)
        Badge(label, color)
    }
}

@Composable
private fun DataSettingsScreen(ui: NightUi, s: SettingsState, onSettings: ((SettingsState) -> SettingsState) -> Unit, wide: Boolean) {
    val data: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            T(stringResource(R.string.data_title), NightType.title)
            if (ui.regions.isEmpty()) Card(Modifier.fillMaxWidth()) { T(stringResource(R.string.data_none), NightType.body) }
            ui.regions.forEach { r ->
                Card(Modifier.fillMaxWidth()) {
                    T(r.region, NightType.body)
                    T(stringResource(R.string.data_region, r.baseDate, r.count), NightType.caption, Night.yellow)
                }
            }
            T(stringResource(R.string.data_source), NightType.caption, Night.sub)
        }
    }
    val toggles: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            T(stringResource(R.string.settings_title), NightType.title)
            Toggle(R.string.set_auto, R.string.set_auto_desc, s.autoStart) { v -> onSettings { it.copy(autoStart = v) } }
            Toggle(R.string.set_flash, R.string.set_flash_desc, s.flashVoice) { v -> onSettings { it.copy(flashVoice = v) } }
            Toggle(R.string.set_popup, R.string.set_popup_desc, s.popup) { v -> onSettings { it.copy(popup = v) } }
            Toggle(R.string.set_rain, R.string.set_rain_desc, s.rain) { v -> onSettings { it.copy(rain = v) } }
        }
    }
    val scroll = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp)
    if (wide) Row(scroll, horizontalArrangement = Arrangement.spacedBy(40.dp)) { data(Modifier.weight(1f)); toggles(Modifier.weight(1f)) }
    else Column(scroll, verticalArrangement = Arrangement.spacedBy(40.dp)) { data(Modifier.fillMaxWidth()); toggles(Modifier.fillMaxWidth()) }
}

@Composable
private fun Toggle(title: Int, desc: Int, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Night.surface).clickable { onChange(!value) }
            .padding(horizontal = 36.dp, vertical = 28.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1f)) { T(stringResource(title), NightType.body); T(stringResource(desc), NightType.caption, Night.sub) }
        Switch(checked = value, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Night.moon))
    }
}
