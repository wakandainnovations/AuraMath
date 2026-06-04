package com.lit.fire.flame.nlq.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wires up the Ask engine's configuration. For F0 this only enables
 * {@link AskEngineProperties}; no beans, controllers, or pipeline components are registered yet,
 * so the engine remains dormant.
 */
@Configuration
@EnableConfigurationProperties(AskEngineProperties.class)
public class AskEngineConfiguration {
}
