package cc.lik.timefactor.process;

import cc.lik.timefactor.service.SettingConfigGetter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.model.IOpenElementTag;
import org.thymeleaf.model.IStandaloneElementTag;
import org.thymeleaf.model.IText;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import org.unbescape.html.HtmlEscape;
import org.unbescape.json.JsonEscape;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.MetadataOperator;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.query.Queries;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.infra.SystemInfo;
import run.halo.app.infra.SystemInfoGetter;
import run.halo.app.infra.SystemSetting;
import run.halo.app.theme.dialect.TemplateHeadProcessor;
import run.halo.app.theme.finders.vo.ExtensionVoOperator;
import run.halo.app.theme.router.ModelConst;

/**
 * 模板 ID 枚举，映射 Halo 路由系统中的 {@code _templateId} 到具体页面类型。
 *
 * <p>参考 Halo 内置模板枚举：
 * <a href="https://github.com/halo-dev/halo/blob/main/application/src/main/java/run/halo/app/theme/DefaultTemplateEnum.java">
 * DefaultTemplateEnum.java</a>
 */
@Getter
enum TemplateEnum {
    // ---- 内置模板 ID ----
    // 参考 https://github.com/halo-dev/halo/blob/main/application/src/main/java/run/halo/app/theme/DefaultTemplateEnum.java
    INDEX("index"), CATEGORIES("categories"), CATEGORY("category"), ARCHIVES("archives"),
    POST("post"), TAG("tag"), TAGS("tags"), SINGLE_PAGE("page"), AUTHOR("author"),

    // ---- 第三方插件适配 ----
    // 适配瞬间插件 https://github.com/halo-sigs/plugin-moments
    MOMENTS("moments"),  // 瞬间列表，路径 /moments
    MOMENT("moment"),    // 瞬间详情页，路径 /moments/{slug}
    // 适配图库管理插件 https://github.com/halo-sigs/plugin-photos
    PHOTOS("photos"),    // 路径 /photos
    // 适配朋友圈插件 https://github.com/chengzhongxue/plugin-friends-new
    FRIENDS("friends"),  // 路径 /friends
    // 适配豆瓣插件 https://github.com/chengzhongxue/plugin-douban
    DOUBAN("douban"),    // 路径 /douban
    // 适配 BangumiData 插件 https://github.com/ShiinaKin/halo-plugin-bangumi-data
    BANGUMI("bangumi"),  // 路径 /bangumi

    // ---- 无法适配的插件（_templateId 为 null，无法识别） ----
    // 足迹插件 https://github.com/acanyo/halo-plugin-footprint
    // 链接管理插件 https://github.com/halo-sigs/plugin-links
    // 追番插件 https://github.com/Roozenlz/plugin-bilibili-bangumi

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

/**
 * 时间因子 SEO 处理器，为 Halo 主题页面的 {@code <head>} 注入结构化 SEO 数据。
 *
 * <p>核心设计思路：
 * <ol>
 *   <li><b>模板分发</b>：根据 {@code _templateId} 上下文变量分发到对应页面处理器</li>
 *   <li><b>官方详情页使用强类型</b>：POST、SINGLE_PAGE、CATEGORY、TAG、AUTHOR 通过
 *       {@link ReactiveExtensionClient} 获取 Halo Extension 对象（Post、SinglePage、
 *       Category、Tag、User），完全避免反射，保证类型安全和编译期检查</li>
 *   <li><b>列表页使用站点信息</b>：INDEX、CATEGORIES、TAGS、ARCHIVES 等列表页没有
 *       特定实体对象，使用站点级信息（标题、描述、Logo）构建 SEO 数据</li>
 *   <li><b>第三方插件降级处理</b>：由于第三方插件的 Vo 类型不在编译期 classpath 中，
 *       第三方插件页面（MOMENTS、PHOTOS、FRIENDS、DOUBAN、BANGUMI）统一使用
 *       站点级信息 + 页面语义标题构建，部分可利用的上下文变量（如豆瓣的 title）也会被读取</li>
 *   <li><b>统一输出管道</b>：所有页面最终通过 {@link #generateSeoTags} 输出，
 *       保证标签格式一致，配置项统一生效</li>
 * </ol>
 *
 * @see TemplateHeadProcessor
 * @see <a href="https://docs.halo.run/developer-guide/theme/template-variables">Halo 模板变量文档</a>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TimeFactorProcess implements TemplateHeadProcessor {

    /**
     * 百度时间因子格式：不含时区偏移，如 {@code 2024-01-15T10:30:00}
     */
    private static final DateTimeFormatter BAIDU_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * Google/Schema.org 时间格式：含时区偏移，如 {@code 2024-01-15T10:30:00+08:00}
     */
    private static final DateTimeFormatter GOOGLE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");


    private final ReactiveExtensionClient client;
    private final SettingConfigGetter settingConfigGetter;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final SystemInfoGetter systemInfoGetter;

    // ======================== 入口方法 ========================

    /**
     * Halo 模板头处理器入口，根据模板类型分发到对应的 SEO 处理器。
     *
     * <p>从 Thymeleaf 上下文读取 {@code _templateId} 变量（由 Halo 路由系统注入），
     * 映射为 {@link TemplateEnum} 后分发到对应处理方法。未知模板返回空 Mono，
     * 不影响页面渲染。全局 onErrorResume 兜底，确保 SEO 注入失败不会影响页面加载。
     *
     * @param context Thymeleaf 模板上下文，包含路由注入的模板变量
     * @param model 待输出的 HTML 模型，SEO 标签将追加到此模型
     * @param handler 结构处理器（本插件未使用）
     */
    @Override
    public Mono<Void> process(ITemplateContext context, IModel model,
        IElementModelStructureHandler handler) {
        var templateId =
            Optional.ofNullable(context.getVariable(ModelConst.TEMPLATE_ID)).map(Object::toString)
                .orElse(null);
        var template = TemplateEnum.fromTemplateId(templateId);
        log.debug("Processing SEO for templateId: {}", templateId);

        return settingConfigGetter.getSeoOverrideConfig().flatMap(overrideCfg -> {
            // 查找与当前模板 ID 匹配的 SEO 覆盖条目
            var overrides = overrideCfg.getSeoOverrides();
            var override = SeoOverride.NONE;
            if (overrides != null && templateId != null) {
                for (var entry : overrides) {
                    if (templateId.equals(entry.getTemplateId())) {
                        override =
                            new SeoOverride(hasValue(entry.getTitle()) ? entry.getTitle() : null,
                                hasValue(entry.getDescription()) ? entry.getDescription() : null);
                        break;
                    }
                }
            }
            final var seoOverride = override;

            // 按模板类型分发，全局捕获异常避免影响页面渲染
            Mono<Void> result = switch (template) {
                // ---- 官方页面 ----
                case INDEX -> processIndexSeoData(context, model, seoOverride);
                case POST -> processPostSeoData(context, model, seoOverride);
                case SINGLE_PAGE -> processSinglePageSeoData(context, model, seoOverride);
                case CATEGORIES -> processCategoriesSeoData(context, model, seoOverride);
                case CATEGORY -> processCategorySeoData(context, model, seoOverride);
                case ARCHIVES -> processArchivesSeoData(context, model, seoOverride);
                case TAGS -> processTagsSeoData(context, model, seoOverride);
                case TAG -> processTagSeoData(context, model, seoOverride);
                case AUTHOR -> processAuthorSeoData(context, model, seoOverride);
                // ---- 第三方插件页面 ----
                case MOMENTS -> processMomentsSeoData(context, model, seoOverride);
                case MOMENT -> processMomentSeoData(context, model, seoOverride);
                case PHOTOS -> processPhotosSeoData(context, model, seoOverride);
                case FRIENDS -> processFriendsSeoData(context, model, seoOverride);
                case DOUBAN -> processDoubanSeoData(context, model, seoOverride);
                case BANGUMI -> processBangumiSeoData(context, model, seoOverride);
                case UNKNOWN -> Mono.empty();
            };

            return result.onErrorResume(error -> {
                log.warn("Failed to process SEO for templateId: {}", templateId, error);
                return Mono.empty();
            });
        }).onErrorResume(error -> {
            log.warn("Failed to fetch SEO override config for templateId: {}", templateId, error);
            return Mono.empty();
        });
    }

