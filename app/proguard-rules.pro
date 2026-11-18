# Preserve useful line numbers for local crash diagnosis with the archived R8 mapping.
# Room and AndroidX supply their consumer rules; no blanket keep of the application.
-keepattributes SourceFile,LineNumberTable
