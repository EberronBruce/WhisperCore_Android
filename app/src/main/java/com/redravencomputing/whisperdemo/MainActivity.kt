package com.redravencomputing.whisperdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.redravencomputing.whisperdemo.ui.main.MainScreen
import com.redravencomputing.whisperdemo.ui.main.TestViewModel
import com.redravencomputing.whisperdemo.ui.theme.WhisperDemoTheme

class MainActivity : ComponentActivity() {
	//private val viewModel: MainScreenViewModel by viewModels { MainScreenViewModel.factory() }
	private val viewModel: TestViewModel by viewModels { TestViewModel.factory() }
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			WhisperDemoTheme {
				MainScreen(viewModel)
			}
		}
	}
}
