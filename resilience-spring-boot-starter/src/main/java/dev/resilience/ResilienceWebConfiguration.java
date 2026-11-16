package dev.resilience;

import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ResilienceWebConfiguration {
    @Bean
    public FilterRegistrationBean<DeadlinePropagationFilter> deadlinePropagationFilter(
            @org.springframework.beans.factory.annotation.Value("${resilience.inbound-max-budget:2s}")
            java.time.Duration maximum) {
        var bean = new FilterRegistrationBean<>(new DeadlinePropagationFilter(maximum));
        bean.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ResilienceHttpClient resilienceHttpClient() {
        return new ResilienceHttpClient();
    }
}
