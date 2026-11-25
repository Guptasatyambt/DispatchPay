package org.myorg.dispatchpay.iam.access;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class AccessWebConfig implements WebMvcConfigurer {

    private final AccessService accessService;

    AccessWebConfig(AccessService accessService) {
        this.accessService = accessService;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new BusinessAccessArgumentResolver(accessService));
    }
}
