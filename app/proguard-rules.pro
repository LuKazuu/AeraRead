# Preserve Android view XML inflation constructors while letting R8 shrink, inline,
# repackage and optimize all unreachable code across libraries and the app.
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-repackageclasses ''
-allowaccessmodification
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
