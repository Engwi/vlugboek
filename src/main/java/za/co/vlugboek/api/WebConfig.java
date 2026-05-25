package za.co.vlugboek.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "http://localhost:*",
                        "https://localhost",
                        "https://localhost:*",
                        "http://127.0.0.1:*",
                        "capacitor://localhost",
                        "app://localhost",
                        "https://vlugboek.co.za",
                        "https://www.vlugboek.co.za",
                        "http://vlugboek.co.za",
                        "http://www.vlugboek.co.za"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