    // ======================== 列表页 SEO 处理器 ========================
    // 设计思路：列表页没有具体的内容实体对象，使用站点级信息 + 页面语义标题构建 SEO 数据。
    // 所有列表页共享 buildListPageSeoData 方法，仅传入不同的标题、描述和路径。

    /**
     * 首页 SEO 注入。
     *
     * <p>首页是站点的入口页面，使用站点标题和 SEO 描述构建。
     * 模板变量：{@code posts} (UrlContextListResult&lt;ListedPostVo&gt;)
     *
     * @see
     * <a href="https://docs.halo.run/developer-guide/theme/template-variables/index_">首页模板变量</a>
     */
    private Mono<Void> processIndexSeoData(ITemplateContext context, IModel model,
        SeoOverride override) {
        return buildListPageSeoData(context, model, rules -> "/", override);
    }

    /**
     * 分类集合页 SEO 注入。
     *
     * <p>展示所有分类的导航页面。
     * 模板变量：{@code categories} (List&lt;CategoryTreeVo&gt;)
     *
     * @see
     * <a href="https://docs.halo.run/developer-guide/theme/template-variables/categories">分类集合模板变量</a>
     */
    private Mono<Void> processCategoriesSeoData(ITemplateContext context, IModel model,
        SeoOverride override) {
        return buildListPageSeoData(context, model,
            rules -> Optional.ofNullable(rules.getCategories()).orElse("/categories"), override);
    }

    /**
     * 文章归档页 SEO 注入。
     *
     * <p>按时间归档的文章列表页面。
     * 模板变量：{@code archives} (UrlContextListResult&lt;PostArchiveVo&gt;)
     *
     * @see
     * <a href="https://docs.halo.run/developer-guide/theme/template-variables/archives">归档模板变量</a>
     */
    private Mono<Void> processArchivesSeoData(ITemplateContext context, IModel model,
        SeoOverride override) {
        return buildListPageSeoData(context, model,
            rules -> Optional.ofNullable(rules.getArchives()).orElse("/archives"), override);
    }

    /**
     * 标签集合页 SEO 注入。
     *
     * <p>展示所有标签的导航页面。
     * 模板变量：{@code tags} (List&lt;TagVo&gt;)
     *
     * @see
     * <a href="https://docs.halo.run/developer-guide/theme/template-variables/tags">标签集合模板变量</a>
     */
    private Mono<Void> processTagsSeoData(ITemplateContext context, IModel model,
        SeoOverride override) {
        return buildListPageSeoData(context, model,
            rules -> Optional.ofNullable(rules.getTags()).orElse("/tags"), override);
    }

    /**
     * 瞬间列表页 SEO 注入（第三方插件 plugin-moments）。
     *
     * <p>瞬间插件的列表页面，使用站点级信息构建。
     * MomentVo 结构：spec.content.html, spec.releaseTime, spec.owner, owner.displayName
     * 由于 MomentVo 不在编译期 classpath，此处使用站点级信息降级处理。
     *
     * @see <a href="https://github.com/halo-sigs/plugin-moments">plugin-moments</a>
     */
    private Mono<Void> processMomentsSeoData(ITemplateContext context, IModel model,
        SeoOverride override) {
        return buildListPageSeoData(context, model, rules -> "/moments", override);
    }

    /**
     * 瞬间详情页 SEO 注入（第三方插件 plugin-moments）。
     *
     * <p>由于 MomentVo 类型不在编译期 classpath 中，无法强类型读取。
     * 瞬间详情页通常为短内容社交帖子，站点级 SEO 已满足基本需求。
     * 如需更精确的 SEO 数据，可在未来版本通过 Finder API 增强。
     */
    private Mono<Void> processMomentSeoData(ITemplateContext context, IModel model,
        SeoOverride override) {
        // MomentVo 在上下文中是延迟加载的 Mono 对象，无法直接获取 metadata.name，降级使用站点信息
        return buildListPageSeoData(context, model, rules -> "/moments", override);
    }

    /**
     * 图库页 SEO 注入（第三方插件 plugin-photos）。
     *
     * <p>图库插件的列表页面。PhotoVo 结构：spec.displayName, spec.description,
     * spec.url, spec.cover。由于不在 classpath，使用站点级信息降级。
     *
     * @see <a href="https://github.com/halo-sigs/plugin-photos">plugin-photos</a>
     */
    private Mono<Void> processPhotosSeoData(ITemplateContext context, IModel model,
        SeoOverride override) {
        return buildListPageSeoData(context, model, rules -> "/photos", override);
    }

    /**
     * 朋友圈页 SEO 注入（第三方插件 plugin-friends-new）。
     *
     * <p>朋友圈插件的列表页面。FriendPostVo 结构：spec.author, spec.logo,
     * spec.title, spec.postLink, spec.description, spec.pubDate。
     * 由于不在 classpath，使用站点级信息降级。
     *
     * @see <a href="https://github.com/chengzhongxue/plugin-friends-new">plugin-friends-new</a>
     */
    private Mono<Void> processFriendsSeoData(ITemplateContext context, IModel model,
        SeoOverride override) {
        return buildListPageSeoData(context, model, rules -> "/friends", override);
    }

    /**
     * 豆瓣页 SEO 注入（第三方插件 plugin-douban）。
     *
     * <p>豆瓣插件路由在上下文中设置了 {@code title} 变量
     * （由 {@code DoubanRouter.getDoubanTitle()} 生成），优先使用该变量作为页面标题。
     * DoubanMovieVo 结构：spec.name, spec.poster, spec.link, spec.score, spec.year,
     * faves.createTime, faves.remark。
     *
     * @see <a href="https://github.com/chengzhongxue/plugin-douban">plugin-douban</a>
     */
    private Mono<Void> processDoubanSeoData(ITemplateContext context, IModel model,
        SeoOverride override) {
        // 豆瓣路由在上下文中注入了 title 变量，作为 <title> 提取失败时的中间回退
        var contextTitle = Optional.ofNullable(context.getVariable("title")).map(Object::toString)
            .filter(s -> !s.isBlank()).orElse(null);
        return buildListPageSeoData(context, model, rules -> "/douban", override, contextTitle,
            null);
    }

    /**
     * 番剧页 SEO 注入（第三方插件 halo-plugin-bangumi-data）。
     *
     * <p>番剧插件通过 {@code bangumiDataFinder} 提供数据。
     * BangumiUserData（Kotlin 数据类）结构：spec.nickname, spec.avatar, spec.sign,
     * spec.*CollectionJson（各类收藏的 JSON 字符串）。
     * 由于数据模型为 Kotlin 类且不在 classpath，使用站点级信息降级。
     *
     * @see
     * <a href="https://github.com/ShiinaKin/halo-plugin-bangumi-data">halo-plugin-bangumi-data</a>
     */
    private Mono<Void> processBangumiSeoData(ITemplateContext context, IModel model,
        SeoOverride override) {
        return buildListPageSeoData(context, model, rules -> "/bangumi", override);
    }

    // ======================== 详情页 SEO 处理器 ========================
    // 设计思路：详情页有具体的内容实体，通过上下文变量 name（Halo 路由系统注入的
    // metadata.name）获取实体标识，再用 ReactiveExtensionClient 获取强类型 Extension
    // 对象，完全避免反射。这与文章详情页（POST）的实现方式一致。

