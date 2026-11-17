package f1.domain;

/**
 * 数据层统一异常:网络失败、HTTP 非 2xx、解析失败都包成它,
 * UI 捕获后展示一句可读的中文错误,不接触底层细节。
 */
public class DataException extends RuntimeException {

    public DataException(String message) {
        super(message);
    }

    public DataException(String message, Throwable cause) {
        super(message, cause);
    }
}
