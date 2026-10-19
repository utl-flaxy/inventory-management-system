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
            // @Size defaults min=0; retain @NotEmpty's lower bound when generating the array schema.
            var orderRequest = api.getComponents().getSchemas().get("OrderRequest");
            if (orderRequest != null && orderRequest.getProperties() != null) {
                Schema<?> items = (Schema<?>) orderRequest.getProperties().get("items");
                if (items != null) items.setMinItems(1);
            }
            api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                var responses = operation.getResponses();
                if (path.equals("/auth/login") && method == io.swagger.v3.oas.models.PathItem.HttpMethod.POST) {
                    responses.addApiResponse("503", new ApiResponse()
                            .description("LOGIN_SOURCE_UNAVAILABLE: verified client identity unavailable; operator configuration required. SERVICE_UNAVAILABLE: temporary database connection/timeout failure; credentials were not rejected."));
                    responses.addApiResponse("429", new ApiResponse().description("LOGIN_RATE_LIMITED: admission denied before credential lookup; valid credentials also consume quota")
                            .addHeaderObject("Retry-After", new io.swagger.v3.oas.models.headers.Header()
                                    .description("Minimum wait in whole seconds; another request can consume available quota")
                                    .schema(new IntegerSchema().minimum(java.math.BigDecimal.ONE))));
                }
                if (path.equals("/auth/register") && method == io.swagger.v3.oas.models.PathItem.HttpMethod.POST) {
                    operation.setDescription("Public ROLE_USER registration. Defaults per JVM: 5 admitted attempts per verified source per fixed 60-second window, 2 concurrent registrations, 4096 source windows. Success, duplicate and internal failure consume admission. Login counters/permits are independent. Shared NAT, restart, eviction and multiple JVMs limit protection; edge abuse controls are required.");
                    responses.addApiResponse("503", new ApiResponse()
                            .description("REGISTRATION_SOURCE_UNAVAILABLE: verified client identity unavailable; operator configuration required. SERVICE_UNAVAILABLE: temporary database connection/timeout failure."));
                    responses.addApiResponse("429", new ApiResponse()
                            .description("REGISTRATION_RATE_LIMITED: source quota or registration concurrency exhausted before DB/BCrypt")
                            .addHeaderObject("Retry-After", new io.swagger.v3.oas.models.headers.Header()
                                    .description("Positive whole seconds until source window expires; 1 second for concurrent saturation. Availability is not guaranteed after waiting.")
                                    .schema(new IntegerSchema().minimum(java.math.BigDecimal.ONE))));
                }
                boolean forceRevocation = path.equals("/api/v1/admin/users/{uid}/token-revocations");
                if ((path.equals("/auth/logout-all") || path.equals("/auth/password") || forceRevocation) && method == io.swagger.v3.oas.models.PathItem.HttpMethod.POST) {
                    operation.setDescription("Authenticated user-wide JWT revocation. Increments the user's generation and inserts an audit row in one transaction; 204 is returned after commit. "
                            + "The same JWT subsequently receives 401, including on retry. No request body is used; the authenticated principal is the only target. "
                            + "Defaults per JVM: 5 admitted attempts per verified source and operation per fixed 60-second window, 2 shared concurrent auth mutations, 4096 source/operation windows. "
                            + "Independent of login/register. Requests authenticated before commit may finish; individual JWT revocation is not provided.");
                    responses.addApiResponse("401", new ApiResponse().description("UNAUTHENTICATED: missing or invalid JWT; legacy and revoked JWTs are rejected, including a generation change while waiting for the user lock"));
                    responses.addApiResponse("503", new ApiResponse().description("AUTH_MUTATION_SOURCE_UNAVAILABLE: verified client identity unavailable. SERVICE_UNAVAILABLE: confirmed transient DB/row-lock failure; no Retry-After."));
                    responses.addApiResponse("429", new ApiResponse().description("AUTH_MUTATION_RATE_LIMITED: auth mutation source quota or concurrency exhausted")
                            .addHeaderObject("Retry-After", new io.swagger.v3.oas.models.headers.Header()
                                    .description("Positive whole seconds; availability after waiting is not guaranteed")
                                    .schema(new IntegerSchema().minimum(java.math.BigDecimal.ONE))));
                    if (path.equals("/auth/password")) {
                        operation.setDescription("Authenticated self-service password change: currentPassword/newPassword, no target identifier. "
                                + "Current credentials use login validation; new password: USER at least 8 characters, ADMIN at least 16 with no control characters, UTF-8 at most 72 bytes, no trimming. "
                                + "BCrypt runs outside the transaction within the shared bounded permit. The user lock rechecks UUID, generation and previous hash. "
                                + "Password hash, generation increment and PASSWORD_CHANGE audit commit atomically before 204; no token is returned. All old JWTs receive 401; log in with the new password. "
                                + "Defaults per JVM: 5 admitted attempts per verified source/operation per fixed minute, 2 shared concurrent auth mutations, 4096 bounded windows. Login/register budgets are independent. "
                                + "Already authenticated in-flight business operations may finish. Multi-instance rate limiting and individual token revocation are not provided.");
                        responses.addApiResponse("400", new ApiResponse().description("VALIDATION_ERROR with errors.currentPassword / errors.newPassword; rejected values are never returned"));
                        responses.addApiResponse("401", new ApiResponse().description("INVALID_CREDENTIALS: wrong current password. UNAUTHENTICATED: invalid/revoked JWT or concurrent generation/hash change"));
                    }
                    if (forceRevocation) {
                        operation.setDescription("Current DB ADMIN plus the actor's own currentPassword (login validation, no trimming). Target is only the canonical lowercase UUID v4 in the path. "
                                + "One BCrypt check runs outside transactions in the bounded permit. Deduplicated actor/target rows lock in internal ID order and recheck actor UID, generation, hash and ADMIN role. "
                                + "Fresh target generation increment and FORCE_REVOKE audit commit atomically before 204; no JWT, password or role change. Self-revocation is allowed; the old admin JWT then receives 401. "
                                + "Independent valid ADMIN calls increment once per success. In-flight requests may finish and password holders can log in again. "
                                + "Per JVM: 5 attempts per verified source/operation/minute, shared 2 concurrent auth mutations, 4096 windows; independent of login/register. No jti, reset, refresh or role-change API.");
                        responses.addApiResponse("400", new ApiResponse().description("INVALID_REQUEST: malformed target UID. VALIDATION_ERROR: errors.currentPassword; no rejected values"));
                        responses.addApiResponse("404", new ApiResponse().description("USER_NOT_FOUND: target absent, only after ADMIN reauthentication"));
                    }
                }
                responses.addApiResponse("500", new ApiResponse().description("SYSTEM_ERROR"));
                responses.putIfAbsent("503", new ApiResponse().description("SERVICE_UNAVAILABLE: temporary database connection/timeout failure; keep a valid JWT. Recovery time is unknown; no Retry-After. Do not blindly replay writes."));
                if (!path.startsWith("/auth/")) {
                    responses.addApiResponse("401", new ApiResponse().description(forceRevocation ? "INVALID_CREDENTIALS / UNAUTHENTICATED" : "UNAUTHENTICATED"));
                    responses.addApiResponse("403", new ApiResponse().description("ACCESS_DENIED"));
                }
                if (operation.getRequestBody() != null) {
                    responses.putIfAbsent("400", new ApiResponse().description("入力不正・Validation"));
                    responses.putIfAbsent("415", new ApiResponse().description("UNSUPPORTED_MEDIA_TYPE"));
                }
                if (path.contains("/orders")) {
                    if (java.util.Set.of("/api/v1/admin/orders", "/api/v1/me/orders").contains(path)
                            && method == io.swagger.v3.oas.models.PathItem.HttpMethod.GET)
                        responses.putIfAbsent("400", new ApiResponse().description("INVALID_REQUEST: page/size/sort不正"));
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
                    responses.putIfAbsent("400", new ApiResponse().description("INVALID_REQUEST: unsupported sort property or invalid keyword"));
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
                            case "keyword" -> {
                                if (path.equals("/products/search")) {
                                    parameter.setDescription("Required: 1–100 Unicode scalar values, nonblank. Spaces are significant; no trim or case normalization. Whitespace includes Unicode NEXT LINE (U+0085). Blank, whitespace-only, unpaired surrogate or longer values return 400 INVALID_REQUEST.");
                                    parameter.setSchema(new StringSchema().minLength(1).maxLength(100));
                                }
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
