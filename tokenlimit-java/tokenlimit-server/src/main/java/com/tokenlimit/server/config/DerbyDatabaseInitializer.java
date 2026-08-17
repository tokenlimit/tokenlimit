package com.tokenlimit.server.config;

import org.apache.derby.jdbc.EmbeddedDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Derby 内嵌数据库初始化器 (参考 Nacos 单机版方案)
 * 
 * 核心特性：
 * 1. 纯 Java 实现，无本地依赖，跨平台一致
 * 2. 嵌入式模式，在 JVM 进程内运行，零配置启动
 * 3. 自动检测并创建数据库目录
 * 4. 静默执行 DDL 脚本建表
 * 5. 日志重定向，防止污染业务日志
 * 6. 文件锁机制 (db.lck) 防止多进程并发访问
 */
@Configuration
@ConditionalOnProperty(name = "spring.datasource.driver-class-name", havingValue = "org.apache.derby.jdbc.EmbeddedDriver")
public class DerbyDatabaseInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DerbyDatabaseInitializer.class);
    
    @Value("${spring.datasource.url}")
    private String jdbcUrl;
    
    @Value("${derby.stream.error.file:${user.home}/.tokenlimit/logs/derby.log}")
    private String derbyLogFile;

    /**
     * 配置 Derby 系统属性
     */
    @Bean
    public void configureDerbyProperties() {
        // 1. 设置 Derby 错误日志文件路径
        System.setProperty("derby.stream.error.file", derbyLogFile);
        
        // 2. 禁用 Derby 网络服务器模式（仅嵌入式）
        System.setProperty("derby.drda.startNetworkServer", "false");
        
        // 3. 设置 Derby 日志级别
        System.setProperty("derby.language.logStatementText", "false");
        
        logger.info("Derby 内嵌数据库已配置，数据目录：{}", jdbcUrl);
        logger.info("Derby 日志文件：{}", derbyLogFile);
    }

    /**
     * 数据库初始化器：首次启动时自动执行建表脚本
     */
    @Bean
    public DataSourceInitializer dataSourceInitializer(DataSource dataSource) {
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        
        // 加载建表脚本
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new PathMatchingResourcePatternResolver()
            .getResource("classpath:db/derby/schema.sql"));
        
        // 忽略部分错误（如表已存在）
        populator.setContinueOnError(true);
        // 使用分号作为分隔符
        populator.setSeparator(";");
        
        initializer.setDatabasePopulator(populator);
        
        logger.info("Derby 数据库初始化器已配置，将自动执行 schema.sql");
        return initializer;
    }

    /**
     * 验证 Derby 数据库连接
     */
    public boolean validateConnection() {
        try {
            // 加载 Derby 驱动
            new EmbeddedDriver();
            
            // 尝试建立连接
            Properties props = new Properties();
            props.put("user", "APP");
            props.put("password", "APP");
            
            Connection conn = DriverManager.getConnection(jdbcUrl, props);
            if (conn != null && !conn.isClosed()) {
                conn.close();
                logger.info("Derby 数据库连接验证成功");
                return true;
            }
        } catch (SQLException e) {
            logger.error("Derby 数据库连接验证失败：{}", e.getMessage());
            return false;
        }
        return false;
    }
}
