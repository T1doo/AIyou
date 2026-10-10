package com.t1doo.aiyou

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.designsystem.ProviderConnectionCard
import com.t1doo.aiyou.designsystem.StatusNotice
import com.t1doo.aiyou.ui.Destination
import com.t1doo.aiyou.ui.MainScreen
import com.t1doo.aiyou.ui.DestinationIcon

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent { AIyouTheme { AIyouApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIyouApp(previewDestination: Destination = Destination.NOW, previewDemo: Boolean = false,
             previewState: ComponentState = ComponentState.NORMAL) {
    var selectedName by rememberSaveable { mutableStateOf(previewDestination.name) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var demo by rememberSaveable { mutableStateOf(previewDemo) }
    var stateName by rememberSaveable { mutableStateOf(previewState.name) }
    val selected = Destination.valueOf(selectedName)
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(enabled = showSettings) { showSettings = false }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(if (showSettings) "设置" else "爱忆 AIyou", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "返回" else "设置") }
                },
            )
        },
        bottomBar = {
            if (!imeVisible) {
                NavigationBar {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = destination == selected && !showSettings,
                            onClick = { selectedName = destination.name; showSettings = false },
                            icon = { DestinationIcon(destination) }, label = { Text(destination.label, style = MaterialTheme.typography.bodySmall) },
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        val modifier = Modifier.padding(contentPadding).consumeWindowInsets(contentPadding)
        if (showSettings) {
            Column(
                modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AIyouDimensions.pageNarrow),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceLg),
            ) {
                StatusNotice("仅本机处理", "尚未开放记录保存、AI 连接与系统提醒。")
                ProviderConnectionCard("我的模型 · 未配置", "视觉、转写和语义检索能力未检测")
                Text("界面示例", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { demo = !demo }) { Text(if (demo) "退出虚构示例" else "查看虚构示例") }
                if (demo) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                        ComponentState.entries.forEach { state ->
                            OutlinedButton(onClick = { stateName = state.name }) {
                                Text(state.description + if (stateName == state.name) " · 已选" else "")
                            }
                        }
                    }
                }
            }
        } else {
            MainScreen(
                destination = selected, demo = demo, modifier = modifier,
                state = if (demo) ComponentState.valueOf(stateName) else ComponentState.NORMAL,
            )
        }
    }
}
