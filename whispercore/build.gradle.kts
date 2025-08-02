plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
}

android {
	namespace = "com.redravencomputing.whisper"
	compileSdk = 36

	buildFeatures {
		buildConfig = true
	}

	defaultConfig {
		minSdk = 29

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		consumerProguardFiles("consumer-rules.pro")

		externalNativeBuild {
			cmake {
				// Arguments to pass to CMake.
				// We ensure various backends are off, as we've cleaned the source.
				// GGML_USE_CPU is also attempted in your main CMakeLists.txt.
				arguments.addAll(listOf(
					"-DGGML_USE_CPU=ON",
					"-DWHISPER_SUPPORT_COREML=OFF",
					"-DWHISPER_SUPPORT_METAL=OFF",
					"-DWHISPER_NATIVE=OFF",
					"-DWHISPER_ACCELERATE=OFF",
					"-DWHISPER_OPENBLAS=OFF",
					"-DGGML_BLAS_OFF=ON",
					"-DGGML_CUDA_OFF=ON",
					"-DGGML_METAL_OFF=ON",
					"-DGGML_OPENCL_OFF=ON",
					"-DGGML_VULKAN_OFF=ON"
				))
				// ABI Filters: Specify which native architectures to build for.
				// It's good practice to define these.
				// Choose based on what you want to support. arm64-v8a is common.
				abiFilters.addAll(listOf(
					"arm64-v8a",
					// "armeabi-v7a",
					// "x86",        // Less common for physical devices now
					"x86_64"      // For emulators mainly
				))
			}
		}


	}

	testBuildType = "debug"

	buildTypes {
		release {
			isMinifyEnabled = true
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro"
			)

			// Consider adding NDK debuggability for release builds if needed,
			// though usually not for final releases.
			// ndk {
			//     debuggable = true
			// }
		}

		// It's good practice to also configure debug type for native builds
		debug {
			// If you specifically need to pass arguments for debug NDK builds,
			// you might do it here using externalNativeBuild.cmake.arguments,
			// but let's start without it.
		 }
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
//	kotlinOptions {
//		jvmTarget = "11"
//	}

	// NDK version (optional but recommended for reproducibility)
	// Use a version you have installed via Android Studio's SDK Manager
	ndkVersion = "27.0.12077973"

	// --- Add this block for externalNativeBuild ---
	externalNativeBuild {
		cmake {
			// Path to your main CMakeLists.txt file in the cpp directory
			path = file("src/main/cpp/CMakeLists.txt")
			// Specify your CMake version.
			// Use a version that is available in your NDK and >= cmake_minimum_required.
			// The NDK often bundles specific CMake versions.
			// Check Android Studio SDK Manager -> SDK Tools -> NDK & CMake for installed versions.
			version = "3.22.1" // Common version bundled with recent NDKs.
			// Adjust if necessary, e.g., to "3.18.1" or "3.24.1"
		}
	}

	testOptions {
		unitTests.isReturnDefaultValues = true
	}

}

kotlin {
	jvmToolchain(17)
}

dependencies {
	implementation(libs.androidx.core.ktx)

	testImplementation(libs.junit)
	testImplementation(libs.mockk)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.mockito.core)
	testImplementation(libs.mockito.kotlin)
	testImplementation(libs.mockito.inline)

	androidTestImplementation(libs.androidx.junit.ktx)
	androidTestImplementation(libs.androidx.rules)
	androidTestImplementation(libs.mockito.android)
	androidTestImplementation(libs.mockito.kotlin)
	androidTestImplementation(libs.kotlinx.coroutines.test)
}