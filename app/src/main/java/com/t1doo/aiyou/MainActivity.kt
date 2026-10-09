package com.t1doo.aiyou

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                AIyouApp()
            }
        }
    }
}

private enum class Destination(val label: Int, val description: Int, val symbol: String) {
    NOW(R.string.now, R.string.now_placeholder, "○"),
    MEMORIES(R.string.memories, R.string.memories_placeholder, "◇"),
    TASKS(R.string.tasks, R.string.tasks_placeholder, "□"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AIyouApp() {
    var selectedName by rememberSaveable { mutableStateOf(Destination.NOW.name) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val selected = Destination.valueOf(selectedName)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = { showSettings = true }) {
                        Text(stringResource(R.string.settings))
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == selected,
                        onClick = { selectedName = destination.name },
                        icon = { Text(destination.symbol) },
                        label = { Text(stringResource(destination.label)) },
                    )
                }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(selected.label), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.local_only), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(selected.description), style = MaterialTheme.typography.bodyLarge)
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text(stringResource(R.string.settings)) },
            text = { Text(stringResource(R.string.settings_placeholder)) },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}