    /**
     * 文章详情页 SEO 注入。
     *
     * <p>通过上下文变量 {@code name}（文章的 metadata.name）获取强类型
     * {@link Post} 对象，提取标题、摘要、封面、作者、标签、发布/更新时间等完整 SEO 信息。
     * 这是插件最核心的 SEO 注入场景，字段精度最高。
     *
     * <p>模板变量：{@code post} (PostVo), {@code name} (String)
     * <p>数据来源：Post.spec.title, Post.status.excerpt, Post.spec.cover,
     * Post.status.permalink, Post.spec.owner → User.spec.displayName,
     * Post.spec.tags → Tag.spec.displayName, Post.spec.publishTime,
     * Post.status.lastModifyTime
     *
     * @see <a href="https://docs.halo.run/developer-guide/theme/template-variables/post">文章模板变量</a>
     */
    private Mono<Void> processPostSeoData(ITemplateContext context, IModel model,
        SeoOverride override) {
        var modelFactory = context.getModelFactory();
        var postName = getNameVariable(context);
        if (postName == null) {
            return Mono.empty();
        }
        var extractedTitle = extractTitleFromModel(model);
        var extractedDesc = extractMetaDescriptionFromModel(model);
        var title = hasValue(override.title()) ? override.title() : extractedTitle;
        var description = hasValue(override.description()) ? override.description() : extractedDesc;
        return client.fetch(Post.class, postName).flatMap(
            post -> buildSeoDataForPost(post, title, description, extractedTitle,
                extractedDesc).flatMap(seoData -> generateSeoTags(seoData, model, modelFactory)));
    }

    /**
     * 分类详情页 SEO 注入。
     *
     * <p>通过上下文变量 {@code name}（分类的 metadata.name）获取强类型
     * {@link Category} 对象。分类拥有 displayName、description、cover 等字段，
     * 但没有独立的作者和发布时间。
     *
     * <p>模板变量：{@code category} (CategoryVo), {@code posts} (UrlContextListResult),
     * {@code name} (String)
     * <p>数据来源：Category.spec.displayName, Category.spec.description,
     * Category.spec.cover, Category.status.permalink
     *
     * @see
     * <a href="https://docs.halo.run/developer-guide/theme/template-variables/category">分类详情模板变量</a>
     */
    private Mono<Void> processCategorySeoData(ITemplateContext context, IModel model,
        SeoOverride override) {
        var modelFactory = context.getModelFactory();
        // 分类详情页不注入 name 变量，从上下文的 CategoryVo 对象取 metadata.name
        var categoryName = getMetadataNameFromVo(context, "category");
        if (categoryName == null) {
            return Mono.empty();
        }
        var extractedTitle = extractTitleFromModel(model);
        var extractedDesc = extractMetaDescriptionFromModel(model);
        var title = hasValue(override.title()) ? override.title() : extractedTitle;
        var description = hasValue(override.description()) ? override.description() : extractedDesc;
        return client.fetch(Category.class, categoryName).flatMap(
            category -> buildSeoDataForCategory(category, title, description, extractedTitle,
                extractedDesc).flatMap(seoData -> generateSeoTags(seoData, model, modelFactory)));
    }

    /**
     * 标签详情页 SEO 注入。
     *
     * <p>通过上下文变量 {@code name}（标签的 metadata.name）获取强类型
     * {@link Tag} 对象。注意：Tag 没有 description 字段，使用
     * "标签: displayName" 作为描述。
     *
     * <p>模板变量：{@code tag} (TagVo), {@code posts} (UrlContextListResult),
     * {@code name} (String)
     * <p>数据来源：Tag.spec.displayName, Tag.spec.cover, Tag.status.permalink
     *
     * @see
     * <a href="https://docs.halo.run/developer-guide/theme/template-variables/tag">标签详情模板变量</a>
     */
    private Mono<Void> processTagSeoData(ITemplateContext context, IModel model,
        SeoOverride override) {
        var modelFactory = context.getModelFactory();
        var tagName = getNameVariable(context);
        if (tagName == null) {
            return Mono.empty();
        }
        var extractedTitle = extractTitleFromModel(model);
        var extractedDesc = extractMetaDescriptionFromModel(model);
        var title = hasValue(override.title()) ? override.title() : extractedTitle;
        var description = hasValue(override.description()) ? override.description() : extractedDesc;
        return client.fetch(Tag.class, tagName).flatMap(
            tag -> buildSeoDataForTag(tag, title, description, extractedTitle,
                extractedDesc).flatMap(seoData -> generateSeoTags(seoData, model, modelFactory)));
    }

    /**
     * 独立页面 SEO 注入。
     *
     * <p>独立页面与文章结构类似，拥有标题、摘要、封面、发布时间等字段，
     * 通过 {@code name} 上下文变量获取 {@link SinglePage} 对象。
     * 与文章的区别：独立页面没有分类和标签，关键词使用站点级 SEO 关键词。
     *
     * <p>模板变量：{@code singlePage} (SinglePageVo), {@code name} (String)
     * <p>数据来源：SinglePage.spec.title, SinglePage.spec.cover,
     * SinglePage.spec.publishTime, SinglePage.spec.owner → User.spec.displayName,
     * SinglePage.status.permalink, SinglePage.status.excerpt,
     * SinglePage.status.lastModifyTime（SinglePageStatus 继承 PostStatus）
     *
     * @see
     * <a href="https://docs.halo.run/developer-guide/theme/template-variables/page">独立页面模板变量</a>
     */
    private Mono<Void> processSinglePageSeoData(ITemplateContext context, IModel model,
        SeoOverride override) {
        var modelFactory = context.getModelFactory();
        var pageName = getNameVariable(context);
        if (pageName == null) {
            return Mono.empty();
        }
        var extractedTitle = extractTitleFromModel(model);
        var extractedDesc = extractMetaDescriptionFromModel(model);
        var title = hasValue(override.title()) ? override.title() : extractedTitle;
        var description = hasValue(override.description()) ? override.description() : extractedDesc;
        return client.fetch(SinglePage.class, pageName).flatMap(
            page -> buildSeoDataForSinglePage(page, title, description, extractedTitle,
                extractedDesc).flatMap(seoData -> generateSeoTags(seoData, model, modelFactory)));
    }

    /**
     * 作者页 SEO 注入。
     *
     * <p>通过上下文变量 {@code name}（用户的 metadata.name）获取强类型
     * {@link User} 对象。注意：User 没有 status.permalink 字段，
     * URL 通过 "/authors/" + metadata.name 手动构造。
     *
     * <p>模板变量：{@code author} (UserVo), {@code posts} (UrlContextListResult),
     * {@code name} (String)
     * <p>数据来源：User.spec.displayName, User.spec.bio, User.spec.avatar
     *
     * @see
     * <a href="https://docs.halo.run/developer-guide/theme/template-variables/author">作者模板变量</a>
     */
    private Mono<Void> processAuthorSeoData(ITemplateContext context, IModel model,
        SeoOverride override) {
        var modelFactory = context.getModelFactory();
        var userName = getNameVariable(context);
        if (userName == null) {
            return Mono.empty();
        }
        var extractedTitle = extractTitleFromModel(model);
        var extractedDesc = extractMetaDescriptionFromModel(model);
        var title = hasValue(override.title()) ? override.title() : extractedTitle;
        var description = hasValue(override.description()) ? override.description() : extractedDesc;
        return client.fetch(User.class, userName).flatMap(
            user -> buildSeoDataForAuthor(user, title, description, extractedTitle,
                extractedDesc).flatMap(seoData -> generateSeoTags(seoData, model, modelFactory)));
    }

    // ======================== SEO 数据构建方法 ========================

    /**
     * 列表页通用 SEO 数据构建。
     *
     * <p>设计思路：列表页（首页、分类集合、标签集合、文章归档页、第三方插件列表页）
     * 没有具体的内容实体对象，使用站点级信息构建 SEO 数据。
     * <ul>
     *   <li>标题格式："pageTitle - siteName"（站点名称为空时仅 pageTitle）</li>
     *   <li>描述优先级：传入描述 &gt; 站点 SEO 描述 &gt; 页面标题</li>
     *   <li>封面：使用插件设置的默认封面，回退到站点 Logo</li>
     *   <li>URL：通过 {@link ExternalLinkProcessor} 将路径转为完整外部链接</li>
     *   <li>作者/日期：列表页不包含具体的作者和时间信息，字段留空</li>
     * </ul>
     *
     * @param context Thymeleaf 模板上下文
     * @param model HTML 输出模型
     * @param pathProvider 页面路径提供函数，接收 {@link SystemSetting.ThemeRouteRules} 以支持可配置路由前缀
     */
    private Mono<Void> buildListPageSeoData(ITemplateContext context, IModel model,
        Function<SystemSetting.ThemeRouteRules, String> pathProvider, SeoOverride override) {
        return buildListPageSeoData(context, model, pathProvider, override, null, null);
    }

