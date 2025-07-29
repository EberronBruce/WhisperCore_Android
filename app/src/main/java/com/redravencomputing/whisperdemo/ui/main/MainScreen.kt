package com.redravencomputing.whisperdemo.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.redravencomputing.whisperdemo.R


@Composable
fun MainScreen(viewModel: TestViewModel) {
	MainScreen(
		canTranscribe = viewModel.canTranscribe,
		isRecording = viewModel.isRecording,
		messageLog = viewModel.dataLog,
		onBenchmarkTapped = viewModel::benchmark,
		onTranscribeSampleTapped = viewModel::transcribeSample,
		onRecordTapped = viewModel::toggleRecord,
		onRequestPermissionResult = { granted -> // NEW callback
			viewModel.onUIPermissionResult(granted)
		}

	)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
	canTranscribe: Boolean,
	isRecording: Boolean,
	messageLog: String,
	onBenchmarkTapped: () -> Unit,
	onTranscribeSampleTapped: () -> Unit,
	onRecordTapped: () -> Unit,
	onRequestPermissionResult: (Boolean) -> Unit
) {
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.app_name)) }
			)
		},
	) { innerPadding ->
		Column(
			modifier = Modifier
				.padding(innerPadding)
				.padding(16.dp)
		) {
			Column(verticalArrangement = Arrangement.SpaceBetween) {
				Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
					BenchmarkButton(enabled = canTranscribe, onClick = onBenchmarkTapped)
					TranscribeSampleButton(enabled = canTranscribe, onClick = onTranscribeSampleTapped)
				}
				RecordButton(
					enabled = canTranscribe,
					isRecording = isRecording,
					onClick = onRecordTapped,
					onRequestPermissionResult = onRequestPermissionResult
				)
			}
			MessageLog(messageLog)
		}
	}
}

@Composable
private fun MessageLog(log: String) {
	SelectionContainer {
		Text(modifier = Modifier.verticalScroll(rememberScrollState()), text = log)
	}
}

@Composable
private fun BenchmarkButton(enabled: Boolean, onClick: () -> Unit) {
	Button(onClick = onClick, enabled = enabled) {
		Text("Benchmark")
	}
}

@Composable
private fun TranscribeSampleButton(enabled: Boolean, onClick: () -> Unit) {
	Button(onClick = onClick, enabled = enabled) {
		Text("Transcribe sample")
	}
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun RecordButton(enabled: Boolean, isRecording: Boolean, onClick: () -> Unit, onRequestPermissionResult: (Boolean) -> Unit) {
	val micPermissionState = rememberPermissionState(
		permission = android.Manifest.permission.RECORD_AUDIO,
		onPermissionResult = { granted ->
			onRequestPermissionResult(granted)
			if (granted) {
				onClick()
			}
		}
	)
	Button(onClick = {
		if (micPermissionState.status.isGranted) {
			onClick()
		} else {
			micPermissionState.launchPermissionRequest()
		}
	}, enabled = enabled) {
		Text(
			if (isRecording) {
				"Stop recording"
			} else {
				"Start recording"
			}
		)
	}
}