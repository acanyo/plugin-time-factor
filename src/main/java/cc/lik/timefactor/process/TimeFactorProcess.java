package cc.lik.timefactor.process;

import cc.lik.timefactor.service.SettingConfigGetter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import org.unbescape.html.HtmlEscape;
import org.unbescape.json.JsonEscape;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.query.Queries;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.infra.SystemInfo;
import run.halo.app.infra.SystemInfoGetter;
import run.halo.app.theme.dialect.TemplateHeadProcessor;
import run.halo.app.theme.router.ModelConst;

@Getter
enum TemplateEnum {
    // 内置模板 ID 参考 https://github.com/halo-dev/halo/blob/main/application/src/main/java/run/halo/app/theme/DefaultTemplateEnum.java
    INDEX("index"), CATEGORIES("categories"), CATEGORY("category"), ARCHIVES("archives"),
    POST("post"), TAG("tag"), TAGS("tags"), SINGLE_PAGE("page"), AUTHOR("author"),
    // 适配瞬间插件 https://github.com/halo-sigs/plugin-moments
    MOMENTS("moments"),  // 瞬间列表，路径 /moments
    MOMENT("moment"), // 瞬间详情页，路径 /moments/{slug}
    // 适配图库管理插件 https://github.com/halo-sigs/plugin-photos
    PHOTOS("photos"),  // 路径 /photos
    // 适配朋友圈插件 https://github.com/chengzhongxue/plugin-friends-new
    FRIENDS("friends"), // 路径 /friends
    // 适配豆瓣插件 https://github.com/chengzhongxue/plugin-douban
    DOUBAN("douban"), // 路径 /douban
    // 适配 BangumiData 插件 https://github.com/ShiinaKin/halo-plugin-bangumi-data
    BANGUMI("bangumi"), // 路径 /bangumi
    // 无法适配足迹插件 https://github.com/acanyo/halo-plugin-footprint ，值为 null
    // 无法适配链接管理插件 https://github.com/halo-sigs/plugin-links ，值为 null
    // 无法适配追番插件 https://github.com/Roozenlz/plugin-bilibili-bangumi ，值为 null
    // 未知模板
    UNKNOWN("unknown");

    private final String value;

    TemplateEnum(String value) {
        this.value = value;
    }

    static TemplateEnum fromTemplateId(String templateId) {
        return Arrays.stream(values()).filter(item -> item.getValue().equals(templateId))
            .findFirst().orElse(UNKNOWN);
    }
}

