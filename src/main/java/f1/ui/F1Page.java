package f1.ui;

import javafx.scene.Node;

/**
 * 页面契约:导航壳在页面切换时通知 onShown/onHidden,
 * 页面借此在"进入页面时"刷新数据、离开时停掉动画/轮询。
 * 各页面互不引用,只与导航壳交互。
 */
public interface F1Page {

    /** 页面根节点(每个页面实例建一次,复用) */
    Node build();

    default void onShown() {
    }

    default void onHidden() {
    }
}
