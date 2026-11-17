package f1.ui.util;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * 页面级状态卡 —— 整块区域加载失败时,给出"图标 + 原因 + 重试"的引导,
 * 替代"标题栏一行红字、内容区全空白"的裸错误态。纯 UI 小工具,与数据层无耦合。
 */
public final class StateCard {

    private StateCard() {
    }

    /** 错误卡:⚠️ 原因 + 重试按钮;onRetry 为 null 时只展示不提供重试 */
    public static Node error(String message, Runnable onRetry) {
        VBox card = new VBox(10);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(36, 24, 36, 24));
        card.setMaxWidth(Double.MAX_VALUE);
        card.getStyleClass().add("card");

        Label icon = new Label("⚠️");
        icon.setStyle("-fx-font-size: 30px;");

        Label text = new Label(message == null || message.isBlank()
                ? "加载失败,请稍后重试"
                : message);
        text.getStyleClass().add("error-text");
        text.setWrapText(true);
        text.setMaxWidth(760);
        text.setAlignment(Pos.CENTER);

        card.getChildren().add(icon);
        card.getChildren().add(text);
        if (onRetry != null) {
            Button retry = new Button("↻ 重试");
            retry.getStyleClass().add("ghost");
            retry.setOnAction(e -> onRetry.run());
            card.getChildren().add(retry);
        }
        return card;
    }
}
