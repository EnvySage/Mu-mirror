package org.xianshen.mumirrorb.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / SpringDoc 全局配置
 *
 * 配置后在 doc.html 页面顶部会出现"Authorize"按钮，
 * 输入 JWT Token 后，所有需要认证的接口都可以直接调试。
 */
@Configuration
public class Knife4jConfig {

    /**
     * JWT 认证名称（SecurityScheme 与 SecurityRequirement 需要一致）
     */
    private static final String SECURITY_SCHEME_NAME = "Bearer Token";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Mu-mirror-B API 文档")
                        .description("AI 镜像站后端接口文档")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("xianshen")
                                .url("https://github.com/xianshen")))
                // 全局安全方案：Bearer Token
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .name("Authorization")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("输入 JWT Token，不需要带 Bearer 前缀")));
    }
}
