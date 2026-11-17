package f1.domain.port;

import f1.domain.model.PositionUpdate;

import java.util.List;

/**
 * 实时流端口:订阅后持续收到位置更新(批次),直到 disconnect。
 *
 * 实现方(Mock 模拟器 / Jolpica 近实时轮询 / 历史回放引擎)各不相同,
 * 但对 UI 暴露同一契约 —— 实时页不关心数据到底从哪来。
 * 回调发生在工作线程;UI 侧负责转回平台线程(见 ui.live 的合并分发)。
 */
public interface LiveTimingProvider extends AutoCloseable {

    /** 流事件监听 */
    interface Listener {

        /** 一批位置更新(同一时刻的全体车手快照,或一次小规模变动) */
        void onUpdates(List<PositionUpdate> updates);

        /** 状态消息:连接中 / 比赛结束 / 错误等(UI 直接展示) */
        void onStatus(String message);

        /**
         * 退赛事件(由数据方在"确定该车手不会再出圈"时上报,UI 只呈现不猜测):
         *
         * @param driverId 车手 ID
         * @param atLap    判退圈号 = 他未完成的下一圈(已完整跑完 atLap-1 圈)
         */
        default void onRetirement(String driverId, int atLap) {
        }

        /**
         * 撤销退赛:数据方事后发现误判(典型:把"被套圈但已冲线完赛"的车手当退赛,
         * 官方成绩确认后纠正)。UI 把该行恢复为完赛名单。
         */
        default void onRetirementRescinded(String driverId) {
        }
    }

    void connect(Listener listener);

    void disconnect();

    /** 当前流的可读描述,如 "Mock 演示 · 第 3 圈" / "真实近实时 · 西班牙站" */
    String describe();

    @Override
    default void close() {
        disconnect();
    }
}
