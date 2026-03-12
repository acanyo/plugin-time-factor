package cc.lik.timefactor.process;

import cc.lik.timefactor.service.SettingConfigGetter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.RouteMatcher;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PathPatternRouteMatcher;
import org.springframework.web.util.pattern.PatternParseException;
import org.thymeleaf.context.Contexts;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import org.thymeleaf.web.IWebRequest;
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
    private final RouteMatcher routeMatcher = createRouteMatcher();

    RouteMatcher createRouteMatcher() {
        var parser = new PathPatternParser();
        parser.setPathOptions(PathContainer.Options.HTTP_PATH);
        return new PathPatternRouteMatcher(parser);
    }

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model,
        IElementModelStructureHandler handler) {

        if (!Contexts.isWebContext(context)) {
            return Mono.empty();
        }

        IWebRequest request = Contexts.asWebContext(context).getExchange().getRequest();
        String requestPath = request.getRequestPath();
        RouteMatcher.Route requestRoute = routeMatcher.parseRoute(requestPath);
        var matchedRule = matchRoute(requestRoute);

        log.debug("Request path: {}, matched route: {}", requestPath,
            matchedRule.map(PathMatchRule::template).orElse("UNMATCHED"));

        // 未匹配到任何路由规则则不生成 SEO
        return matchedRule.<Mono<Void>>map(pathMatchRule -> switch (pathMatchRule) {
            // 匹配到首页路由规则
            case INDEX -> Mono.empty();
            // 匹配到文章详情页路由规则
            case POST -> processPostRoute(context, model);
            // 其他路由规则暂不处理
            default -> Mono.empty();
        }).orElseGet(Mono::empty);
    }

    private Mono<Void> processPostRoute(ITemplateContext context, IModel model) {
        var modelFactory = context.getModelFactory();
        var postName = Optional.ofNullable(context.getVariable("name")).map(Object::toString)
            .filter(name -> !name.isEmpty()).orElse(null);

        if (postName == null) {
            return Mono.empty();
        }

        return client.fetch(Post.class, postName).flatMap(post -> buildSeoData(post).flatMap(
            seoData -> generateSeoTags(seoData, model, modelFactory)));
    }

    private Optional<PathMatchRule> matchRoute(RouteMatcher.Route requestRoute) {
        for (var rule : PathMatchRule.values()) {
            if (isMatchedRoute(requestRoute, rule)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    private boolean isMatchedRoute(RouteMatcher.Route requestRoute, PathMatchRule rule) {
        for (var pathPattern : rule.pathPatterns()) {
            try {
                if (routeMatcher.match(pathPattern, requestRoute)) {
                    return true;
                }
            } catch (PatternParseException e) {
                log.warn("Parse route pattern [{}] failed", pathPattern, e);
            }
        }
        return false;
    }

    private enum PathMatchRule {
        // TODO: 需修改，读取配置中的路由规则进行匹配 /console/settings?tab=routeRules
        // TODO: 可能要调整匹配顺序，可以参照 Halo CMS 官方实现
        INDEX("index.html", List.of("/", "/page/{page}")), // 首页
        POST("post.html", List.of("/archives/{slug}")), // 文章详情页
        ARCHIVES("archives.html",
            List.of("/archives", "/archives/{year}", "/archives/{year}/{month}")), // 归档页
        TAGS("tags.html", List.of("/tags")), // 标签页
        TAG("tag.html", List.of("/tags/{slug}")), // 标签详情页
        CATEGORIES("categories.html", List.of("/categories")), // 分类页
        CATEGORY("category.html", List.of("/categories/{slug}")), // 分类详情页
        AUTHOR("author.html", List.of("/authors/{name}")), // 作者详情页
        PAGE("page.html", List.of("/{slug}")); // 单页

        private final String template;
        private final List<String> pathPatterns;

        PathMatchRule(String template, List<String> pathPatterns) {
            this.template = template;
            this.pathPatterns = pathPatterns;
        }

        String template() {
            return template;
        }

        List<String> pathPatterns() {
            return pathPatterns;
        }
    }

    private Mono<SeoData> buildSeoData(Post post) {
        return Mono.zip(client.fetch(User.class, post.getSpec().getOwner()), findTag(post),
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

            model.add(modelFactory.createText(sb.toString()));
        }).then();
    }

    private Mono<String> findTag(Post post) {
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
            <meta property="og:type" content="article"/>
            <meta property="og:title" content="%s"/>
            <meta property="og:description" content="%s"/>
            <meta property="og:image" content="%s"/>
            <meta property="og:url" content="%s"/>
            <meta property="og:release_date" content="%s"/>
            <meta property="og:modified_time" content="%s"/>
            <meta property="og:author" content="%s"/>
            """.formatted(HtmlEscape.escapeHtml5(seoData.title()),
            HtmlEscape.escapeHtml5(seoData.description()), seoData.coverUrl(), seoData.postUrl(),
            seoData.baiduPubDate(), seoData.baiduUpdDate(),
            HtmlEscape.escapeHtml5(seoData.author()));
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

    private record SeoData(String title, String description, String coverUrl, String postUrl,
                           String author, String baiduPubDate, String baiduUpdDate,
                           String googlePubDate, String googleUpdDate, String siteName,
                           String siteLogo, String keywords) {
    }
}