    private Mono<Void> buildListPageSeoData(ITemplateContext context, IModel model,
        Function<SystemSetting.ThemeRouteRules, String> pathProvider, SeoOverride override,
        String intermediateTitle, String intermediateDesc) {
        var modelFactory = context.getModelFactory();
        // 始终提取原始值，用于 %TITLE%/%DESC% 占位符展开
        var extractedTitle = extractTitleFromModel(model);
        var extractedDesc = extractMetaDescriptionFromModel(model);
        // 覆盖值优先；无覆盖时使用提取值
        var title = hasValue(override.title()) ? override.title() : extractedTitle;
        var description = hasValue(override.description()) ? override.description() : extractedDesc;

        return Mono.zip(settingConfigGetter.getBasicConfig(), systemInfoGetter.get(),
            getThemeRouteRules()).flatMap(tuple -> {
            var config = tuple.getT1();
            var systemInfo = tuple.getT2();
            var routeRules = tuple.getT3();

            var siteName = systemInfo.getTitle();
            var siteLogo = processPermalink(systemInfo.getLogo());
            var siteDesc =
                Optional.ofNullable(systemInfo.getSeo()).map(SystemInfo.SeoProp::getDescription)
                    .orElse(null);

            // %TITLE%/%DESC% 展开时，用 extracted 优先、intermediate 兜底的有效值，
            // 避免 extracted 为 null 时占位符展开为空串导致 firstNonBlank 无法正常回退
            var phTitle = firstNonBlank(extractedTitle, intermediateTitle);
            var phDesc = firstNonBlank(extractedDesc, intermediateDesc);
            // 标题：覆盖值（占位符已替换） → 中间值（实体/插件上下文变量） → 站点标题 → 空
            var finalTitle = firstNonBlank(applyPlaceholders(title, phTitle, phDesc, systemInfo),
                intermediateTitle, siteName);
            // 描述：覆盖值（占位符已替换） → 中间值 → 站点描述 → 空
            var finalDesc =
                firstNonBlank(applyPlaceholders(description, phTitle, phDesc, systemInfo),
                    intermediateDesc, siteDesc);

            // 通过 ExternalLinkProcessor 将相对路径转为完整的外部 URL
            var pageUrl = externalLinkProcessor.processLink(pathProvider.apply(routeRules));
            var coverUrl =
                processPermalink(firstNonBlank(config.getDefaultImage(), systemInfo.getLogo()));
            var keywords =
                Optional.ofNullable(systemInfo.getSeo()).map(SystemInfo.SeoProp::getKeywords)
                    .orElse(null);

            // 列表页没有具体的发布/更新时间，相关字段留空；
            // 作者优先使用配置的默认作者，回退到站点名称；pageType 为 website
            var author = firstNonBlank(config.getDefaultAuthor(), siteName);
            var seoData =
                new SeoData(finalTitle, finalDesc, coverUrl, pageUrl, author, null, null, null,
                    null, siteName, siteLogo, keywords, "website");

            return generateSeoTags(seoData, model, modelFactory);
        }).onErrorResume(error -> {
            log.warn("Failed to build list page SEO", error);
            return Mono.empty();
        });
    }

    /**
     * 构建文章详情页 SEO 数据。
     *
     * <p>从 {@link Post} 强类型对象提取完整的 SEO 信息。
     * 并行获取四个数据源：文章所有者（User）、文章标签（Tag）、插件配置、系统信息。
     *
     * <p>字段映射：
     * <ul>
     *   <li>标题 ← {@code post.spec.title}</li>
     *   <li>摘要 ← {@code post.status.excerpt}</li>
     *   <li>封面 ← {@code post.spec.cover}，回退到插件默认封面</li>
     *   <li>URL ← {@code post.status.permalink}</li>
     *   <li>作者 ← 通过 {@code post.spec.owner} 查询 User 获取 displayName</li>
     *   <li>关键词 ← 通过 {@code post.spec.tags} 查询 Tag 获取 displayName，
     *       回退到站点 SEO 关键词</li>
     *   <li>发布时间 ← {@code post.spec.publishTime}</li>
     *   <li>更新时间 ← {@code post.status.lastModifyTime}</li>
     * </ul>
     *
     * @param post 文章 Extension 对象
     * @return 包含完整 SEO 数据的 Mono
     */
    private Mono<SeoData> buildSeoDataForPost(Post post, String title, String description,
        String extractedTitle, String extractedDesc) {
        // 并行获取：文章所有者、文章标签、插件配置、系统信息
        return Mono.zip(client.fetch(User.class, post.getSpec().getOwner()), findTagForPost(post),
            settingConfigGetter.getBasicConfig(), systemInfoGetter.get()).map(tuple -> {
            var user = tuple.getT1();
            var keywords = tuple.getT2();
            var config = tuple.getT3();
            var systemInfo = tuple.getT4();

            // 作者名：优先 User.spec.displayName，回退到 owner 标识符
            var author = Optional.of(user).map(User::getSpec).map(User.UserSpec::getDisplayName)
                .orElse(post.getSpec().getOwner());

            // status 字段在 Reconciler 首次处理前为 null，使用 getStatusOrDefault() 安全获取
            var status = post.getStatusOrDefault();
            var postUrl = processPermalink(status.getPermalink());
            // 标题/描述：页面 <title>/<meta description> → 实体固定值 → 站点级
            var siteName = systemInfo.getTitle();
            var siteDesc =
                Optional.ofNullable(systemInfo.getSeo()).map(SystemInfo.SeoProp::getDescription)
                    .orElse(null);
            var phTitle = firstNonBlank(extractedTitle, post.getSpec().getTitle());
            var phDesc = firstNonBlank(extractedDesc, status.getExcerpt());
            var finalTitle = firstNonBlank(applyPlaceholders(title, phTitle, phDesc, systemInfo),
                post.getSpec().getTitle(), siteName);
            var finalDesc =
                firstNonBlank(applyPlaceholders(description, phTitle, phDesc, systemInfo),
                    status.getExcerpt(), siteDesc);
            // 封面：优先文章封面，回退到插件默认封面
            var coverUrl = processPermalink(
                firstNonBlank(post.getSpec().getCover(), config.getDefaultImage()));

            var publishInstant = post.getSpec().getPublishTime();
            var updateInstant = status.getLastModifyTime();
            var zoneId = ZoneId.systemDefault();

            var siteLogo = processPermalink(systemInfo.getLogo());
            var siteKeywords =
                Optional.ofNullable(systemInfo.getSeo()).map(SystemInfo.SeoProp::getKeywords)
                    .orElse(null);
            // 关键词：优先文章标签，回退到站点 SEO 关键词
            var finalKeywords = hasValue(keywords) ? keywords : siteKeywords;

            return new SeoData(finalTitle, finalDesc, coverUrl, postUrl, author,
                formatDateTime(publishInstant, BAIDU_FORMATTER, zoneId),
                formatDateTime(updateInstant, BAIDU_FORMATTER, zoneId),
                formatDateTime(publishInstant, GOOGLE_FORMATTER, zoneId),
                formatDateTime(updateInstant, GOOGLE_FORMATTER, zoneId), siteName, siteLogo,
                finalKeywords, "article");
        });
    }

