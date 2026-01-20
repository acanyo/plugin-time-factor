package cc.lik.timefactor.process;

import cc.lik.timefactor.service.SettingConfigGetter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.query.Queries;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.infra.SystemInfo;
import run.halo.app.infra.SystemInfoGetter;
import run.halo.app.theme.dialect.TemplateHeadProcessor;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TimeFactorProcess implements TemplateHeadProcessor {
    private static final DateTimeFormatter BAIDU_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter GOOGLE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private record SeoData(
        String title,
        String description,
        String coverUrl,
        String postUrl,
        String author,
        String baiduPubDate,
        String baiduUpdDate,
        String googlePubDate,
        String googleUpdDate,
        String siteName,
        String siteLogo,
        String keywords
    ) {}

    private final ReactiveExtensionClient client;
    private final SettingConfigGetter settingConfigGetter;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final SystemInfoGetter systemInfoGetter;

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model, IElementModelStructureHandler handler) {
        var modelFactory = context.getModelFactory();
        var postName = Optional.ofNullable(context.getVariable("name"))
            .map(Object::toString)
            .filter(name -> !name.isEmpty())
            .orElse(null);
            
        if (postName == null) {
            return Mono.empty();
        }

        return client.fetch(Post.class, postName)
            .flatMap(post -> buildSeoData(post)
                .flatMap(seoData -> generateSeoTags(seoData, model, modelFactory)));
    }

    private Mono<SeoData> buildSeoData(Post post) {
        return Mono.zip(
            client.fetch(User.class, post.getSpec().getOwner()),
            findTag(post),
            settingConfigGetter.getBasicConfig(),
            systemInfoGetter.get()
        ).map(tuple -> {
            var user = tuple.getT1();
            var keywords = tuple.getT2();
            var config = tuple.getT3();
            var systemInfo = tuple.getT4();
            
            var author = Optional.of(user)
                .map(User::getSpec)
                .map(User.UserSpec::getDisplayName)
                .orElse(post.getSpec().getOwner());
                
            var postUrl = externalLinkProcessor.processLink(post.getStatus().getPermalink());
            var title = post.getSpec().getTitle();
            var description = post.getSpec().getExcerpt().getRaw();
            var coverUrl = externalLinkProcessor.processLink(
                Optional.ofNullable(post.getSpec().getCover())
                    .filter(cover -> !cover.isBlank())
                    .orElse(config.getDefaultImage())
            );
            
            var publishInstant = post.getSpec().getPublishTime();
            var updateInstant = post.getStatus().getLastModifyTime();
            var zoneId = ZoneId.systemDefault();
            
            var baiduPubDate = formatDateTime(publishInstant, BAIDU_FORMATTER, zoneId);
            var baiduUpdDate = formatDateTime(updateInstant, BAIDU_FORMATTER, zoneId);
            var googlePubDate = formatDateTime(publishInstant, GOOGLE_FORMATTER, zoneId);
            var googleUpdDate = formatDateTime(updateInstant, GOOGLE_FORMATTER, zoneId);
            
            var siteName = systemInfo.getTitle();
            var siteLogo = externalLinkProcessor.processLink(systemInfo.getLogo());
            var siteKeywords = Optional.ofNullable(systemInfo.getSeo())
                .map(SystemInfo.SeoProp::getKeywords)
                .orElse("");
            
            var finalKeywords = keywords.isBlank() ? siteKeywords : keywords;
            
            return new SeoData(
                title, description, coverUrl, postUrl, author,
                baiduPubDate, baiduUpdDate, googlePubDate, googleUpdDate,
                siteName, siteLogo, finalKeywords
            );
        });
    }

    private Mono<Void> generateSeoTags(SeoData seoData, IModel model, IModelFactory modelFactory) {
        return settingConfigGetter.getBasicConfig()
            .map(config -> {
                var sb = new StringBuilder();
                
                // 使用if-else简化配置检查
                if (config.isEnableCanonicalLink()) {
                    String url = seoData.postUrl()
                        .replace("&", "&amp;")
                        .replace("\"", "&quot;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;");
                    sb.append("<link rel=\"canonical\" href=\"")
                    .append(url)
                    .append("\" />\n");
                }

                if (config.isEnableOGTimeFactor()) {
                    sb.append(genOGMeta(seoData));
                }
                if (config.isEnableMetaTimeFactor()) {
                    sb.append(genBytedanceMeta(seoData.baiduPubDate(), seoData.baiduUpdDate()));
                }
                if (config.isEnableBaiduTimeFactor()) {
                    sb.append(genBaiduScript(seoData.title(), seoData.postUrl(), seoData.baiduPubDate(), seoData.baiduUpdDate()));
                }
                if (config.isEnableStructuredData()) {
                    sb.append(genSchemaOrgScript(seoData));
                }
                
                model.add(modelFactory.createText(sb.toString()));
                return Mono.<Void>empty();
            })
            .then();
    }

    private Mono<String> findTag(Post post) {
        var tagNames = post.getSpec().getTags();
        if (tagNames == null || tagNames.isEmpty()) {
            return Mono.just("");
        }

        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(
            Queries.in("metadata.name", tagNames.toArray(new String[0]))
        ));

        return client.listAll(Tag.class, listOptions, Sort.by(Sort.Order.asc("metadata.name")))
            .map(tag -> Optional.ofNullable(tag.getSpec().getDisplayName())
                .orElse(tag.getMetadata().getName()))
            .collectList()
            .map(list -> String.join(",", list))
            .onErrorReturn("")
            .defaultIfEmpty("");
    }

    private String formatDateTime(java.time.Instant instant, DateTimeFormatter formatter, ZoneId zoneId) {
        return Optional.ofNullable(instant)
            .map(inst -> inst.atZone(zoneId).format(formatter))
            .orElse("");
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String genOGMeta(SeoData seoData) {
        return """
            <meta property="og:type" content="article"/>
            <meta property="og:title" content="%s"/>
            <meta property="og:description" content="%s"/>
            <meta property="og:image" content="%s"/>
            <meta property="og:url" content="%s"/>
            <meta property="og:release_date" content="%s"/>
            <meta property="og:modified_time" content="%s"/>
            <meta property="og:author" content="%s"/>
            """.formatted(
                seoData.title(), seoData.description(), seoData.coverUrl(), seoData.postUrl(),
                seoData.baiduPubDate(), seoData.baiduUpdDate(), seoData.author()
            );
    }

    private String genBytedanceMeta(String publishDate, String updateDate) {
        return """
            <meta property="bytedance:published_time" content="%s"/>
            <meta property="bytedance:updated_time" content="%s"/>
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
            """.formatted(url, escapeJson(title), publishDate, updateDate);
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
            """.formatted(
                seoData.postUrl(), escapeJson(seoData.title()), escapeJson(seoData.description()),
                seoData.googlePubDate(), seoData.googleUpdDate(), escapeJson(seoData.author()),
                escapeJson(seoData.siteName()), seoData.siteLogo(), seoData.coverUrl(),
                seoData.postUrl(), escapeJson(seoData.keywords())
            );
    }
}