package f1.app;

/**
 * 程序入口(独立于 JavaFX Application,避免直接 java 运行时
 * "JavaFX runtime components missing" 的限制)。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        F1Application.launchApp(args);
    }
}