    /**
     * 构建独立页面 SEO 数据。
     *
     * <p>结构类似文章，从 {@link SinglePage} 提取 SEO 信息。
     * 与文章的区别：独立页面没有分类和标签，关键词使用站点级 SEO 关键词。
     * SinglePageStatus 继承 PostStatus，共享 getPermalink、getExcerpt、
     * getLastModifyTime 方法。
     *
     * <p>字段映射：
     * <ul>
     *   <li>标题 ← {@code singlePage.spec.title}</li>
     *   <li>摘要 ← {@code singlePage.status.excerpt}</li>
     *   <li>封面 ← {@code singlePage.spec.cover}，回退到插件默认封面</li>
     *   <li>URL ← {@code singlePage.status.permalink}</li>
     *   <li>作者 ← 通过 {@code singlePage.spec.owner} 查询 User</li>
     *   <li>日期 ← {@code spec.publishTime}, {@code status.lastModifyTime}</li>
     *   <li>关键词 ← 站点 SEO 关键词（独立页面无标签）</li>
     * </ul>
     *
     * @param page 独立页面 Extension 对象
     * @return 包含 SEO 数据的 Mono
     */
    private Mono<SeoData> buildSeoDataForSinglePage(SinglePage page, String title,
        String description, String extractedTitle, String extractedDesc) {
        return Mono.zip(client.fetch(User.class, page.getSpec().getOwner()),
            settingConfigGetter.getBasicConfig(), systemInfoGetter.get()).map(tuple -> {
            var user = tuple.getT1();
            var config = tuple.getT2();
            var systemInfo = tuple.getT3();

            var author = Optional.of(user).map(User::getSpec).map(User.UserSpec::getDisplayName)
                .orElse(page.getSpec().getOwner());

            var status = page.getStatusOrDefault();
            var pageUrl = processPermalink(status.getPermalink());
            var coverUrl = processPermalink(
                firstNonBlank(page.getSpec().getCover(), config.getDefaultImage()));

            var publishInstant = page.getSpec().getPublishTime();
            var updateInstant = status.getLastModifyTime();
            var zoneId = ZoneId.systemDefault();

            var siteName = systemInfo.getTitle();
            var siteLogo = processPermalink(systemInfo.getLogo());
            var siteDesc =
                Optional.ofNullable(systemInfo.getSeo()).map(SystemInfo.SeoProp::getDescription)
                    .orElse(null);
            var keywords =
                Optional.ofNullable(systemInfo.getSeo()).map(SystemInfo.SeoProp::getKeywords)
                    .orElse(null);

            var phTitle = firstNonBlank(extractedTitle, page.getSpec().getTitle());
            var phDesc = firstNonBlank(extractedDesc, status.getExcerpt());
            return new SeoData(firstNonBlank(applyPlaceholders(title, phTitle, phDesc, systemInfo),
                page.getSpec().getTitle(), siteName),
                firstNonBlank(applyPlaceholders(description, phTitle, phDesc, systemInfo),
                    status.getExcerpt(), siteDesc), coverUrl, pageUrl, author,
                formatDateTime(publishInstant, BAIDU_FORMATTER, zoneId),
                formatDateTime(updateInstant, BAIDU_FORMATTER, zoneId),
                formatDateTime(publishInstant, GOOGLE_FORMATTER, zoneId),
                formatDateTime(updateInstant, GOOGLE_FORMATTER, zoneId), siteName, siteLogo,
                keywords, "article");
        });
    }

    /**
     * 构建分类详情页 SEO 数据。
     *
     * <p>从 {@link Category} 提取分类级 SEO 信息。分类没有独立的作者和发布时间，
     * 作者字段使用站点名称，日期字段留空。
     *
     * <p>字段映射：
     * <ul>
     *   <li>标题 ← {@code category.spec.displayName} + " - " + 站点名称</li>
     *   <li>描述 ← {@code category.spec.description}，回退到 "分类: displayName"</li>
     *   <li>封面 ← {@code category.spec.cover}，回退到默认封面/站点 Logo</li>
     *   <li>URL ← {@code category.status.permalink}</li>
     *   <li>关键词 ← 分类显示名</li>
     * </ul>
     *
     * @param category 分类 Extension 对象
     * @return 包含 SEO 数据的 Mono
     */
    private Mono<SeoData> buildSeoDataForCategory(Category category, String title,
        String description, String extractedTitle, String extractedDesc) {
        return Mono.zip(settingConfigGetter.getBasicConfig(), systemInfoGetter.get()).map(tuple -> {
            var config = tuple.getT1();
            var systemInfo = tuple.getT2();

            var siteName = systemInfo.getTitle();
            var siteDesc =
                Optional.ofNullable(systemInfo.getSeo()).map(SystemInfo.SeoProp::getDescription)
                    .orElse(null);
            var pageUrl = processPermalink(category.getStatusOrDefault().getPermalink());
            var coverUrl = processPermalink(
                firstNonBlank(category.getSpec().getCover(), config.getDefaultImage(),
                    systemInfo.getLogo()));
            var siteLogo = processPermalink(systemInfo.getLogo());
            var author = firstNonBlank(config.getDefaultAuthor(), siteName);
            var displayName = category.getSpec().getDisplayName();

            var phTitle = firstNonBlank(extractedTitle, category.getSpec().getDisplayName());
            var phDesc = firstNonBlank(extractedDesc, category.getSpec().getDescription());
            return new SeoData(firstNonBlank(applyPlaceholders(title, phTitle, phDesc, systemInfo),
                category.getSpec().getDisplayName(), siteName),
                firstNonBlank(applyPlaceholders(description, phTitle, phDesc, systemInfo),
                    category.getSpec().getDescription(), siteDesc), coverUrl, pageUrl, author, null,
                null, null, null, siteName, siteLogo, displayName, "website");
        });
    }

    /**
     * 构建标签详情页 SEO 数据。
     *
     * <p>从 {@link Tag} 提取标签级 SEO 信息。标签没有 description 字段，
     * 描述使用 "标签: displayName" 构造。标签也没有独立的作者和发布时间。
     *
     * <p>字段映射：
     * <ul>
     *   <li>标题 ← {@code tag.spec.displayName} + " - " + 站点名称</li>
     *   <li>描述 ← {@code tag.spec.description}，回退到 "标签: displayName"</li>
     *   <li>封面 ← {@code tag.spec.cover}，回退到默认封面/站点 Logo</li>
     *   <li>URL ← {@code tag.status.permalink}</li>
     *   <li>关键词 ← 标签显示名</li>
     * </ul>
     *
     * @param tag 标签 Extension 对象
     * @return 包含 SEO 数据的 Mono
     */
    private Mono<SeoData> buildSeoDataForTag(Tag tag, String title, String description,
        String extractedTitle, String extractedDesc) {
        return Mono.zip(settingConfigGetter.getBasicConfig(), systemInfoGetter.get()).map(tuple -> {
            var config = tuple.getT1();
            var systemInfo = tuple.getT2();

            var siteName = systemInfo.getTitle();
            var siteDesc =
                Optional.ofNullable(systemInfo.getSeo()).map(SystemInfo.SeoProp::getDescription)
                    .orElse(null);
            var pageUrl = processPermalink(tag.getStatusOrDefault().getPermalink());
            var coverUrl = processPermalink(
                firstNonBlank(tag.getSpec().getCover(), config.getDefaultImage(),
                    systemInfo.getLogo()));
            var siteLogo = processPermalink(systemInfo.getLogo());
            var author = firstNonBlank(config.getDefaultAuthor(), siteName);
            var displayName = tag.getSpec().getDisplayName();

            var phTitle = firstNonBlank(extractedTitle, tag.getSpec().getDisplayName());
            var phDesc = firstNonBlank(extractedDesc, tag.getSpec().getDescription());
            return new SeoData(firstNonBlank(applyPlaceholders(title, phTitle, phDesc, systemInfo),
                tag.getSpec().getDisplayName(), siteName),
                firstNonBlank(applyPlaceholders(description, phTitle, phDesc, systemInfo),
                    tag.getSpec().getDescription(), siteDesc), coverUrl, pageUrl, author, null,
                null, null, null, siteName, siteLogo, displayName, "website");
        });
    }

