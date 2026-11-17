package f1.domain.port;

import f1.domain.model.NewsArticle;

import java.util.List;

/**
 * 新闻端口:多个订阅源聚合为统一列表(按时间倒序)。
 * Mock 与真实 RSS 都实现它。
 */
public interface NewsProvider {

    /** 拉取最新新闻(合并各源,按发布时间倒序);失败抛 DataException */
    List<NewsArticle> latestNews(int limit);
}
