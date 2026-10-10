package com.example.demo.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenApiCustomizer apiProblemResponses() {
        return api -> {
            api.getComponents().addSchemas("ApiProblem", new ObjectSchema()
                    .addProperty("type", new StringSchema().format("uri"))
                    .addProperty("title", new StringSchema())
                    .addProperty("status", new IntegerSchema().format("int32"))
                    .addProperty("detail", new StringSchema())
                    .addProperty("instance", new StringSchema().format("uri-reference"))
                    .addProperty("code", new StringSchema())
                    .addProperty("errors", new ObjectSchema().additionalProperties(new StringSchema())
                            .description("Validation時のみ。nested field pathと安全なmessage。入力値は含まない"))
                    .required(java.util.List.of("type", "title", "status", "detail", "instance", "code")));
            api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                var responses = operation.getResponses();
                responses.addApiResponse("500", new ApiResponse().description("SYSTEM_ERROR"));
                if (!path.startsWith("/auth/")) {
                    responses.addApiResponse("401", new ApiResponse().description("UNAUTHENTICATED"));
                    responses.addApiResponse("403", new ApiResponse().description("ACCESS_DENIED"));
                }
                if (operation.getRequestBody() != null) {
                    responses.putIfAbsent("400", new ApiResponse().description("入力不正・Validation"));
                    responses.putIfAbsent("415", new ApiResponse().description("UNSUPPORTED_MEDIA_TYPE"));
                }
                if (path.contains("/orders")) {
                    if (path.contains("{id}")) responses.putIfAbsent("404", new ApiResponse().description("ORDER_NOT_FOUND"));
                    if (method != io.swagger.v3.oas.models.PathItem.HttpMethod.GET)
                        responses.putIfAbsent("409", new ApiResponse().description("注文の競合"));
                    if (path.endsWith("/sales")) responses.putIfAbsent("400", new ApiResponse().description("期間の形式・範囲が不正"));
                }
                if (path.equals("/products/{id}") && java.util.Set.of(
                        io.swagger.v3.oas.models.PathItem.HttpMethod.GET,
                        io.swagger.v3.oas.models.PathItem.HttpMethod.PUT,
                        io.swagger.v3.oas.models.PathItem.HttpMethod.DELETE).contains(method))
                    responses.putIfAbsent("404", new ApiResponse().description("PRODUCT_NOT_FOUND"));
                if (java.util.Set.of("/products", "/products/search").contains(path)
                        && method == io.swagger.v3.oas.models.PathItem.HttpMethod.GET) {
                    responses.putIfAbsent("400", new ApiResponse().description("INVALID_REQUEST: unsupported sort property"));
                    operation.getParameters().forEach(parameter -> {
                        switch (parameter.getName()) {
                            case "page" -> {
                                parameter.setDescription("0-based page number; default 0. Invalid/negative values resolve to 0.");
                                parameter.setSchema(new IntegerSchema()._default(0).minimum(java.math.BigDecimal.ZERO));
                            }
                            case "size" -> {
                                parameter.setDescription("Page size; default 20, max 100. Larger values clamp to 100; invalid/nonpositive values resolve to 20.");
                                parameter.setSchema(new IntegerSchema()._default(20)
                                        .minimum(java.math.BigDecimal.ONE).maximum(java.math.BigDecimal.valueOf(100)));
                            }
                            case "sort" -> {
                                parameter.setDescription("Allowed fields: id,name,price,stock,version. Format: sort=price,asc. Multiple sort parameters supported (sort=price,asc&sort=name,desc). Default id,asc; append id ASC only when id is absent. Unsupported fields return 400 INVALID_REQUEST.");
                                parameter.setSchema(new ArraySchema().items(new StringSchema())
                                        ._default(java.util.List.of("id,asc")));
                            }
                            default -> { }
                        }
                    });
                }
                if (path.startsWith("/products") && method == io.swagger.v3.oas.models.PathItem.HttpMethod.PUT)
                    responses.putIfAbsent("409", new ApiResponse().description("CONCURRENT_UPDATE"));
                if (path.equals("/products/{id}") && method == io.swagger.v3.oas.models.PathItem.HttpMethod.DELETE)
                    responses.putIfAbsent("409", new ApiResponse().description("PRODUCT_DELETE_CONFLICT / CONCURRENT_UPDATE"));
                if (path.startsWith("/api/v1/admin/products/{id}/inventory-")) {
                    responses.putIfAbsent("404", new ApiResponse().description("PRODUCT_NOT_FOUND"));
                    if (method == io.swagger.v3.oas.models.PathItem.HttpMethod.POST) {
                        responses.put("400", new ApiResponse().description("INVALID_IDEMPOTENCY_KEY / INVALID_INVENTORY_ADJUSTMENT / 入力不正・Validation"));
                        responses.putIfAbsent("409", new ApiResponse().description("IDEMPOTENCY_KEY_CONFLICT / INVENTORY_ADJUSTMENT_CONFLICT / CONCURRENT_UPDATE"));
                    }
                }
                responses.forEach((status, response) -> {
                    if (status.matches("[45][0-9]{2}")) {
                        response.setContent(new Content().addMediaType("application/problem+json",
                                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiProblem"))));
                    }
                });
            }));
        };
    }

    @Bean
    public OpenAPI customOpenAPI() {

        final String securitySchemeName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("Inventory Management API")
                        .version("1.0"))
                .addSecurityItem(
                        new SecurityRequirement()
                                .addList(securitySchemeName))
                .schemaRequirement(
                        securitySchemeName,
                        new SecurityScheme()
                                .name(securitySchemeName)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                );
    }
}
