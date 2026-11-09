package com.running.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import java.time.Duration

/**
 * Single-user login when `app.security.password` is set; fully open otherwise.
 * CSRF is off because the templates post plain forms; the session cookie is SameSite=Lax, so other sites cannot post with it.
 * Remember-me keeps you logged in across restarts.
 */
@Configuration
@EnableConfigurationProperties(AppSecurityProperties::class)
class SecurityConfig {

    private val log = LoggerFactory.getLogger(SecurityConfig::class.java)

    @Bean
    fun userDetailsService(properties: AppSecurityProperties): UserDetailsService {
        val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
        val password = properties.password.ifBlank { java.util.UUID.randomUUID().toString() }
        return InMemoryUserDetailsManager(
            User.withUsername(properties.username).password(encoder.encode(password)).roles("OWNER").build(),
        )
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity, properties: AppSecurityProperties, users: UserDetailsService): SecurityFilterChain {
        http.csrf { it.disable() }
        http.headers { headers -> headers.frameOptions { it.sameOrigin() } }
        if (!properties.loginEnabled) {
            log.warn("No app.security.password set: the app is open without login (fine on localhost only)")
            http.authorizeHttpRequests { it.anyRequest().permitAll() }
            return http.build()
        }
        http.authorizeHttpRequests {
            it.requestMatchers("/login", "/favicon.ico", "/error", "/css/**", "/js/**", "/img/**").permitAll()
            it.anyRequest().authenticated()
        }
        http.formLogin { it.loginPage("/login").defaultSuccessUrl("/", false).permitAll() }
        http.rememberMe {
            it.key(properties.rememberMeKey.ifBlank { properties.password })
                .userDetailsService(users)
                .alwaysRemember(true)
                .tokenValiditySeconds(REMEMBER_ME.seconds.toInt())
        }
        http.logout { it.logoutSuccessUrl("/login?logout").permitAll() }
        return http.build()
    }

    companion object {
        val REMEMBER_ME: Duration = Duration.ofDays(90)
    }
}