@Component
@RequiredArgsConstructor
@Slf4j
public class TimeFactorProcess implements TemplateHeadProcessor {
    private static final DateTimeFormatter BAIDU_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter GOOGLE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private final ReactiveExtensionClient client;
    private final SettingConfigGetter settingConfigGetter;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final SystemInfoGetter systemInfoGetter;

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model,
        IElementModelStructureHandler handler) {

        var templateId =
            Optional.ofNullable(context.getVariable(ModelConst.TEMPLATE_ID)).map(Object::toString)
                .orElse(null);
        var template = TemplateEnum.fromTemplateId(templateId);

        log.debug("Processing SEO for templateId: {}", templateId);

        return switch (template) {
            case INDEX -> processIndexSeoData();
            case POST -> processPostSeoData(context, model);
            case CATEGORIES -> processCategoriesSeoData();
            case CATEGORY -> processCategorySeoData();
            case ARCHIVES -> processArchivesSeoData();
            case TAGS -> processTagsSeoData();
            case TAG -> processTagSeoData();
            case SINGLE_PAGE -> processSinglePageSeoData();
            case AUTHOR -> processAuthorSeoData();
            case MOMENTS -> processMomentsSeoData();
            case MOMENT -> processMomentSeoData();
            case PHOTOS -> processPhotosSeoData();
            case FRIENDS -> processFriendsSeoData();
            case DOUBAN -> processDoubanSeoData();
            case BANGUMI -> processBangumiSeoData();
            case UNKNOWN -> Mono.empty();
        };
    }

    private Mono<Void> processIndexSeoData() {
        return Mono.empty();
    }

    private Mono<Void> processPostSeoData(ITemplateContext context, IModel model) {
        var modelFactory = context.getModelFactory();
        var postName = Optional.ofNullable(context.getVariable("name")).map(Object::toString)
            .filter(name -> !name.isEmpty()).orElse(null);

        if (postName == null) {
            return Mono.empty();
        }

        return client.fetch(Post.class, postName).flatMap(post -> buildSeoDataForPost(post).flatMap(
            seoData -> generateSeoTags(seoData, model, modelFactory)));
    }

    private Mono<Void> processCategoriesSeoData() {
        return Mono.empty();
    }

    private Mono<Void> processCategorySeoData() {
        return Mono.empty();
    }

    private Mono<Void> processArchivesSeoData() {
        return Mono.empty();
    }

    private Mono<Void> processTagsSeoData() {
        return Mono.empty();
    }

    private Mono<Void> processTagSeoData() {
        return Mono.empty();
    }

    private Mono<Void> processSinglePageSeoData() {
        return Mono.empty();
    }

    private Mono<Void> processAuthorSeoData() {
        return Mono.empty();
    }

    private Mono<Void> processMomentsSeoData() {
        return Mono.empty();
    }

    private Mono<Void> processMomentSeoData() {
        return Mono.empty();
    }

    private Mono<Void> processPhotosSeoData() {
        return Mono.empty();
    }

    private Mono<Void> processFriendsSeoData() {
        return Mono.empty();
    }

    private Mono<Void> processDoubanSeoData() {
        return Mono.empty();
    }

    private Mono<Void> processBangumiSeoData() {
        return Mono.empty();
    }

    private Mono<SeoData> buildSeoDataForPost(Post post) {
        return Mono.zip(client.fetch(User.class, post.getSpec().getOwner()), findTagForPost(post),
            settingConfigGetter.getBasicConfig(), systemInfoGetter.get()).map(tuple -> {
            var user = tuple.getT1();
            var keywords = tuple.getT2();
            var config = tuple.getT3();
            var systemInfo = tuple.getT4();

            var author = Optional.of(user).map(User::getSpec).map(User.UserSpec::getDisplayName)
                .orElse(post.getSpec().getOwner());

            var postUrl = externalLinkProcessor.processLink(post.getStatus().getPermalink());
            var title = post.getSpec().getTitle();
            var description = post.getStatus().getExcerpt();
            var coverUrl = externalLinkProcessor.processLink(
                Optional.ofNullable(post.getSpec().getCover()).filter(cover -> !cover.isBlank())
                    .orElse(config.getDefaultImage()));

            var publishInstant = post.getSpec().getPublishTime();
            var updateInstant = post.getStatus().getLastModifyTime();
            var zoneId = ZoneId.systemDefault();

            var baiduPubDate = formatDateTime(publishInstant, BAIDU_FORMATTER, zoneId);
            var baiduUpdDate = formatDateTime(updateInstant, BAIDU_FORMATTER, zoneId);
            var googlePubDate = formatDateTime(publishInstant, GOOGLE_FORMATTER, zoneId);
            var googleUpdDate = formatDateTime(updateInstant, GOOGLE_FORMATTER, zoneId);

            var siteName = systemInfo.getTitle();
            var siteLogo = externalLinkProcessor.processLink(systemInfo.getLogo());
            var siteKeywords =
                Optional.ofNullable(systemInfo.getSeo()).map(SystemInfo.SeoProp::getKeywords)
                    .orElse("");

            var finalKeywords = keywords.isBlank() ? siteKeywords : keywords;

            return new SeoData(title, description, coverUrl, postUrl, author, baiduPubDate,
                baiduUpdDate, googlePubDate, googleUpdDate, siteName, siteLogo, finalKeywords);
        });
    }

    private Mono<Void> generateSeoTags(SeoData seoData, IModel model, IModelFactory modelFactory) {
        return settingConfigGetter.getBasicConfig().doOnNext(config -> {
            var sb = new StringBuilder();

            // 使用if-else简化配置检查
            if (config.isEnableCanonicalLink()) {
                sb.append("<link rel=\"canonical\" href=\"")
                    .append(HtmlEscape.escapeHtml5(seoData.postUrl())).append("\" />\n");

                if (config.isEnableAlternateLink()) {
                    for (var altLink : config.getAlternateLinks()) {
                        sb.append("<link rel=\"alternate\" hreflang=\"")
                            .append(HtmlEscape.escapeHtml5(altLink.getLangCode()))
                            .append("\" href=\"").append(HtmlEscape.escapeHtml5(
                                altLink.getUrlTemplate().replace("%URL%", seoData.postUrl())))
                            .append("\" />\n");
                    }
                }
            }

            if (config.isEnableOGTimeFactor()) {
                sb.append(genOGMeta(seoData));
            }
            if (config.isEnableMetaTimeFactor()) {
                sb.append(genBytedanceMeta(seoData.baiduPubDate(), seoData.baiduUpdDate()));
            }
            if (config.isEnableBaiduTimeFactor()) {
                sb.append(genBaiduScript(seoData.title(), seoData.postUrl(), seoData.baiduPubDate(),
                    seoData.baiduUpdDate()));
            }
            if (config.isEnableStructuredData()) {
                sb.append(genSchemaOrgScript(seoData));
            }

            if (config.isEnableTwitterCards()) {
                sb.append(genTwitterCards(seoData, config.getTwitterCardsType(),
                    config.getTwitterSiteUsername(), config.getTwitterSiteUserId(),
                    config.getTwitterCreatorUsername(), config.getTwitterCreatorUserId()));
            }

            model.add(modelFactory.createText(sb.toString()));
        }).then();
    }

    private Mono<String> findTagForPost(Post post) {
        var tagNames = post.getSpec().getTags();
        if (tagNames == null || tagNames.isEmpty()) {
            return Mono.just("");
        }

        var listOptions = new ListOptions();
        listOptions.setFieldSelector(
            FieldSelector.of(Queries.in("metadata.name", tagNames.toArray(new String[0]))));

        return client.listAll(Tag.class, listOptions, Sort.by(Sort.Order.asc("metadata.name"))).map(
                tag -> Optional.ofNullable(tag.getSpec().getDisplayName())
                    .orElse(tag.getMetadata().getName())).collectList()
            .map(list -> String.join(",", list)).onErrorReturn("").defaultIfEmpty("");
    }

    private String formatDateTime(java.time.Instant instant, DateTimeFormatter formatter,
        ZoneId zoneId) {
        return Optional.ofNullable(instant).map(inst -> inst.atZone(zoneId).format(formatter))
            .orElse("");
    }

    private String genOGMeta(SeoData seoData) {
        return """
            <meta property="og:type" content="article" />
            <meta property="og:title" content="%s" />
            <meta property="og:description" content="%s" />
            <meta property="og:image" content="%s" />
            <meta property="og:url" content="%s" />
            <meta property="og:release_date" content="%s" />
            <meta property="og:modified_time" content="%s" />
            <meta property="og:author" content="%s" />
            """.formatted(HtmlEscape.escapeHtml5(seoData.title()),
            HtmlEscape.escapeHtml5(seoData.description()), seoData.coverUrl(), seoData.postUrl(),
            seoData.baiduPubDate(), seoData.baiduUpdDate(),
            HtmlEscape.escapeHtml5(seoData.author()));
    }

    private String genBytedanceMeta(String publishDate, String updateDate) {
        return """
            <meta property="bytedance:published_time" content="%s" />
            <meta property="bytedance:updated_time" content="%s" />
            """.formatted(publishDate, updateDate);
    }

    private String genBaiduScript(String title, String url, String publishDate, String updateDate) {
        return """
            <script type="application/ld+json">
            {
              "@context": "https://ziyuan.baidu.com/contexts/cambrian.jsonld",
              "@id": "%s",
              "title": "%s",
              "pubDate": "%s",
              "upDate": "%s"
            }
            </script>
            """.formatted(url, JsonEscape.escapeJson(title), publishDate, updateDate);
    }

    private String genSchemaOrgScript(SeoData seoData) {
        return """
            <script type="application/ld+json">
            {
              "@context": "https://schema.org",
              "@type": "BlogPosting",
              "mainEntityOfPage": {
                "@type": "WebPage",
                "@id": "%s"
              },
              "headline": "%s",
              "description": "%s",
              "datePublished": "%s",
              "dateModified": "%s",
              "author": {
                "@type": "Person",
                "name": "%s"
              },
              "publisher": {
                "@type": "Organization",
                "name": "%s",
                "logo": {
                  "@type": "ImageObject",
                  "url": "%s"
                }
              },
              "image": "%s",
              "url": "%s",
              "keywords": "%s"
            }
            </script>
            """.formatted(seoData.postUrl(), JsonEscape.escapeJson(seoData.title()),
            JsonEscape.escapeJson(seoData.description()), seoData.googlePubDate(),
            seoData.googleUpdDate(), JsonEscape.escapeJson(seoData.author()),
            JsonEscape.escapeJson(seoData.siteName()), seoData.siteLogo(), seoData.coverUrl(),
            seoData.postUrl(), JsonEscape.escapeJson(seoData.keywords()));
    }

    private String genTwitterCards(SeoData seoData, String twitterCardsType,
        String twitterSiteUsername, String twitterSiteUserId, String twitterCreatorUsername,
        String twitterCreatorUserId) {
        StringBuilder sb = new StringBuilder();

        // card 标签始终添加
        sb.append("<meta name=\"twitter:card\" content=\"")
            .append(HtmlEscape.escapeHtml5(twitterCardsType)).append("\" />\n");

        // 如果站点用户名非空，添加 site 标签
        if (twitterSiteUsername != null && !twitterSiteUsername.trim().isEmpty()) {
            sb.append("<meta name=\"twitter:site\" content=\"")
                .append(HtmlEscape.escapeHtml5(twitterSiteUsername)).append("\" />\n");
        }

        if (twitterSiteUserId != null && !twitterSiteUserId.trim().isEmpty()) {
            sb.append("<meta name=\"twitter:site:id\" content=\"")
                .append(HtmlEscape.escapeHtml5(twitterSiteUserId)).append("\" />\n");
        }

        // 如果创作者用户名非空，添加 creator 标签
        if (twitterCreatorUsername != null && !twitterCreatorUsername.trim().isEmpty()) {
            sb.append("<meta name=\"twitter:creator\" content=\"")
                .append(HtmlEscape.escapeHtml5(twitterCreatorUsername)).append("\" />\n");
        }

        if (twitterCreatorUserId != null && !twitterCreatorUserId.trim().isEmpty()) {
            sb.append("<meta name=\"twitter:creator:id\" content=\"")
                .append(HtmlEscape.escapeHtml5(twitterCreatorUserId)).append("\" />\n");
        }


        // 标题、描述、图片标签始终添加
        sb.append("<meta name=\"twitter:title\" content=\"")
            .append(HtmlEscape.escapeHtml5(seoData.title())).append("\" />\n");

        sb.append("<meta name=\"twitter:description\" content=\"")
            .append(HtmlEscape.escapeHtml5(seoData.description())).append("\" />\n");

        sb.append("<meta name=\"twitter:image\" content=\"")
            .append(HtmlEscape.escapeHtml5(seoData.coverUrl())).append("\" />\n");

        return sb.toString();
    }

    private record SeoData(String title, String description, String coverUrl, String postUrl,
                           String author, String baiduPubDate, String baiduUpdDate,
                           String googlePubDate, String googleUpdDate, String siteName,
                           String siteLogo, String keywords) {
    }
}
