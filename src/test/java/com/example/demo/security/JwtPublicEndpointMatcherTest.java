package com.example.demo.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class JwtPublicEndpointMatcherTest {
    static Stream<Arguments> requests() {
        var cases=new ArrayList<Arguments>();
        for(String context:List.of("","/inventory"))
            for(String endpoint:List.of("/auth/login","/auth/register"))
                for(String method:List.of("POST","GET","HEAD","PUT","DELETE","OPTIONS"))
                    cases.add(Arguments.of(method,context+endpoint,context,method.equals("POST")));
        for(String path:List.of("/authority/filter-probe","/authentication/filter-probe","/authz/filter-probe",
                "/auth/private","/auth/login/","/auth/login-extra","/auth/login;parameter",
                "/auth/%6cogin","/auth//login","/AUTH/login"))
            cases.add(Arguments.of("POST",path,"",false));
        return cases.stream();
    }

    @ParameterizedTest(name="{0} {1} context={2}: public={3}")
    @MethodSource("requests")
    void onlyCanonicalPublicPostsSkipJwtAndAllowAnonymous(String method,String uri,String context,boolean expected) {
        var request=new MockHttpServletRequest(method,uri);
        request.setContextPath(context);
        request.setQueryString("next=/authority");
        var filter=new JwtAuthenticationFilter(mock(JwtUtil.class),mock(UserDetailsService.class),mock(SecurityProblemWriter.class));
        assertEquals(expected,JwtAuthenticationFilter.PUBLIC_AUTH_REQUESTS.matches(request));
        assertEquals(expected,filter.shouldNotFilter(request));
    }
}
