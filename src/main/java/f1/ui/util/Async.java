package f1.ui.util;

import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 后台任务 → 平台线程回调用具。
 * 所有页面发起网络/数据调用都必须经这里:工作在线程池,回调切回 FX 线程,
 * UI 代码永远不需要自己管理线程。
 */
public final class Async {

    private Async() {
    }

    public static <T> void run(Executor executor, Callable<T> work,
                               Consumer<T> onSuccessFx, Consumer<Throwable> onErrorFx) {
        executor.execute(() -> {
            T result;
            try {
                result = work.call();
            } catch (Throwable t) {
                Platform.runLater(() -> onErrorFx.accept(t));
                return;
            }
            Platform.runLater(() -> onSuccessFx.accept(result));
        });
    }
}
