package cc.lik.timefactor.service;

import lombok.Data;
import reactor.core.publisher.Mono;

public interface SettingConfigGetter {
    Mono<BasicConfig> getBasicConfig();

    @Data
    class BasicConfig {
        public static final String GROUP = "basic";
        private boolean enableCanonicalLink;
        private boolean enableBaiduTimeFactor;
        private boolean enableOGTimeFactor;
        private boolean enableMetaTimeFactor;
        private boolean enableStructuredData;
        private String defaultImage;
    }
}
