#This is the consumer-rules.pro
# Keep the JNI bridge class, its constructor, and all its members, especially native methods.
# Ensure the package and class name 'com.redravencomputing.whisper.whispercore.WhisperJNIBridge' is correct.
-keep class com.redravencomputing.whisper.whispercore.WhisperJNIBridge { *; }
-keepclassmembers class com.redravencomputing.whisper.whispercore.WhisperJNIBridge {
    public <init>(); # Keep constructor(s)
    native <methods>; # Crucial: keeps all native methods
}

# --- Keep your Public API ---

# Main public class
-keep public class com.redravencomputing.whisper.whispercore.Whisper { # Adjust package if this class is in com.redravencomputing.whisper
    public protected *;
}

# Sealed class for operation errors and its publicly accessible members/subclasses
-keep public class com.redravencomputing.whisper.whispercore.WhisperOperationError { # Adjust package if needed
    public protected *;
}
# Optionally, be more explicit for object subclasses if needed for strong API contract
-keep public class com.redravencomputing.whisper.whispercore.WhisperOperationError$MissingRecordedFile { *; }
-keep public class com.redravencomputing.whisper.whispercore.WhisperOperationError$MicPermissionDenied { *; }
-keep public class com.redravencomputing.whisper.whispercore.WhisperOperationError$ModelNotLoaded { *; }
-keep public class com.redravencomputing.whisper.whispercore.WhisperOperationError$RecordingFailed { *; }
# For the class subclass, the keep on WhisperOperationError should cover it if it's public.

# Sealed class for load errors and its publicly accessible members/subclasses
-keep public class com.redravencomputing.whisper.whispercore.WhisperLoadError { # Adjust package if needed
    public protected *;
}
# Optionally, be more explicit for object subclasses
-keep public class com.redravencomputing.whisper.whispercore.WhisperLoadError$PathToModelEmpty { *; }
-keep public class com.redravencomputing.whisper.whispercore.WhisperLoadError$CouldNotLocateModel { *; }
-keep public class com.redravencomputing.whisper.whispercore.WhisperLoadError$UnableToLoadModel { *; }
# For the class subclass, the keep on WhisperLoadError should cover it if it's public.

# Public interface
-keep public interface com.redravencomputing.whisper.whispercore.WhisperDelegate { # Adjust package if needed
    public protected *;
}

# If readResolve methods are critical for consumers (Java serialization)
#-keepclassmembers class **.*$* { # More targeted if possible
#    private java.lang.Object readResolve();
#}
# Or be very specific:
#-keepclassmembers class com.redravencomputing.whisper.whispercore.WhisperLoadError$PathToModelEmpty {
#    private java.lang.Object readResolve();
#}
#-keepclassmembers class com.redravencomputing.whisper.whispercore.WhisperLoadError$CouldNotLocateModel {
#    private java.lang.Object readResolve();
#}
# (and so on for other objects with readResolve)

