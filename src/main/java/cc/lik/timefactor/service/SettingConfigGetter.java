package cc.lik.timefactor.service;

import lombok.Data;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import java.util.List;

public interface SettingConfigGetter {
    Mono<BasicConfig> getBasicConfig();

    Mono<SeoOverrideConfig> getSeoOverrideConfig();

    @Data
    class alternateLink {
        private String langCode;
        private String urlTemplate;
    }

    @Data
    class SeoOverrideEntry {
        private String templateId;
        private String title;
        private String description;
    }

    @Data
    class SeoOverrideConfig {
        public static final String GROUP = "seoOverrides";
        private List<SeoOverrideEntry> seoOverrides;
    }

    @Data
    class BasicConfig {
        public static final String GROUP = "basic";
        private boolean enableCanonicalLink;
        private boolean enableAlternateLink;
        private List<alternateLink> alternateLinks;
        private boolean enableBaiduTimeFactor;
        private boolean enableOGTimeFactor;
        private boolean enableMetaTimeFactor;
        private boolean enableStructuredData;
        private boolean enableTwitterCards;
        private String twitterCardsType;
        private String twitterSiteUsername;
        private String twitterSiteUserId;
        private String twitterCreatorUsername;
        private String twitterCreatorUserId;
        private String defaultImage;
        private String defaultAuthor;
    }
}
