package com.netflix.vms.transformer.startup;

import com.netflix.vms.transformer.common.config.TransformerConfig;

import com.google.inject.AbstractModule;
// Common module dependencies
import com.google.inject.Provides;
import com.netflix.archaius.ConfigProxyFactory;
// Server dependencies
import com.netflix.runtime.health.guice.HealthModule;
import com.netflix.runtime.lifecycle.RuntimeCoreModule;
import com.netflix.vms.transformer.health.CustomHealthIndicator;
import javax.inject.Singleton;


/**
 * This is the "main" module where we wire everything up. If you see this module getting overly
 * complex, it's a good idea to break things off into separate ones and install them here instead.
 *
 * @author This file is auto-generated by runtime@netflix.com. Feel free to modify.
 */
public final class VideometadataTransformerModule extends AbstractModule {

    @Override
    protected void configure() {
        install(new RuntimeCoreModule());
        install(new HealthModule() {
            @Override
            protected void configureHealth() {
                bindAdditionalHealthIndicator().to(CustomHealthIndicator.class);
            }
        });
        install(new JerseyModule());

        bind(TransformerCycleKickoff.class).asEagerSingleton();
    }

    @Provides
    @Singleton
    TransformerConfig getVideometadataTransformerConfig(ConfigProxyFactory factory) {
        // Here we turn the config interface into an implementation that can load dynamic properties.
        return factory.newProxy(TransformerConfig.class);
    }
}