    /**
     * 构建作者页 SEO 数据。
     *
     * <p>从 {@link User} 提取作者级 SEO 信息。注意 User 没有
     * {@code status.permalink} 字段，URL 通过 "/authors/" + metadata.name 手动构造。
     *
     * <p>字段映射：
     * <ul>
     *   <li>标题 ← {@code user.spec.displayName} + " - " + 站点名称</li>
     *   <li>描述 ← {@code user.spec.bio}，回退到 "作者: displayName"</li>
     *   <li>封面 ← {@code user.spec.avatar}，回退到默认封面/站点 Logo</li>
     *   <li>URL ← "/authors/" + {@code user.metadata.name}</li>
     *   <li>作者 ← {@code user.spec.displayName}</li>
     *   <li>关键词 ← 作者显示名</li>
     * </ul>
     *
     * @param user 用户 Extension 对象
     * @return 包含 SEO 数据的 Mono
     */
    private Mono<SeoData> buildSeoDataForAuthor(User user, String title, String description,
        String extractedTitle, String extractedDesc) {
        return Mono.zip(settingConfigGetter.getBasicConfig(), systemInfoGetter.get()).map(tuple -> {
            var config = tuple.getT1();
            var systemInfo = tuple.getT2();

            var siteName = systemInfo.getTitle();
            var siteDesc =
                Optional.ofNullable(systemInfo.getSeo()).map(SystemInfo.SeoProp::getDescription)
                    .orElse(null);
            var displayName = user.getSpec().getDisplayName();
            var pageUrl =
                externalLinkProcessor.processLink("/authors/" + user.getMetadata().getName());
            var coverUrl = processPermalink(
                firstNonBlank(user.getSpec().getAvatar(), config.getDefaultImage(),
                    systemInfo.getLogo()));
            var siteLogo = processPermalink(systemInfo.getLogo());

            var phTitle = firstNonBlank(extractedTitle, user.getSpec().getDisplayName());
            var phDesc = firstNonBlank(extractedDesc, user.getSpec().getBio());
            return new SeoData(firstNonBlank(applyPlaceholders(title, phTitle, phDesc, systemInfo),
                user.getSpec().getDisplayName(), siteName),
                firstNonBlank(applyPlaceholders(description, phTitle, phDesc, systemInfo),
                    user.getSpec().getBio(), siteDesc), coverUrl, pageUrl, displayName, null, null,
                null, null, siteName, siteLogo, displayName, "profile");
        });
    }

    // ======================== SEO 标签生成管道 ========================

    /**
     * 根据 SEO 数据和插件配置生成完整的 HTML meta/script 标签。
     *
     * <p>所有页面类型的 SEO 数据最终汇入此方法，按插件设置依次生成：
     * <ol>
     *   <li>canonical 链接标签（帮助搜索引擎识别内容唯一地址）</li>
     *   <li>alternate 链接标签（多语言/多版本支持）</li>
     *   <li>Open Graph (OG) meta 标签（主流社交平台和搜索引擎）</li>
     *   <li>字节跳动 (Bytedance) meta 标签（头条系搜索引擎）</li>
     *   <li>百度时间因子 script 标签（百度搜索引擎）</li>
     *   <li>Schema.org JSON-LD 结构化数据（Google、Bing）</li>
     *   <li>Twitter Cards meta 标签（Twitter/X 平台）</li>
     * </ol>
     *
     * @param seoData SEO 数据记录
     * @param model HTML 输出模型
     * @param modelFactory Thymeleaf 模型工厂，用于创建 HTML 文本节点
     */
    private Mono<Void> generateSeoTags(SeoData seoData, IModel model, IModelFactory modelFactory) {
        return settingConfigGetter.getBasicConfig().doOnNext(config -> {
            var sb = new StringBuilder();

            // canonical 和 alternate 链接（pageUrl 为空时跳过，无有效 URL 的页面不输出 canonical）
            if (config.isEnableCanonicalLink() && hasValue(seoData.pageUrl())) {
                sb.append("<link rel=\"canonical\" href=\"")
                    .append(HtmlEscape.escapeHtml5(seoData.pageUrl())).append("\" />\n");

                if (config.isEnableAlternateLink()) {
                    var alternateLinks = config.getAlternateLinks();
                    if (alternateLinks == null) {
                        log.warn(
                            "Alternate link is enabled but alternateLinks list is null, skipping "
                                + "alternate tags");
                    } else {
                        for (var altLink : alternateLinks) {
                            sb.append("<link rel=\"alternate\" hreflang=\"")
                                .append(HtmlEscape.escapeHtml5(altLink.getLangCode()))
                                .append("\" href=\"").append(HtmlEscape.escapeHtml5(
                                    altLink.getUrlTemplate().replace("%URL%", seoData.pageUrl())))
                                .append("\" />\n");
                        }
                    }
                }
            }

            // OG meta 标签
            if (config.isEnableOGTimeFactor()) {
                sb.append(genOGMeta(seoData));
            }
            // 字节跳动 meta 标签
            if (config.isEnableMetaTimeFactor()) {
                sb.append(genBytedanceMeta(seoData.baiduPubDate(), seoData.baiduUpdDate()));
            }
            // 百度时间因子
            if (config.isEnableBaiduTimeFactor()) {
                sb.append(genBaiduScript(seoData.title(), seoData.pageUrl(), seoData.baiduPubDate(),
                    seoData.baiduUpdDate()));
            }
            // Schema.org JSON-LD
            if (config.isEnableStructuredData()) {
                sb.append(genSchemaOrgScript(seoData));
            }
            // Twitter Cards
            if (config.isEnableTwitterCards()) {
                sb.append(genTwitterCards(seoData, config.getTwitterCardsType(),
                    config.getTwitterSiteUsername(), config.getTwitterSiteUserId(),
                    config.getTwitterCreatorUsername(), config.getTwitterCreatorUserId()));
            }

            model.add(modelFactory.createText(sb.toString()));
        }).then().onErrorResume(error -> {
            log.warn("Failed to generate SEO tags", error);
            return Mono.empty();
        });
    }

    // ======================== 标签生成方法 ========================

    /**
     * 生成 Open Graph meta 标签。
     *
     * <p>OG 协议被 Facebook、LinkedIn、微信等主流社交平台支持，
     * 也被 Google、Bing 等搜索引擎用于增强搜索结果展示。
     *
     * @see <a href="https://ogp.me/">The Open Graph protocol</a>
     */
    private String genOGMeta(SeoData seoData) {
        // 所有字段按需输出：null 或空白时跳过对应标签，避免生成无意义的空标签
        var sb = new StringBuilder();
        sb.append("<meta property=\"og:type\" content=\"")
            .append(HtmlEscape.escapeHtml5(seoData.pageType())).append("\" />\n");
        if (hasValue(seoData.title())) {
            sb.append("<meta property=\"og:title\" content=\"")
                .append(HtmlEscape.escapeHtml5(seoData.title())).append("\" />\n");
        }
        if (hasValue(seoData.description())) {
            sb.append("<meta property=\"og:description\" content=\"")
                .append(HtmlEscape.escapeHtml5(seoData.description())).append("\" />\n");
        }
        if (hasValue(seoData.coverUrl())) {
            sb.append("<meta property=\"og:image\" content=\"")
                .append(HtmlEscape.escapeHtml5(seoData.coverUrl())).append("\" />\n");
        }
        if (hasValue(seoData.pageUrl())) {
            sb.append("<meta property=\"og:url\" content=\"")
                .append(HtmlEscape.escapeHtml5(seoData.pageUrl())).append("\" />\n");
        }
        if (hasValue(seoData.baiduPubDate())) {
            sb.append("<meta property=\"og:release_date\" content=\"")
                .append(HtmlEscape.escapeHtml5(seoData.baiduPubDate())).append("\" />\n");
        }
        if (hasValue(seoData.baiduUpdDate())) {
            sb.append("<meta property=\"og:modified_time\" content=\"")
                .append(HtmlEscape.escapeHtml5(seoData.baiduUpdDate())).append("\" />\n");
        }
        if (hasValue(seoData.author())) {
            sb.append("<meta property=\"og:author\" content=\"")
                .append(HtmlEscape.escapeHtml5(seoData.author())).append("\" />\n");
        }
        return sb.toString();
    }

    /**
     * 生成字节跳动时间因子 meta 标签。
     *
     * <p>用于今日头条、抖音搜索等字节系产品的 SEO 优化。
     */
    private String genBytedanceMeta(String publishDate, String updateDate) {
        var sb = new StringBuilder();
        if (hasValue(publishDate)) {
            sb.append("<meta property=\"bytedance:published_time\" content=\"")
                .append(HtmlEscape.escapeHtml5(publishDate)).append("\" />\n");
        }
        if (hasValue(updateDate)) {
            sb.append("<meta property=\"bytedance:updated_time\" content=\"")
                .append(HtmlEscape.escapeHtml5(updateDate)).append("\" />\n");
        }
        return sb.toString();
    }

