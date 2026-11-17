package f1.domain.port;

/**
 * 支持"播放变速"的实时流(目前只有历史回放引擎实现;
 * Mock 演示 / 真实近实时是天然节奏,不支持变速,UI 不会给它们显示倍速控件)。
 *
 * 倍速相对各自引擎的 1× 基准节奏(回放引擎 1× = 1000ms/圈)。
 * 引擎通过 instanceof 判断能力,不引入新装配点 —— UI 只依赖 domain 接口。
 */
public interface LivePlayback extends LiveTimingProvider {

    /** 设置播放倍速(0.1× ~ 20×);运行中随时可调,下一次心跳生效 */
    void setSpeed(double rate);
}
