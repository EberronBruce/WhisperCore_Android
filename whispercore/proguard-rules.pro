    # In whispercore/proguard-rules.pro (THIS IS THE LIBRARY'S OWN PROGUARD FILE)

    # Keep the JNI bridge class, its constructor, and all its members, especially native methods.
    # Ensure the package and class name 'com.redravencomputing.whisper.whispercore.WhisperJNIBridge' is correct.
    -keep class com.redravencomputing.whispercore.WhisperJNIBridge { *; }
    -keepclassmembers class com.redravencomputing.whispercore.WhisperJNIBridge {
        public <init>(); # Keep constructor(s)
        native <methods>; # Crucial: keeps all native methods
    }

    # --- Keep your Public API (also important for the library's own minification) ---

    # Main public class
    -keep public class com.redravencomputing.whispercore.Whisper { # Adjust package if this class is in com.redravencomputing.whisper
        public protected *;
    }

    # Sealed class for operation errors and its publicly accessible members/subclasses
    -keep public class com.redravencomputing.whispercore.WhisperOperationError { # Adjust package if needed
        public protected *;
    }
    -keep public class com.redravencomputing.whispercore.WhisperOperationError$MissingRecordedFile { *; }
    -keep public class com.redravencomputing.whispercore.WhisperOperationError$MicPermissionDenied { *; }
    -keep public class com.redravencomputing.whispercore.WhisperOperationError$ModelNotLoaded { *; }
    -keep public class com.redravencomputing.whispercore.WhisperOperationError$RecordingFailed { *; }


    # Sealed class for load errors and its publicly accessible members/subclasses
    -keep public class com.redravencomputing.whispercore.WhisperLoadError { # Adjust package if needed
        public protected *;
    }
    -keep public class com.redravencomputing.whispercore.WhisperLoadError$PathToModelEmpty { *; }
    -keep public class com.redravencomputing.whispercore.WhisperLoadError$CouldNotLocateModel { *; }
    -keep public class com.redravencomputing.whispercore.WhisperLoadError$UnableToLoadModel { *; }


    # Public interface
    -keep public interface com.redravencomputing.whispercore.WhisperDelegate { # Adjust package if needed
        public protected *;
    }

    # You can also add the -keepattributes and -renamesourcefileattribute if you want,
    # though they are more for debugging the minified library itself.
    # Uncomment this to preserve the line number information for
    # debugging stack traces.
    #-keepattributes SourceFile,LineNumberTable

    # If you keep the line number information, uncomment this to
    # hide the original source file name.
    #-renamesourcefileattribute SourceFile
    