    /**
     * 生成百度时间因子 JSON-LD script 标签。
     *
     * <p>使用百度专用的 cambrian.jsonld 上下文，帮助百度搜索引擎
     * 识别内容的发布和更新时间。
     *
     * @see <a href="https://ziyuan.baidu.com/college/courseinfo?id=2210">百度时间因子文档</a>
     */
    private String genBaiduScript(String title, String url, String publishDate, String updateDate) {
        // 百度时间因子的 pubDate 和 upDate 为核心字段，全部为空时跳过整个 script 标签
        if (!hasValue(publishDate) && !hasValue(updateDate)) {
            return "";
        }
        // 统一使用前逗号模式，避免各字段条件化时产生悬挂逗号
        var sb = new StringBuilder();
        sb.append("<script type=\"application/ld+json\">\n");
        sb.append("{\n");
        sb.append("  \"@context\": \"https://ziyuan.baidu.com/contexts/cambrian.jsonld\"");
        if (hasValue(url)) {
            sb.append(",\n  \"@id\": \"").append(JsonEscape.escapeJson(url)).append("\"");
        }
        if (hasValue(title)) {
            sb.append(",\n  \"title\": \"").append(JsonEscape.escapeJson(title)).append("\"");
        }
        if (hasValue(publishDate)) {
            sb.append(",\n  \"pubDate\": \"").append(JsonEscape.escapeJson(publishDate))
                .append("\"");
        }
        if (hasValue(updateDate)) {
            sb.append(",\n  \"upDate\": \"").append(JsonEscape.escapeJson(updateDate)).append("\"");
        }
        sb.append("\n}\n</script>\n");
        return sb.toString();
    }

    /**
     * 生成 Schema.org JSON-LD 结构化数据。
     *
     * <p>使用 BlogPosting 类型，被 Google、Bing 等搜索引擎用于
     * 富摘要（Rich Snippets）展示。
     *
     * @see <a href="https://schema.org/BlogPosting">Schema.org BlogPosting</a>
     */
    private String genSchemaOrgScript(SeoData seoData) {
        // 根据 pageType 推导 Schema.org @type：article→BlogPosting, profile→ProfilePage, 其他→WebPage
        var schemaType = switch (seoData.pageType()) {
            case "article" -> "BlogPosting";
            case "profile" -> "ProfilePage";
            default -> "WebPage";
        };
        // 使用 StringBuilder 动态构建 JSON-LD，字段为空时省略，避免无效结构化数据
        var sb = new StringBuilder();
        sb.append("<script type=\"application/ld+json\">\n");
        sb.append("{\n");
        sb.append("  \"@context\": \"https://schema.org\",\n");
        sb.append("  \"@type\": \"").append(schemaType).append("\"");
        if (hasValue(seoData.pageUrl())) {
            sb.append(",\n  \"mainEntityOfPage\": {\n");
            sb.append("    \"@type\": \"WebPage\",\n");
            sb.append("    \"@id\": \"").append(JsonEscape.escapeJson(seoData.pageUrl()))
                .append("\"\n");
            sb.append("  }");
        }
        if (hasValue(seoData.title())) {
            sb.append(",\n  \"headline\": \"").append(JsonEscape.escapeJson(seoData.title()))
                .append("\"");
        }
        if (hasValue(seoData.description())) {
            sb.append(",\n  \"description\": \"")
                .append(JsonEscape.escapeJson(seoData.description())).append("\"");
        }
        if (hasValue(seoData.googlePubDate())) {
            sb.append(",\n  \"datePublished\": \"")
                .append(JsonEscape.escapeJson(seoData.googlePubDate())).append("\"");
        }
        if (hasValue(seoData.googleUpdDate())) {
            sb.append(",\n  \"dateModified\": \"")
                .append(JsonEscape.escapeJson(seoData.googleUpdDate())).append("\"");
        }
        if (hasValue(seoData.author())) {
            sb.append(",\n  \"author\": {\n");
            sb.append("    \"@type\": \"Person\",\n");
            sb.append("    \"name\": \"").append(JsonEscape.escapeJson(seoData.author()))
                .append("\"\n");
            sb.append("  }");
        }
        if (hasValue(seoData.siteName())) {
            sb.append(",\n  \"publisher\": {\n");
            sb.append("    \"@type\": \"Organization\",\n");
            sb.append("    \"name\": \"").append(JsonEscape.escapeJson(seoData.siteName()))
                .append("\"");
            if (hasValue(seoData.siteLogo())) {
                sb.append(",\n    \"logo\": {\n");
                sb.append("      \"@type\": \"ImageObject\",\n");
                sb.append("      \"url\": \"").append(JsonEscape.escapeJson(seoData.siteLogo()))
                    .append("\"\n");
                sb.append("    }");
            }
            sb.append("\n  }");
        }
        if (hasValue(seoData.coverUrl())) {
            sb.append(",\n  \"image\": \"").append(JsonEscape.escapeJson(seoData.coverUrl()))
                .append("\"");
        }
        if (hasValue(seoData.pageUrl())) {
            sb.append(",\n  \"url\": \"").append(JsonEscape.escapeJson(seoData.pageUrl()))
                .append("\"");
        }
        if (hasValue(seoData.keywords())) {
            sb.append(",\n  \"keywords\": \"").append(JsonEscape.escapeJson(seoData.keywords()))
                .append("\"");
        }
        sb.append("\n}\n</script>\n");
        return sb.toString();
    }

    /**
     * 生成 Twitter Cards meta 标签。
     *
     * <p>Twitter/X 平台的内容卡片，当链接被分享时展示标题、描述和图片。
     * 支持 summary 和 summary_large_image 两种类型。
     * 站点/创作者的用户名和 ID 为可选字段，非空时才添加对应标签。
     *
     * @see
     * <a href="https://developer.twitter.com/en/docs/twitter-for-websites/cards/overview/abouts-cards">
     * Twitter Cards 文档</a>
     */
    private String genTwitterCards(SeoData seoData, String twitterCardsType,
        String twitterSiteUsername, String twitterSiteUserId, String twitterCreatorUsername,
        String twitterCreatorUserId) {
        StringBuilder sb = new StringBuilder();

        // card 类型标签始终添加
        sb.append("<meta name=\"twitter:card\" content=\"")
            .append(HtmlEscape.escapeHtml5(twitterCardsType)).append("\" />\n");

        if (hasValue(twitterSiteUsername)) {
            sb.append("<meta name=\"twitter:site\" content=\"")
                .append(HtmlEscape.escapeHtml5(twitterSiteUsername)).append("\" />\n");
        }
        if (hasValue(twitterSiteUserId)) {
            sb.append("<meta name=\"twitter:site:id\" content=\"")
                .append(HtmlEscape.escapeHtml5(twitterSiteUserId)).append("\" />\n");
        }
        if (hasValue(twitterCreatorUsername)) {
            sb.append("<meta name=\"twitter:creator\" content=\"")
                .append(HtmlEscape.escapeHtml5(twitterCreatorUsername)).append("\" />\n");
        }
        if (hasValue(twitterCreatorUserId)) {
            sb.append("<meta name=\"twitter:creator:id\" content=\"")
                .append(HtmlEscape.escapeHtml5(twitterCreatorUserId)).append("\" />\n");
        }
        if (hasValue(seoData.title())) {
            sb.append("<meta name=\"twitter:title\" content=\"")
                .append(HtmlEscape.escapeHtml5(seoData.title())).append("\" />\n");
        }
        if (hasValue(seoData.description())) {
            sb.append("<meta name=\"twitter:description\" content=\"")
                .append(HtmlEscape.escapeHtml5(seoData.description())).append("\" />\n");
        }
        if (hasValue(seoData.coverUrl())) {
            sb.append("<meta name=\"twitter:image\" content=\"")
                .append(HtmlEscape.escapeHtml5(seoData.coverUrl())).append("\" />\n");
        }

        return sb.toString();
    }

