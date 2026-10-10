package com.example.demo;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public final class TestApiProblems {
    private TestApiProblems() { }
    public static ResultMatcher problem(int statusCode, String code, String title, String path) {
        return result -> {
            status().is(statusCode).match(result);
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            jsonPath("$.status").value(statusCode).match(result);
            jsonPath("$.code").value(code).match(result);
            jsonPath("$.title").value(title).match(result);
            jsonPath("$.type").value("https://inventory-management-system.example/problems/"
                    + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')).match(result);
            jsonPath("$.detail").isString().match(result);
            jsonPath("$.instance").value(path).match(result);
            jsonPath("$.length()").value(code.equals("VALIDATION_ERROR") ? 7 : 6).match(result);
            for (String field : java.util.List.of("message", "trace", "exception", "stackTrace", "password", "hash", "token"))
                jsonPath("$." + field).doesNotExist().match(result);
        };
    }
}
