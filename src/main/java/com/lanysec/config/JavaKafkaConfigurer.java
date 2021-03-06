package com.lanysec.config;

import org.apache.flink.api.java.utils.ParameterTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * @author daijb
 * @date 2021/3/8 16:23
 * 参数设置参考如下:
 * --bootstrap.servers 192.168.33.101:6667
 * --topic csp_flow12
 * --group.id test12
 * --interval 1m
 */
public class JavaKafkaConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(JavaKafkaConfigurer.class);

    private static volatile Properties properties;

    public static Properties getKafkaProperties(String[] args) {
        if (null == properties) {
            synchronized (JavaKafkaConfigurer.class) {
                if (properties == null) {
                    if (args == null || args.length <= 0) {
                        //获取配置文件kafka.properties的内容
                        Properties kafkaProperties = new Properties();
                        try {
                            kafkaProperties.load(JavaKafkaConfigurer.class.getClassLoader().getResourceAsStream("kafka.properties"));
                        } catch (Throwable throwable) {
                            logger.error("load kafka configurer failed due to ", throwable);
                        }
                        properties = kafkaProperties;
                    } else {
                        ParameterTool parameters = ParameterTool.fromArgs(args);
                        properties = parameters.getProperties();
                    }

                }
            }
        }
        return properties;
    }
}
