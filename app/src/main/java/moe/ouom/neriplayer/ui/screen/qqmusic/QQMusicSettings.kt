package moe.ouom.neriplayer.ui.screen.qqmusic

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.settings.*
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicQuality
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.SettingsHomeCardSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.component.playback.QualityOptionsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.common.R as CoreR

@Composable
internal fun QQHomeVisibilitySetting(highlightTargetId:String?=null,highlightPulse:Int=0,onHighlightFinished:(()->Unit)?=null) {
    val context=LocalContext.current
    val setting=AutoSettingsSchema.personalization.homeCardQqMusic
    val enabled by remember(context) { context.autoSettingFlow(setting) }.collectAsState(initial=true)
    val scope=rememberCoroutineScope()
    SettingsHomeCardSwitch(title=stringResource(CoreR.string.qq_home_visibility),
        description=stringResource(CoreR.string.qq_home_visibility_desc),icon=Icons.Outlined.Star,
        checked=enabled,onCheckedChange={scope.launch {context.setAutoSetting(setting,it)}},enabled=true,
        targetId="setting:home_card_qq_music",highlightTargetId=highlightTargetId,highlightPulse=highlightPulse,onHighlightFinished=onHighlightFinished)
}

@Composable
internal fun QQAudioQualitySetting() {
    val accounts=AppContainer.qqMusicAccounts
    val quality by accounts.qualityState.collectAsState()
    val scope=rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(accounts) { accounts.quality() }
    val labels=mapOf(QQMusicQuality.STANDARD to stringResource(CoreR.string.quality_standard),
        QQMusicQuality.EXHIGH to stringResource(CoreR.string.quality_very_high),
        QQMusicQuality.LOSSLESS to stringResource(CoreR.string.quality_lossless))
    ListItem(modifier=Modifier.settingsItemClickable {show=true},headlineContent={Text(stringResource(CoreR.string.qq_quality_default))},
        supportingContent={Text(stringResource(CoreR.string.common_label_value_format,labels[quality].orEmpty(),quality.name.lowercase()))},
        leadingContent={Icon(Icons.Filled.Audiotrack,null,Modifier.size(24.dp))},
        colors=ListItemDefaults.colors(containerColor=Color.Transparent))
    if(show) QualityOptionsDialog(title=stringResource(CoreR.string.qq_quality_default),selectedValue=quality.name,
        options=QQMusicQuality.entries.map {it.name to labels[it].orEmpty()},onDismiss={show=false},onSelect={ value ->
            scope.launch { accounts.setQuality(QQMusicQuality.valueOf(value));show=false }
        })
}
