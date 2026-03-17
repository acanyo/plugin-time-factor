# 时间因子SEO 插件

时间因子SEO 是一个专为 Halo 博客系统设计的 SEO 优化插件，通过动态注入结构化数据（meta/script 标签）来提升站点在各大搜索引擎的收录与排名表现。

## 🌐 演示与交流

- **演示站点**：[https://www.lik.cc/](https://www.lik.cc/)
- **文档**：[https://docs.lik.cc/](https://docs.lik.cc/)
- **QQ 交流群**：[![QQ群](https://www.lik.cc/upload/iShot_2025-03-03_16.03.00.png)](https://www.lik.cc/upload/iShot_2025-03-03_16.03.00.png)

## 功能特性

### 结构化数据注入效果示例

下图展示了插件在页面中为不同搜索引擎动态注入的结构化数据（OG、字节、百度、Google 等）：

![结构化数据注入效果示例](https://www.lik.cc/upload/Google%20Chrome%202025-06-28%2011.51.07.png)

### 🎯 智能 SEO 注入
- **多平台支持**：支持 Google、百度、字节、OG 等主流搜索引擎的结构化数据格式
- **动态内容**：根据页面类型和内容自动填充标题、描述、作者、标签等字段

### 🔧 配置灵活
- 支持启用/禁用搜索引擎优化功能
- 可配置默认封面图片
- 自动获取站点信息（标题、Logo、关键词等）

### 🛡️ 性能优化
- 普通用户访问时零性能影响
- 结构化数据格式紧凑，无多余注释
- 异常处理完善，保证系统稳定性

## 支持注入的页面类型

- [文章详情页](https://docs.halo.run/developer-guide/theme/template-variables/post)

## 支持注入的数据类型

### Link 标签
- canonical 链接
- alternate 链接

### Open Graph (OG)
- 标题、描述、封面图
- 作者、标签、发布时间
- 站点信息

### 百度结构化数据
- 文章标题、摘要、封面
- 作者、分类、发布时间
- 站点名称、Logo

### 字节跳动结构化数据
- 内容标题、描述、图片
- 作者信息、发布时间
- 站点标识

### Google JSON-LD (schema.org)
- 文章结构化数据
- 作者信息
- 发布时间（带时区）

### Twitter Cards

- 标题、描述、图片
- 站点 Twitter/X 账号信息
- 作者 Twitter/X 账号信息

## 支持的搜索引擎

- **Google** (Googlebot)
- **百度** (Baiduspider)
- **字节跳动** (Bytespider)
- **必应** (Bingbot)
- **360搜索** (360spider)
- **搜狗** (Sogou)
- **Yandex**
- **DuckDuckGo** (DuckDuckBot)
- **雅虎** (Slurp)
- **Ask** (Teoma)

## 开发环境

- Java 21+
- Node.js 18+
- pnpm

## 开发

```bash
# 构建插件
./gradlew build

# 开发前端
cd ui
pnpm install
pnpm dev
```

## 构建

```bash
./gradlew build
```

构建完成后，可以在 `build/libs` 目录找到插件 jar 文件。

## 许可证

[GPL-3.0](./LICENSE) © [Handsome](https://github.com/somehand) 