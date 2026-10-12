package com.example.demo.config;

import com.example.demo.security.LoginClientAddressFilter;
import com.example.demo.security.LoginRateLimitProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

@Configuration(proxyBeanMethods = false)
public class LoginSecurityConfiguration {
    @Bean
    public FilterRegistrationBean<LoginClientAddressFilter> loginClientAddressFilter(LoginRateLimitProperties properties) {
        var registration = new FilterRegistrationBean<>(new LoginClientAddressFilter(properties));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    @ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "framework")
    public FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter(TomcatServerProperties properties) {
        var filter = new ForwardedHeaderFilter();
        filter.setRelativeRedirects(properties.isUseRelativeRedirects());
        var registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        // Boot's default has HIGHEST_PRECEDENCE too; explicitly order it after transport peer capture.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
