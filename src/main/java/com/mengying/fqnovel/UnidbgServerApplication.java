package com.mengying.fqnovel;

import com.mengying.fqnovel.utils.ConsoleNoiseFilter;
import com.mengying.fqnovel.utils.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.env.Environment;

import java.net.InetAddress;

@ConfigurationPropertiesScan
@SpringBootApplication(
    excludeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration"
    }
)
public class UnidbgServerApplication {

    private static final Logger log = LoggerFactory.getLogger(UnidbgServerApplication.class);

    private static final String SERVER_PORT = "server.port";
    private static final String SERVER_SERVLET_CONTEXT_PATH = "server.servlet.context-path";

    public static void main(String[] args) {
        ConsoleNoiseFilter.install();
        preferLocalConfigIfPresent();
        Environment env = SpringApplication.run(UnidbgServerApplication.class, args).getEnvironment();
        logApplicationStartup(env);
    }

    /**
     * 默认只使用打包进 Jar 的配置（classpath:/application.yml）。
     * <p>
     * 若运行目录（工作目录）存在 application.yml，则优先使用它；否则回退到 Jar 内置配置。
     * <p>
     * 如需外置配置，请显式传入：
     * <ul>
     *   <li>--spring.config.location=...</li>
     *   <li>或 -Dspring.config.location=...</li>
     *   <li>或 --spring.config.additional-location=...</li>
     * </ul>
     *
     * 若已显式设置 spring.config.location / spring.config.additional-location，则不覆盖。
     */
    private static void preferLocalConfigIfPresent() {
        if (System.getProperty("spring.config.location") != null) {
            return;
        }
        if (System.getProperty("spring.config.additional-location") != null) {
            return;
        }
        if (System.getenv("SPRING_CONFIG_LOCATION") != null) {
            return;
        }
        if (System.getenv("SPRING_CONFIG_ADDITIONAL_LOCATION") != null) {
            return;
        }

        boolean hasLocalConfig = new java.io.File("application.yml").isFile()
            || new java.io.File("application.yaml").isFile()
            || new java.io.File("application.properties").isFile();

        if (hasLocalConfig) {
            // 若本地存在配置文件：允许 file:./ 覆盖 classpath
            System.setProperty("spring.config.location",
                "optional:classpath:/,optional:file:./");
        } else {
            // 本地没有配置文件：只读 classpath，避免扫描工作目录带来的不确定性
            System.setProperty("spring.config.location", "optional:classpath:/");
        }
    }

    private static void logApplicationStartup(Environment env) {
        String serverPort = env.getProperty(SERVER_PORT);
        String contextPath = env.getProperty(SERVER_SERVLET_CONTEXT_PATH);
        contextPath = Texts.defaultIfBlank(contextPath, "/");
        String hostAddress = InetAddress.getLoopbackAddress().getHostAddress();
        log.info("服务已启动: http://{}:{}{}", hostAddress, serverPort, contextPath);
    }
}
