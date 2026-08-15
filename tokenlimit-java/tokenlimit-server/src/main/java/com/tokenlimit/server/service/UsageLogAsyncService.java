package com.tokenlimit.server.service;

import com.tokenlimit.server.entity.UsageLog;
import com.tokenlimit.server.repository.mapper.UsageLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 用量日志异步持久化服务.
 * <p>将 {@link UsageLog} 写入 MySQL 的操作从网关主线程剥离，
 * 避免上游大模型调用完成后同步落库阻塞响应。</p>
 */
@Service
public class UsageLogAsyncService {

    private static final Logger log = LoggerFactory.getLogger(UsageLogAsyncService.class);

    private final UsageLogMapper usageLogMapper;

    public UsageLogAsyncService(UsageLogMapper usageLogMapper) {
        this.usageLogMapper = usageLogMapper;
    }

    /**
     * 异步保存用量日志.
     *
     * @param usageLog 用量日志实体
     */
    @Async("asyncTaskExecutor")
    public void saveUsageLog(UsageLog usageLog) {
        try {
            usageLogMapper.insert(usageLog);
        } catch (Exception e) {
            log.error("异步保存 usage_log 失败, traceId={}", usageLog.getTraceId(), e);
        }
    }
}
