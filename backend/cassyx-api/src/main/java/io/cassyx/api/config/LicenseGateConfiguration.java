package io.cassyx.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.api.filter.LicenseGateFilter;
import io.cassyx.api.license.LicenseGate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Wires the server-side licence gate of plan section 9.1.
 *
 * <p>Registered from a {@code @Configuration} class with an explicit {@link FilterRegistrationBean}
 * rather than by annotating the filter {@code @Component}. Two reasons, both about blast radius: a
 * {@code Filter} bean found by component scanning is pulled into every {@code @WebMvcTest} slice in
 * the module, which would put a licence check in front of eight unrelated workstreams' controller
 * tests; and the registration bean is where the URL patterns and ordering live, so the filter class
 * itself stays a pure decision with no Spring registration semantics baked into it.
 */
@Configuration(proxyBeanMethods = false)
public class LicenseGateConfiguration {

  /**
   * The single shared licence decision. Both the filter and {@code GET /api/license} take THIS
   * bean; two instances would be two verdicts waiting to diverge.
   */
  @Bean
  public LicenseGate licenseGate(LicenseProperties properties, CassyxVersion version) {
    return new LicenseGate(
        properties.publicKey(),
        properties.key(),
        properties.enforce(),
        properties.bypassAllowed(),
        version);
  }

  @Bean
  public CassyxVersion cassyxVersion(
      ObjectProvider<BuildProperties> buildProperties, Environment environment) {
    return new CassyxVersion(
        buildProperties,
        environment.getProperty("cassyx.version", ""),
        environment.getProperty("spring.application.version", ""));
  }

  /**
   * Ordered ahead of the application's own filters but after the container's request-encoding and
   * error-dispatch plumbing, so a refusal is still encoded and logged like any other response.
   */
  @Bean
  public FilterRegistrationBean<LicenseGateFilter> licenseGateFilter(
      LicenseGate gate, ObjectMapper objectMapper) {
    FilterRegistrationBean<LicenseGateFilter> registration =
        new FilterRegistrationBean<>(new LicenseGateFilter(gate, objectMapper));
    // Narrowed to /api/* at the container level as well as in the filter: the SPA and static assets
    // must never so much as enter this filter, or a bug here takes the activation screen down with
    // it and leaves the user no route to fix the licence.
    registration.addUrlPatterns("/api/*");
    registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
    registration.setName("licenseGateFilter");
    return registration;
  }
}