    // ======================== 辅助方法 ========================

    /**
     * 查询文章关联的标签名称，用逗号拼接作为 SEO 关键词。
     *
     * <p>通过 {@code post.spec.tags}（标签的 metadata.name 列表）批量查询
     * Tag 对象，提取 displayName 作为关键词。
     *
     * @param post 文章 Extension 对象
     * @return 逗号分隔的标签名字符串，无标签时返回空字符串
     */
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

    /**
     * 格式化时间戳为指定格式的字符串。
     *
     * @param instant 时间戳，为 null 时返回空字符串
     * @param formatter 日期格式化器
     * @param zoneId 时区
     * @return 格式化后的时间字符串，或空字符串
     */
    private String formatDateTime(Instant instant, DateTimeFormatter formatter, ZoneId zoneId) {
        return Optional.ofNullable(instant).map(inst -> inst.atZone(zoneId).format(formatter))
            .orElse("");
    }

    /**
     * 从上下文的 {@code name} 变量获取实体标识。
     *
     * <p>Post、SinglePage、Tag、Author 的路由会在上下文中注入 {@code name} 变量，
     * 值为实体的 metadata.name。
     *
     * @return metadata.name，获取失败时返回 null
     */
    private String getNameVariable(ITemplateContext context) {
        return Optional.ofNullable(context.getVariable("name")).map(Object::toString)
            .filter(s -> !s.isBlank()).orElse(null);
    }

    /**
     * 从上下文的 Vo 对象中获取 metadata.name。
     *
     * <p>Category 详情页不在上下文中注入独立的 {@code name} 变量，
     * 需要通过 Vo 对象的 {@link ExtensionVoOperator#getMetadata()} 提取。
     * 注意：第三方插件的 Vo（如 MomentVo）在上下文中可能是延迟加载的 Mono 对象，
     * 此方法无法处理这种情况，会返回 null。
     *
     * @param context Thymeleaf 模板上下文
     * @param voVariableName 上下文中 Vo 对象的变量名，如 "category"
     * @return metadata.name，获取失败时返回 null
     */
    private String getMetadataNameFromVo(ITemplateContext context, String voVariableName) {
        var vo = context.getVariable(voVariableName);
        if (vo instanceof ExtensionVoOperator evo) {
            return Optional.ofNullable(evo.getMetadata()).map(MetadataOperator::getName)
                .filter(s -> !s.isBlank()).orElse(null);
        }
        return null;
    }

    /**
     * 判断字符串是否有实际内容（非 null 且非空白）。
     * 用于输出阶段决定是否生成对应的 SEO 标签：无值则跳过，避免输出空标签。
     */
    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 将 SEO 覆盖值中的占位符替换为实际内容。
     *
     * <p>支持的占位符：
     * <ul>
     *   <li>{@code %TITLE%}    → 预注入 {@code <title>} 文本 → 实体固定值（如 post.spec.title）</li>
     *   <li>{@code %DESC%}     → 预注入 {@code <meta name="description">} content → 实体固定值（如 post
     *   .status.excerpt）</li>
     *   <li>{@code %SITENAME%} → 站点标题</li>
     *   <li>{@code %SITEDESC%} → 站点 SEO 描述</li>
     * </ul>
     *
     * @param value 原始字符串，可能包含占位符；null 时原样返回
     * @param phTitle {@code %TITLE%} 的展开值，由调用方按"预注入 → 实体固定值"链取好后传入
     * @param phDesc {@code %DESC%}  的展开值，由调用方按"预注入 → 实体固定值"链取好后传入
     * @param systemInfo 站点信息，用于替换 {@code %SITENAME%} 和 {@code %SITEDESC%}
     */
    private String applyPlaceholders(String value, String phTitle, String phDesc,
        SystemInfo systemInfo) {
        if (value == null || value.isBlank()) {
            return value;
        }
        var siteName = Optional.ofNullable(systemInfo.getTitle()).orElse("");
        var siteDesc =
            Optional.ofNullable(systemInfo.getSeo()).map(SystemInfo.SeoProp::getDescription)
                .orElse("");
        return value.replace("%TITLE%", phTitle != null ? phTitle : "")
            .replace("%DESC%", phDesc != null ? phDesc : "").replace("%SITENAME%", siteName)
            .replace("%SITEDESC%", siteDesc);
    }

    /**
     * 将相对路径或路径片段转为完整外部 URL，null 或空白时返回 null（表示无值）。
     */
    private String processPermalink(String path) {
        return hasValue(path) ? externalLinkProcessor.processLink(path) : null;
    }

    /**
     * 获取 Halo 系统路由规则配置，读取失败时回退到默认值。
     */
    private Mono<SystemSetting.ThemeRouteRules> getThemeRouteRules() {
        return client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG).mapNotNull(cm -> {
            var data = cm.getData();
            if (data == null) {
                return null;
            }
            return SystemSetting.get(data, SystemSetting.ThemeRouteRules.GROUP,
                SystemSetting.ThemeRouteRules.class);
        }).defaultIfEmpty(SystemSetting.ThemeRouteRules.empty());
    }

    /**
     * 返回第一个非空白（non-blank）的字符串，全部为空时返回空字符串。
     *
     * <p>用于实现 SEO 字段的优先级回退链，如：
     * {@code firstNonBlank(entityDesc, siteDesc, fallbackDesc)}
     */
    private String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * 从 head 模型中提取 {@code <title>} 标签的文本内容。
     *
     * @return 标题文本，不存在时返回 null
     */
    private String extractTitleFromModel(IModel model) {
        for (int i = 0; i < model.size() - 1; i++) {
            if (model.get(i) instanceof IOpenElementTag tag && "title".equalsIgnoreCase(
                tag.getElementCompleteName()) && model.get(i + 1) instanceof IText text) {
                var t = HtmlEscape.unescapeHtml(text.getText()).strip();
                return t.isBlank() ? null : t;
            }
        }
        return null;
    }

    /**
     * 从 head 模型中提取 {@code <meta name="description">} 的 content 属性值。
     *
     * @return 描述文本，不存在时返回 null
     */
    private String extractMetaDescriptionFromModel(IModel model) {
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i) instanceof IStandaloneElementTag tag && "meta".equalsIgnoreCase(
                tag.getElementCompleteName()) && "description".equalsIgnoreCase(
                tag.getAttributeValue("name"))) {
                var content = tag.getAttributeValue("content");
                return (content != null && !content.isBlank()) ? content : null;
            }
        }
        return null;
    }

    // ======================== 数据结构 ========================

    /**
     * SEO 覆盖值，从"SEO 覆盖"设置中读取，优先级高于页面 &lt;title&gt; 和 &lt;meta description&gt;。
     * title/description 为 null 表示未配置，不覆盖。
     */
    private record SeoOverride(String title, String description) {
        static final SeoOverride NONE = new SeoOverride(null, null);
    }

    /**
     * SEO 数据记录，封装所有 SEO 标签生成所需的字段。
     *
     * <p>所有页面类型的 SEO 处理器最终将数据封装为此 record，
     * 然后交给 {@link #generateSeoTags} 统一输出。
     *
     * @param title 页面标题
     * @param description 页面描述/摘要
     * @param coverUrl 封面图 URL（已处理为完整外部链接）
     * @param pageUrl 页面 URL（已处理为完整外部链接）
     * @param author 作者名称
     * @param baiduPubDate 百度格式发布时间
     * @param baiduUpdDate 百度格式更新时间
     * @param googlePubDate Google 格式发布时间
     * @param googleUpdDate Google 格式更新时间
     * @param siteName 站点名称
     * @param siteLogo 站点 Logo URL
     * @param keywords 关键词
     * @param pageType 页面语义类型，用于推导 og:type 和 Schema.org @type。
     * 取值：website（列表/聚合页）、article（内容详情页）、profile（作者页）
     */
    private record SeoData(String title, String description, String coverUrl, String pageUrl,
                           String author, String baiduPubDate, String baiduUpdDate,
                           String googlePubDate, String googleUpdDate, String siteName,
                           String siteLogo, String keywords, String pageType) {
    }
}
