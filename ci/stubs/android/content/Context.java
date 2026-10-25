package android.content;

// Compile-time stand-in so ci/AdbClientHostTest.java can build AdbClient on a plain JVM.
public abstract class Context {
    public abstract java.io.File getFilesDir();
}
