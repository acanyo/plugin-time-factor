package cc.lik.timefactor.service.impl;


import cc.lik.timefactor.service.SettingConfigGetter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

@Component
@RequiredArgsConstructor
public class SettingConfigGetterImpl implements SettingConfigGetter {
    private final ReactiveSettingFetcher settingFetcher;

    @Override
    public Mono<BasicConfig> getBasicConfig() {
        return settingFetcher.fetch(BasicConfig.GROUP, BasicConfig.class)
            .defaultIfEmpty(new BasicConfig());
    }

    @Override
    public Mono<SeoOverrideConfig> getSeoOverrideConfig() {
        return settingFetcher.fetch(SeoOverrideConfig.GROUP, SeoOverrideConfig.class)
            .defaultIfEmpty(new SeoOverrideConfig());
    }
}
